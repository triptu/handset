package ai.tushar.handset.ui.days

import java.time.Instant
import java.time.ZoneId

enum class PartOfDay(val label: String) { MORNING("Morning"), AFTERNOON("Afternoon"), EVENING("Evening") }

/** A heading in a day and the moments under it */
sealed interface DaySection {
    val moments: List<Moment>

    data class Place(val visit: Visit, override val moments: List<Moment>) : DaySection

    /** Moments before the first place, under the part of the day */
    data class Loose(val label: String, override val moments: List<Moment>) : DaySection
}

/**
 * Splits a day into headed sections in time order. A moment goes under the last place it was at or had left, so the
 * moments on the way from one place to the next sit under the place left. Moments before the first place go under
 * Morning, Afternoon or Evening. A place with nothing in it still shows, since being there is part of the day.
 */
fun arrange(timeline: Timeline, zone: ZoneId): List<DaySection> {
    val visits = timeline.visits.sortedBy { it.start }
    val placed = visits.associateWith { ArrayList<Moment>() }
    val loose = ArrayList<DaySection.Loose>()
    for (moment in timeline.moments.sortedBy { it.at }) {
        val visit = visits.lastOrNull { !moment.at.isBefore(it.start) }
        if (visit != null) {
            placed.getValue(visit) += moment
            continue
        }
        val label = partOf(moment.at, zone).label
        val last = loose.lastOrNull()
        if (last != null && last.label == label) loose[loose.lastIndex] = last.copy(moments = last.moments + moment) else loose += DaySection.Loose(label, listOf(moment))
    }
    return loose + visits.map { DaySection.Place(it, placed.getValue(it)) }
}

fun partOf(at: Instant, zone: ZoneId): PartOfDay {
    val hour = at.atZone(zone).hour
    return when {
        hour < 12 -> PartOfDay.MORNING
        hour < 17 -> PartOfDay.AFTERNOON
        else -> PartOfDay.EVENING
    }
}
