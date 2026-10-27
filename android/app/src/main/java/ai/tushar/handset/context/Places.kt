package ai.tushar.handset.context

import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Turns location samples into visits: staying within [VISIT_RADIUS_M] of one spot for [MIN_VISIT_MS] or more */
object Places {
    const val VISIT_RADIUS_M = 150.0
    const val MIN_VISIT_MS = 10 * 60_000L

    /** A fix less sure than this can't tell 150 m apart, so it is dropped */
    const val MAX_ACCURACY_M = 200f

    class Sample(val at: Long, val latitude: Double, val longitude: Double, val accuracyM: Float = 0f)

    /**
     * Where the phone has stayed since [start]: the running mean of the samples in range, how many, and the last one's
     * time. [visitId] is set once the stay is long enough to be a visit and has a row.
     */
    @Serializable
    data class Stay(val start: Long, val last: Long, val latitude: Double, val longitude: Double, val samples: Int, val visitId: Long? = null) {
        val isVisit: Boolean get() = last - start >= MIN_VISIT_MS
    }

    /** What a sample changes. [ended] is a stay that became a visit and has now been left. [current] is the stay going on. */
    data class Step(val current: Stay, val ended: Stay?)

    /** Feeds one sample to [stay]. A sample within the radius extends the stay; one outside it starts a new stay. */
    fun step(stay: Stay?, sample: Sample): Step? {
        if (sample.accuracyM > MAX_ACCURACY_M) return null
        if (stay == null) return Step(start(sample), null)
        if (sample.at <= stay.last) return null
        if (distanceM(stay.latitude, stay.longitude, sample.latitude, sample.longitude) <= VISIT_RADIUS_M) {
            val count = stay.samples + 1
            val latitude = stay.latitude + (sample.latitude - stay.latitude) / count
            val longitude = stay.longitude + (sample.longitude - stay.longitude) / count
            return Step(stay.copy(last = sample.at, latitude = latitude, longitude = longitude, samples = count), null)
        }
        return Step(start(sample), stay.takeIf { it.isVisit })
    }

    private fun start(sample: Sample) = Stay(sample.at, sample.at, sample.latitude, sample.longitude, 1)

    /** The visits in a whole sequence of samples, the last one included while it goes on, for tests and for replays */
    fun visits(samples: List<Sample>): List<Stay> {
        val visits = ArrayList<Stay>()
        var stay: Stay? = null
        samples.sortedBy { it.at }.forEach { sample ->
            val step = step(stay, sample) ?: return@forEach
            step.ended?.let { visits.add(it) }
            stay = step.current
        }
        stay?.takeIf { it.isVisit }?.let { visits.add(it) }
        return visits
    }

    /** Distance along the earth's surface, in metres */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private const val EARTH_RADIUS_M = 6_371_000.0
}
