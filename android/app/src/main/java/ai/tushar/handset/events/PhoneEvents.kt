package ai.tushar.handset.events

import ai.tushar.handset.context.ContextStore
import ai.tushar.handset.context.EventSendRow
import ai.tushar.handset.context.NotificationCollector
import ai.tushar.handset.context.NotificationSender
import ai.tushar.handset.context.NotificationRow
import ai.tushar.handset.context.Recording
import ai.tushar.handset.context.Source
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The switches in Settings, under Phone events. Each is off until the user turns it on. */
enum class Trigger(val pref: String) { BATTERY_LOW("battery_low"), CHARGING("charging"), PLACES("places"), NOTIFICATIONS("notifications") }

/** A person or an app whose notifications send an event */
@Serializable
sealed interface From {
    val label: String

    @Serializable
    data class Person(val name: String) : From {
        override val label: String get() = name
    }

    @Serializable
    data class App(val packageName: String, override val label: String) : From
}

/**
 * Phone events: what happens on the phone, sent to the one address the user gave, such as a Claude routine's fire URL.
 * Battery and charging come from Android's battery broadcasts while the app's process runs, places from the visits
 * [ai.tushar.handset.context.PlaceCollector] finds, and notifications from the notification listener. Each occurrence
 * is kept in the day record and sent at most once, and no more than [CAP] are sent in an hour.
 */
class PhoneEvents(
    private val context: Context,
    private val store: ContextStore,
    private val recording: Recording,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val preferences = context.getSharedPreferences("events", Context.MODE_PRIVATE)
    val destinations = DestinationStore(preferences, KeystoreSealer())
    val delivery = Delivery(store, destinations::load, HttpSender())

    fun isOn(trigger: Trigger): Boolean = preferences.getBoolean(trigger.pref, false)

    fun setOn(trigger: Trigger, on: Boolean) {
        val edit = preferences.edit().putBoolean(trigger.pref, on)
        if (on && !isOn(trigger)) {
            edit.putLong(trigger.pref + ON_SINCE, clock())
            // The battery as it is when the first battery switch goes on isn't news, so the next reading starts afresh
            if (trigger in BATTERY_TRIGGERS && BATTERY_TRIGGERS.none(::isOn)) edit.remove(BATTERY_MEMORY)
        }
        edit.apply()
        sync()
    }

    /** The battery level below which Battery low is sent */
    var threshold: Int
        get() = preferences.getInt(THRESHOLD, DEFAULT_THRESHOLD)
        set(value) = preferences.edit().putInt(THRESHOLD, value).apply()

    var from: List<From>
        get() = preferences.getString(FROM, null)?.let { runCatching { json.decodeFromString<List<From>>(it) }.getOrNull() }.orEmpty()
        set(value) = preferences.edit().putString(FROM, json.encodeToString(value)).apply()

    fun setDestination(destination: Destination?) {
        destinations.save(destination)
        sync()
    }

    /** Whether places events can happen: Places must be recording for visits to be found */
    val placesRecording: Boolean get() = recording.recording(Source.PLACES)

    // Sending

    /** Keeps [event] and sends it, unless it was kept before or there is nowhere to send it. Returns the kept row. */
    suspend fun emit(event: PhoneEvent): EventSendRow? {
        if (destinations.load() == null) return null
        val row = store.claimEventSend(event.row()) ?: return null
        EventSendWorker.enqueue(context, row.id)
        return row
    }

    /**
     * Queues a send for each event still to send, since the process can die between keeping an event and queueing it.
     * A send already queued stays as it is.
     */
    suspend fun resume() = store.unsentEventSends().forEach { EventSendWorker.enqueue(context, it.id) }

    suspend fun sendTest(): EventSendRow? = clock().let { now -> emit(PhoneEvent(EventType.TEST, now, "test|$now", battery = batteryLevel())) }

    // Battery and charging

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            reading(intent)?.let(::onBattery)
        }
    }
    private var listening = false

    /**
     * Listens to the battery while a battery switch is on and there is an address, and stops otherwise, and queues any
     * send left unqueued. Safe to call often.
     */
    @Synchronized
    fun sync() {
        scope.launch { resume() }
        val wanted = destinations.load() != null && BATTERY_TRIGGERS.any(::isOn)
        if (wanted == listening) return
        val app = context.applicationContext
        if (wanted) {
            // Android hands over the sticky reading at once, which only sets the memory
            ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            app.unregisterReceiver(receiver)
        }
        listening = wanted
    }

    private fun reading(intent: Intent): Battery.Reading? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return Battery.Reading(clock(), level * 100 / scale, intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
    }

    private fun batteryLevel(): Int? = context.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

    @Synchronized
    private fun onBattery(reading: Battery.Reading) {
        val memory = preferences.getString(BATTERY_MEMORY, null)?.let { runCatching { json.decodeFromString<Battery.Memory>(it) }.getOrNull() }
        val step = Battery.step(memory, reading, threshold)
        if (step.memory != memory) preferences.edit().putString(BATTERY_MEMORY, json.encodeToString(step.memory)).apply()
        val wanted = step.events.filter { if (it.type == EventType.BATTERY_LOW) isOn(Trigger.BATTERY_LOW) else isOn(Trigger.CHARGING) }
        if (wanted.isNotEmpty()) scope.launch { wanted.forEach { emit(it) } }
    }

    // Places

    /** A visit to a named place began or ended. An unnamed place sends nothing. */
    suspend fun onVisit(arrived: Boolean, visitId: Long) {
        if (!isOn(Trigger.PLACES)) return
        val place = store.visitLabel(visitId) ?: return
        val type = if (arrived) EventType.ARRIVED else EventType.LEFT
        emit(PhoneEvent(type, clock(), "${type.code}|$visitId", place = place))
    }

    // Notifications

    fun onNotification(notification: StatusBarNotification, app: String) {
        if (!isOn(Trigger.NOTIFICATIONS)) return
        val chosen = from.ifEmpty { return }
        // A messaging notification lists earlier messages too; the newest is the one that just came
        val newest = NotificationCollector.rows(notification, app, context.packageName).maxByOrNull { it.at } ?: return
        if (newest.at < preferences.getLong(Trigger.NOTIFICATIONS.pref + ON_SINCE, 0L)) return
        val event = notificationEvent(newest, chosen, clock()) ?: return
        scope.launch { emit(event) }
    }

    companion object {
        /** At most this many POSTs in an hour, tries included, below a Claude routine's 30 fires */
        const val CAP = 20
        const val DEFAULT_THRESHOLD = 20
        val THRESHOLDS = listOf(10, 15, 20, 30, 50)

        private val BATTERY_TRIGGERS = listOf(Trigger.BATTERY_LOW, Trigger.CHARGING)
        private const val THRESHOLD = "threshold"
        private const val FROM = "from"
        private const val BATTERY_MEMORY = "battery_memory"
        private const val ON_SINCE = "_on_since"
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The event a kept notification sends when it is from someone in [from]: the person's name the user chose, or
         * the app's, never the notification's text. A chosen name that isn't this notification's person, such as an
         * ordinary notification's title, sends the app's name alone.
         */
        fun notificationEvent(row: NotificationRow, from: List<From>, now: Long): PhoneEvent? {
            val person = from.firstOrNull { it is From.Person && it.name == row.sender }
            val match = person ?: from.firstOrNull { it is From.App && it.packageName == row.packageName } ?: return null
            val occurrence = "notification|${row.dedupe}|${row.text.hashCode()}"
            return when {
                match is From.Person && row.fromPerson -> PhoneEvent(EventType.NOTIFICATION, now, occurrence, person = match.name, app = row.app)
                else -> PhoneEvent(EventType.NOTIFICATION, now, occurrence, app = row.app)
            }
        }

        /**
         * What the picker offers, with [current] kept on top: as people, only those who sent messages or are a
         * notification's person, and as apps, every app.
         */
        fun choices(senders: List<NotificationSender>, current: List<From>): Pair<List<From.Person>, List<From.App>> {
            val people = senders.filter { it.fromPerson && it.sender.isNotBlank() && it.sender != "Me" }.map { From.Person(it.sender) }
            val apps = senders.map { From.App(it.packageName, it.app) }
            return (current.filterIsInstance<From.Person>() + people).distinct() to (current.filterIsInstance<From.App>() + apps).distinctBy { it.packageName }
        }
    }
}
