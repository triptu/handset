package ai.tushar.handset.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Location samples into visits: within 150 m for 10 minutes or more */
class PlacesTest {
    private val home = 12.9716 to 77.5946
    private val office = 12.9352 to 77.6245

    private fun at(minute: Int, place: Pair<Double, Double>, north: Double = 0.0, accuracy: Float = 20f) =
        Places.Sample(minute * 60_000L, place.first + north / 111_000.0, place.second, accuracy)

    @Test
    fun aSequenceBecomesTheRightVisits() {
        val samples = listOf(
            // Home for 30 minutes, drifting by up to 80 m
            at(0, home), at(5, home, 40.0), at(10, home, -30.0), at(20, home, 80.0), at(30, home),
            // Driving: points far apart, none a visit
            at(35, 12.9600 to 77.6000), at(40, 12.9500 to 77.6100), at(45, 12.9400 to 77.6200),
            // The office for 15 minutes
            at(50, office), at(55, office, 50.0), at(65, office, -20.0),
            // A stop shorter than 10 minutes
            at(70, 12.9000 to 77.6500), at(75, 12.9000 to 77.6500),
            at(80, 12.8800 to 77.6600),
        )
        val visits = Places.visits(samples)
        assertEquals(2, visits.size)
        assertEquals(0L to 30 * 60_000L, visits[0].start to visits[0].last)
        assertEquals(50 * 60_000L to 65 * 60_000L, visits[1].start to visits[1].last)
        assertTrue(Places.distanceM(visits[0].latitude, visits[0].longitude, home.first, home.second) < 50)
        assertTrue(Places.distanceM(visits[1].latitude, visits[1].longitude, office.first, office.second) < 50)
    }

    @Test
    fun aStayStillGoingOnCountsOnceLongEnough() {
        assertEquals(0, Places.visits(listOf(at(0, home), at(9, home))).size)
        assertEquals(1, Places.visits(listOf(at(0, home), at(10, home))).size)
    }

    @Test
    fun aVagueFixAndARepeatedFixAreIgnored() {
        // A fix 2 km off but only good to 900 m doesn't end the visit
        val visits = Places.visits(listOf(at(0, home), at(6, home, 2_000.0, accuracy = 900f), at(6, home), at(12, home)))
        assertEquals(1, visits.size)
        assertEquals(12 * 60_000L, visits.single().last)
    }

    @Test
    fun movingMoreThan150MetresStartsANewStay() {
        val visits = Places.visits(listOf(at(0, home), at(12, home), at(14, home, 200.0), at(30, home, 200.0)))
        assertEquals(2, visits.size)
    }
}
