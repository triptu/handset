package ai.tushar.handset.context

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns Android's usage events into app sessions while App use is on. It reads from where the last read left off,
 * when the app opens, before a tool reads the day, and from [ContextWorker] every few hours. Android keeps these
 * events for days, so nothing is lost between reads.
 */
class SessionCollector(private val context: Context, private val store: ContextStore, private val recording: Recording) {
    private val preferences = context.getSharedPreferences("sessions", Context.MODE_PRIVATE)
    private val lock = Mutex()

    suspend fun pull(now: Long = System.currentTimeMillis()) = lock.withLock {
        if (!recording.recording(Source.APP_USE)) {
            // Nothing from while App use is off or paused is read later
            preferences.edit().putLong(CURSOR, now).apply()
            return@withLock
        }
        // A session still open at the last read is read again from its start, so it keeps its row and grows
        val from = maxOf(preferences.getLong(CURSOR, now - FIRST_READ_MS), now - FIRST_READ_MS, recording.onSince(Source.APP_USE), store.deletedThrough())
        val events = ArrayList<Event>()
        val usage = context.getSystemService(UsageStatsManager::class.java).queryEvents(from, now)
        val event = UsageEvents.Event()
        while (usage.hasNextEvent()) {
            usage.getNextEvent(event)
            events.add(Event(event.eventType, event.packageName, event.className, event.timeStamp))
        }
        val ignored = setOfNotNull(context.packageName, launcher(), SYSTEM_UI)
        val result = sessionsFrom(recorded(events, recording.onSince(Source.APP_USE), recording.pauses()), now, ignored)
        store.putSessions(result.sessions.map { SessionRow(packageName = it.packageName, app = appLabel(it.packageName), start = it.start, end = it.end) })
        preferences.edit().putLong(CURSOR, result.openSince ?: now).apply()
    }

    /** A usage event, as the parts the sessions need */
    class Event(val type: Int, val packageName: String, val className: String?, val at: Long)

    class Span(val packageName: String, val start: Long, val end: Long)

    /** [openSince] is the start of the earliest session still open at the end, where the next read should begin */
    class Result(val sessions: List<Span>, val openSince: Long?)

    private fun launcher(): String? =
        context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName

    private fun appLabel(packageName: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    companion object {
        private const val CURSOR = "cursor"
        private const val SYSTEM_UI = "com.android.systemui"
        private const val FIRST_READ_MS = 24 * 60 * 60_000L

        /** Leaving an app and coming back within this time is one session */
        const val MERGE_GAP_MS = 10_000L

        /** Shorter sessions, as passing through an app on the way to another, are dropped */
        const val MIN_SESSION_MS = 5_000L

        /**
         * The events from while App use was recording: none from before it was turned on or during a pause, and every
         * session open when a pause began ends there.
         */
        fun recorded(events: List<Event>, onSince: Long, pauses: List<Pair<Long, Long>>): List<Event> =
            events.filter { event -> event.at >= onSince && pauses.none { (from, to) -> event.at in from until to } } +
                pauses.filter { it.first >= onSince }.map { Event(UsageEvents.Event.SCREEN_NON_INTERACTIVE, "android", null, it.first) }

        /**
         * Pairs each app's activities coming to the front and leaving. An app's session starts when its first activity
         * resumes and ends when its last one pauses, since its next screen resumes before the last one stops. The screen
         * turning off or locking ends every session. A session still open at [now] ends at [now].
         */
        fun sessionsFrom(events: List<Event>, now: Long, ignored: Set<String>): Result {
            val resumed = HashMap<String, MutableSet<String?>>()
            val openedAt = HashMap<String, Long>()
            val spans = ArrayList<Span>()
            fun close(packageName: String, at: Long) {
                val start = openedAt.remove(packageName) ?: return
                resumed.remove(packageName)
                val last = spans.lastOrNull()
                // Back to the same app quickly, as after a permission dialog
                if (last != null && last.packageName == packageName && start - last.end <= MERGE_GAP_MS) {
                    spans[spans.lastIndex] = Span(packageName, last.start, at)
                } else {
                    spans.add(Span(packageName, start, at))
                }
            }
            events.sortedBy { it.at }.forEach { event ->
                if (event.packageName in ignored) return@forEach
                when (event.type) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        resumed.getOrPut(event.packageName) { HashSet() }.add(event.className)
                        openedAt.putIfAbsent(event.packageName, event.at)
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                        val activities = resumed[event.packageName] ?: return@forEach
                        activities.remove(event.className)
                        if (activities.isEmpty()) close(event.packageName, event.at)
                    }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN, UsageEvents.Event.DEVICE_SHUTDOWN ->
                        openedAt.keys.toList().forEach { close(it, event.at) }
                }
            }
            val open = openedAt.keys.toList()
            open.forEach { close(it, now) }
            // An open session may have joined the one before it, so the next read starts at the joined one's start
            val openSince = open.mapNotNull { name -> spans.lastOrNull { it.packageName == name }?.start }.minOrNull()
            return Result(spans.filter { it.end - it.start >= MIN_SESSION_MS }, openSince)
        }
    }
}
