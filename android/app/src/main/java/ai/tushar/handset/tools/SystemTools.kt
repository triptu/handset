package ai.tushar.handset.tools

import ai.tushar.handset.device.Access
import ai.tushar.handset.device.NotificationWatcher
import ai.tushar.handset.device.PhoneAccessibilityService
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Telephony
import android.provider.Settings
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import kotlin.math.abs

/**
 * Tools that do in one call what takes several taps through an app's screens: alarms, timers, the phone's state,
 * text messages, Do not disturb and volume. They use the system's own APIs, so they need no screen.
 */
class SystemTools(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    val all: List<PhoneTool> = listOf(
        PhoneTool(
            slug = "set_alarm",
            description = "Set an alarm in the phone's clock app without opening it. Returns the alarm as set and when it next rings, " +
                "confirmed when it is the phone's next alarm.",
            inputSchema = objectSchema(
                "hour" to prop("integer", "0 to 23"),
                "minute" to prop("integer", "0 to 59"),
                "label" to prop("string", "A name for the alarm"),
                "days" to stringArrayProp("Days it repeats on; leave out for a one-time alarm", DAYS.keys.toList()),
                required = listOf("hour", "minute"),
            ),
        ) { args ->
            val hour = args.requireInt("hour").also { if (it !in 0..23) throw ToolFailure("hour must be 0 to 23") }
            val minute = args.requireInt("minute").also { if (it !in 0..59) throw ToolFailure("minute must be 0 to 59") }
            val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            args.string("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            val dayNames = args.strings("days")
            val days = dayNames.map { DAYS[it.lowercase()] ?: throw ToolFailure("Unknown day $it") }
            if (days.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
            val ringsAt = nextRing(hour, minute, days)
            val described = buildString {
                append("%02d:%02d".format(hour, minute))
                args.string("label")?.let { append(" \"$it\"") }
                append(", on, ")
                append(if (days.isEmpty()) "once" else "repeats " + dayNames.joinToString { it.lowercase().take(3).replaceFirstChar(Char::uppercase) })
                append(", rings ${ringsAt.format(ALARM_TIME)}")
            }
            // Android tells apps only the phone's next alarm, so a new alarm can be read back only when none rings before it
            val earlier = alarms.nextAlarmClock?.takeIf { it.triggerTime < ringsAt.toInstant().toEpochMilli() - MINUTE_MS }
            PhoneAccessibilityService.require().launch(intent)
            if (earlier != null) return@PhoneTool ToolOutput("Sent the clock app $described. Not read back, since the phone's next alarm rings before it (${alarmText(earlier)}).")
            // The clock app sets the alarm a moment later
            val deadline = SystemClock.uptimeMillis() + CLOCK_APP_MS
            while (!isRing(alarms.nextAlarmClock, ringsAt) && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
            if (isRing(alarms.nextAlarmClock, ringsAt)) {
                ToolOutput("Set $described. Confirmed: it is the phone's next alarm.")
            } else {
                ToolOutput("Asked the clock app for $described, but the phone's next alarm doesn't show it yet. ${nextAlarm()} Check in the clock app.")
            }
        },
        PhoneTool(
            slug = "set_timer",
            description = "Start a countdown timer in the phone's clock app without opening it",
            inputSchema = objectSchema(
                "seconds" to prop("integer", "Length of the timer, 1 to 86400"),
                "label" to prop("string", "A name for the timer"),
                required = listOf("seconds"),
            ),
        ) { args ->
            val seconds = args.requireInt("seconds").also { if (it !in 1..MAX_TIMER_S) throw ToolFailure("seconds must be 1 to $MAX_TIMER_S") }
            val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            args.string("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            PhoneAccessibilityService.require().launch(intent)
            ToolOutput("Started a timer for ${duration(seconds)}")
        },
        PhoneTool(
            slug = "device_status",
            concurrent = true,
            description = "The phone's state in one call: time, battery and charging, network, Wi-Fi, Bluetooth, airplane mode, " +
                "Do not disturb, ringer mode, volumes, brightness and the next alarm",
            inputSchema = objectSchema(),
        ) {
            ToolOutput(status())
        },
        PhoneTool(
            slug = "set_do_not_disturb",
            description = "Turn Do not disturb on or off. Off ends only the Do not disturb this tool turned on; the user's own " +
                "schedule or switch stays. Android doesn't let apps switch Wi-Fi, Bluetooth, airplane mode, rotation or the " +
                "flashlight this way; for those, press quick_settings and tap the tile.",
            inputSchema = objectSchema("on" to prop("boolean", "On or off"), required = listOf("on")),
        ) { args ->
            val on = args.boolean("on") ?: throw ToolFailure("`on` is required")
            // An enabled notification listener has Do not disturb access, which this needs
            if (!notifications.isNotificationPolicyAccessGranted) NotificationWatcher.require()
            notifications.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
            val deadline = SystemClock.uptimeMillis() + TOGGLE_SETTLE_MS
            while ((notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) == on && SystemClock.uptimeMillis() < deadline) {
                delay(POLL_MS)
            }
            val stillOn = !on && notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            val note = if (stillOn) " Do not disturb is still on from the user's own schedule or switch, which apps can't end." else ""
            ToolOutput("Turned Do not disturb ${if (on) "on" else "off"}.$note\n\n${status()}")
        },
        PhoneTool(
            slug = "read_sms",
            concurrent = true,
            description = "Read text messages (SMS) on the phone, newest first, such as a one-time code that just arrived. Filter by " +
                "sender, by text or by how recent. The user turns it on in Handset's Settings.",
            inputSchema = objectSchema(
                "from" to prop("string", "Part of the sender's number or name, such as HDFC or +9198"),
                "contains" to prop("string", "Text the message must contain, matched without case, such as OTP"),
                "since_minutes" to prop("integer", "Only messages from the last this many minutes"),
                "box" to prop("string", "Received, sent or both; received by default", enum = listOf("received", "sent", "all")),
                "limit" to prop("integer", "How many to return; 10 by default, 50 at most"),
            ),
        ) { args ->
            ToolOutput(readSms(args))
        },
        PhoneTool(
            slug = "set_volume",
            description = "Set a volume as a percentage",
            inputSchema = objectSchema(
                "stream" to prop("string", "Which volume", enum = STREAMS.keys.toList()),
                "percent" to prop("integer", "0 to 100"),
                required = listOf("stream", "percent"),
            ),
        ) { args ->
            val name = args.requireString("stream")
            val stream = STREAMS[name] ?: throw ToolFailure("Unknown stream $name")
            val percent = args.requireInt("percent").coerceIn(0, 100)
            val max = audio.getStreamMaxVolume(stream)
            try {
                audio.setStreamVolume(stream, Math.round(max * percent / 100f), 0)
            } catch (_: SecurityException) {
                throw ToolFailure("Android blocks changing the $name volume while Do not disturb is on")
            }
            ToolOutput("Set $name volume to ${volume(stream)}")
        },
    )

    private fun status(): String {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) }
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val network = when {
            capabilities == null -> "offline"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "connected"
        }
        val wifi = context.getSystemService(WifiManager::class.java).isWifiEnabled
        val resolver = context.contentResolver
        val bluetooth = Settings.Global.getInt(resolver, Settings.Global.BLUETOOTH_ON, 0) == 1
        val airplane = Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val dnd = when (notifications.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "on (priority only)"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "on (alarms only)"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "on (total silence)"
            else -> "unknown"
        }
        val ringer = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "sound"
        }
        val brightness = runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / MAX_BRIGHTNESS }.getOrNull()
        val autoBrightness = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        }.getOrDefault(false)
        return buildString {
            appendLine("Time: ${ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm z"))}")
            appendLine("Battery: ${level?.let { "$it%" } ?: "unknown"}${if (plugged) ", charging" else ""}")
            appendLine("Network: $network")
            appendLine("Wi-Fi: ${onOff(wifi)}")
            appendLine("Bluetooth: ${onOff(bluetooth)}")
            appendLine("Airplane mode: ${onOff(airplane)}")
            appendLine("Do not disturb: $dnd")
            appendLine("Ringer: $ringer")
            appendLine("Volume: " + STREAMS.entries.joinToString(", ") { (name, stream) -> "$name ${volume(stream)}" })
            appendLine("Brightness: ${brightness?.let { "about $it%" } ?: "unknown"}${if (autoBrightness) ", adaptive" else ""}")
            append(nextAlarm())
        }
    }

    private fun nextAlarm(): String {
        val next = alarms.nextAlarmClock ?: return "The phone has no alarm set."
        return "Next alarm: ${alarmText(next)}."
    }

    /** When an alarm rings, and the app that set it, since a calendar or a routine app can set the phone's next alarm too */
    private fun alarmText(alarm: AlarmManager.AlarmClockInfo): String {
        val time = Instant.ofEpochMilli(alarm.triggerTime).atZone(ZoneId.systemDefault()).format(ALARM_TIME)
        val app = alarm.showIntent?.creatorPackage?.let { packageName ->
            runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)
        }
        return if (app != null) "$time, set by $app" else time
    }

    private fun isRing(alarm: AlarmManager.AlarmClockInfo?, ringsAt: ZonedDateTime): Boolean =
        alarm != null && abs(alarm.triggerTime - ringsAt.toInstant().toEpochMilli()) < MINUTE_MS

    /** When an alarm at [hour]:[minute] next rings: today if that's still ahead, else the next day it's set for */
    private fun nextRing(hour: Int, minute: Int, days: List<Int>): ZonedDateTime {
        val now = ZonedDateTime.now()
        val today = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        return (0..7).map { today.plusDays(it.toLong()) }.first { time ->
            // Calendar counts days from Sunday = 1; java.time from Monday = 1
            time.isAfter(now) && (days.isEmpty() || (time.dayOfWeek.value % 7 + 1) in days)
        }
    }

    private fun volume(stream: Int): String = "${audio.getStreamVolume(stream) * 100 / audio.getStreamMaxVolume(stream).coerceAtLeast(1)}%"

    /** Messages that match the filters, newest first, as a block each: when, from or to whom, then the text */
    private fun readSms(args: ToolArgs): String {
        Access.SMS.require(context)
        val uri = when (args.string("box")) {
            "sent" -> Telephony.Sms.Sent.CONTENT_URI
            "all" -> Telephony.Sms.CONTENT_URI
            else -> Telephony.Sms.Inbox.CONTENT_URI
        }
        val where = ArrayList<String>()
        val values = ArrayList<String>()
        args.string("from")?.let { where.add("${Telephony.Sms.ADDRESS} LIKE ?"); values.add("%$it%") }
        args.string("contains")?.let { where.add("${Telephony.Sms.BODY} LIKE ?"); values.add("%$it%") }
        args.int("since_minutes")?.let { where.add("${Telephony.Sms.DATE} >= ?"); values.add((System.currentTimeMillis() - it * 60_000L).toString()) }
        val limit = (args.int("limit") ?: SMS_DEFAULT_LIMIT).coerceIn(1, SMS_MAX_LIMIT)
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ)
        val blocks = ArrayList<String>()
        context.contentResolver.query(uri, projection, where.joinToString(" AND ").ifEmpty { null }, values.toTypedArray(), "${Telephony.Sms.DATE} DESC")?.use { cursor ->
            while (blocks.size < limit && cursor.moveToNext()) {
                val address = cursor.getString(0).orEmpty()
                val sent = cursor.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT
                val time = Instant.ofEpochMilli(cursor.getLong(2)).atZone(ZoneId.systemDefault()).format(SMS_TIME)
                val unread = if (!sent && cursor.getInt(4) == 0) " · unread" else ""
                blocks.add("$time · ${if (sent) "to" else "from"} $address$unread\n${cursor.getString(1).orEmpty()}")
            }
        }
        return blocks.joinToString("\n\n").ifEmpty { "No text messages match" }
    }

    private fun onOff(value: Boolean) = if (value) "on" else "off"

    private fun duration(seconds: Int): String = listOfNotNull(
        (seconds / 3600).takeIf { it > 0 }?.let { "$it h" },
        (seconds % 3600 / 60).takeIf { it > 0 }?.let { "$it min" },
        (seconds % 60).takeIf { it > 0 }?.let { "$it s" },
    ).joinToString(" ")

    private companion object {
        const val CLOCK_APP_MS = 1_500L
        const val POLL_MS = 25L
        const val TOGGLE_SETTLE_MS = 400L
        const val MAX_TIMER_S = 86_400
        const val MAX_BRIGHTNESS = 255
        const val SMS_DEFAULT_LIMIT = 10
        const val SMS_MAX_LIMIT = 50
        val SMS_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
        val ALARM_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
        const val MINUTE_MS = 60_000L
        val DAYS = mapOf(
            "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY, "wednesday" to Calendar.WEDNESDAY, "thursday" to Calendar.THURSDAY,
            "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY, "sunday" to Calendar.SUNDAY,
        )
        val STREAMS = mapOf("media" to AudioManager.STREAM_MUSIC, "ring" to AudioManager.STREAM_RING, "alarm" to AudioManager.STREAM_ALARM)
    }
}
