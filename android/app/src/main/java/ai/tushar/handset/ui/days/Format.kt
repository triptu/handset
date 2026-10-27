package ai.tushar.handset.ui.days

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Times and dates the way the screens write them: "9:25 am", "7:40 to 9:25 am", "Sun 25 Oct", "Saturday, 24 October" */
class Format(private val zone: ZoneId, private val today: LocalDate, private val use24Hour: Boolean, private val locale: Locale = Locale.getDefault()) {
    private val clock12 = DateTimeFormatter.ofPattern("h:mm", locale)
    private val clock24 = DateTimeFormatter.ofPattern("HH:mm", locale)

    fun time(at: Instant): String {
        val time = at.atZone(zone)
        return if (use24Hour) clock24.format(time) else "${clock12.format(time)} ${half(at)}"
    }

    /** A stay, with am or pm once when both ends share it */
    fun span(start: Instant, end: Instant?): String {
        if (end == null) return "from ${time(start)}"
        if (use24Hour) return "${time(start)} to ${time(end)}"
        val sameHalf = half(start) == half(end)
        return "${if (sameHalf) clock12.format(start.atZone(zone)) else time(start)} to ${time(end)}"
    }

    fun minutes(start: Instant, end: Instant): String {
        val minutes = Duration.between(start, end).toMinutes().coerceAtLeast(1)
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h${if (minutes % 60 == 0L) "" else " ${minutes % 60} min"}"
    }

    /** "Today" or "Sun 25 Oct", for the Days header */
    fun shortDay(date: LocalDate): String = when (date) {
        today -> "Today"
        else -> "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)} ${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.SHORT, locale)}" +
            if (date.year != today.year) " ${date.year}" else ""
    }

    /** "Today", "Yesterday" or "Saturday, 24 October", for headings */
    fun longDay(date: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> "${date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)}, ${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.FULL, locale)}" +
            if (date.year != today.year) " ${date.year}" else ""
    }

    /** The time when [at] is today, else the day, as a row's trailing text */
    fun moment(at: Instant): String = dayOf(at).let { if (it == today) time(at) else shortDay(it) }

    fun dayOf(at: Instant): LocalDate = at.atZone(zone).toLocalDate()

    private fun half(at: Instant) = if (at.atZone(zone).hour < 12) "am" else "pm"
}
