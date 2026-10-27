package ai.tushar.handset.context

import ai.tushar.handset.device.Access
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the phone can remember, each with its own switch. All are off until the user turns them on. */
enum class Source(val title: String, val detail: String, val pref: String) {
    // The pref predates the store, so a phone that had it on keeps it on
    SCREEN("Screen text", "The text of the screens you open, for search.", "collect_context"),
    NOTIFICATIONS("Notifications", "Notifications as they arrive, for catching up.", "record_notifications"),
    APP_USE("App use", "Which app was open and when, from Android's usage record.", "record_app_use"),
    PLACES("Places", "Where you stayed 10 minutes or more, as coordinates. No place names are looked up.", "record_places"),
    ;

    /** Whether Android lets the app read this source */
    fun granted(context: Context): Boolean = when (this) {
        SCREEN -> true
        NOTIFICATIONS -> {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            enabled.split(':').mapNotNull { ComponentName.unflattenFromString(it) }.any { it.packageName == context.packageName }
        }
        APP_USE -> Access.SCREEN_TIME.granted(context)
        PLACES -> Access.LOCATION.granted(context) &&
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    /** What to turn on in Android when the switch is on but Android doesn't allow it yet */
    val needs: String?
        get() = when (this) {
            SCREEN -> null
            NOTIFICATIONS -> "Needs Notification access."
            APP_USE -> "Needs Usage access."
            PLACES -> "Needs location, all the time."
        }
}

/** How long screen text and notifications are kept. App use, places and agent steps are kept a year. */
enum class Keep(val days: Int, val label: String) { WEEK(7, "7 days"), MONTH(30, "30 days"), QUARTER(90, "90 days") }

/** The switch for each [Source], "Pause for an hour" and how long text is kept, with the app's other settings */
class Recording(private val context: Context, private val preferences: SharedPreferences, private val clock: () -> Long = System::currentTimeMillis) {
    private val _pausedUntil = MutableStateFlow(preferences.getLong(PAUSED_UNTIL, 0L))

    /** When recording starts again; a time already past when it isn't paused */
    val pausedUntil: StateFlow<Long> get() = _pausedUntil

    val paused: Boolean get() = clock() < _pausedUntil.value

    fun isOn(source: Source): Boolean = preferences.getBoolean(source.pref, false)

    /** Turning a source on starts it from now, so nothing from before is read back */
    fun setOn(source: Source, on: Boolean) {
        val edit = preferences.edit().putBoolean(source.pref, on)
        if (on && !isOn(source)) edit.putLong(source.pref + ON_SINCE, clock())
        edit.apply()
    }

    /**
     * Whether something [source] gave at [at] happened while it wasn't recording: before it was last turned on, or
     * during a pause. Collectors that read back in time, as app use and a notification's earlier messages do, skip it.
     */
    fun excluded(source: Source, at: Long): Boolean = at < onSince(source) || pauses().any { (from, to) -> at in from until to }

    /** When [source] was last turned on, or 0 when it was on before this was kept */
    fun onSince(source: Source): Long = preferences.getLong(source.pref + ON_SINCE, 0L)

    /** The pauses of the last two days, as start and end, the one going on included */
    fun pauses(): List<Pair<Long, Long>> = preferences.getString(PAUSES, null).orEmpty().split(';').mapNotNull { pause ->
        val (from, to) = pause.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        (from.toLongOrNull() ?: return@mapNotNull null) to (to.toLongOrNull() ?: return@mapNotNull null)
    }

    /** Whether to keep what [source] gives now: its switch is on, Android allows it, and recording isn't paused */
    fun recording(source: Source): Boolean = isOn(source) && !paused && source.granted(context)

    var keep: Keep
        get() = preferences.getInt(KEEP_DAYS, Keep.MONTH.days).let { days -> Keep.entries.firstOrNull { it.days == days } ?: Keep.MONTH }
        set(value) = preferences.edit().putInt(KEEP_DAYS, value.days).apply()

    /** Whether any source is switched on */
    val anyOn: Boolean get() = Source.entries.any(::isOn)

    fun pauseForAnHour() {
        val now = clock()
        val (going, past) = pauses().partition { it.second > now }
        setPausedUntil(now + HOUR_MS, past + ((going.minOfOrNull { it.first } ?: now) to now + HOUR_MS))
    }

    /** Ends the pause now, and keeps it as the span it lasted */
    fun resume() {
        val now = clock()
        setPausedUntil(now, pauses().map { (from, to) -> if (to > now) from to now else from to to })
    }

    private fun setPausedUntil(at: Long, pauses: List<Pair<Long, Long>>) {
        val kept = pauses.filter { it.second >= clock() - PAUSES_KEPT_MS }.joinToString(";") { "${it.first}:${it.second}" }
        preferences.edit().putLong(PAUSED_UNTIL, at).putString(PAUSES, kept).apply()
        _pausedUntil.value = at
    }

    private companion object {
        const val PAUSED_UNTIL = "recording_paused_until"
        const val KEEP_DAYS = "recording_keep_days"
        const val PAUSES = "recording_pauses"
        const val ON_SINCE = "_on_since"
        const val HOUR_MS = 60 * 60_000L

        /** Longer than any read back: app use reads a day back at most */
        const val PAUSES_KEPT_MS = 2 * 24 * HOUR_MS
    }
}
