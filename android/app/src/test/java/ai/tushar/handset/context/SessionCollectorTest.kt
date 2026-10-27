package ai.tushar.handset.context

import ai.tushar.handset.context.SessionCollector.Event
import android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED
import android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
import android.app.usage.UsageEvents.Event.SCREEN_NON_INTERACTIVE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Usage events into app sessions */
class SessionCollectorTest {
    private fun s(seconds: Int) = seconds * 1_000L

    @Test
    fun anAppsScreensMakeOneSessionAndTheLauncherNone() {
        val events = listOf(
            Event(ACTIVITY_RESUMED, "launcher", "Home", s(0)),
            Event(ACTIVITY_PAUSED, "launcher", "Home", s(1)),
            Event(ACTIVITY_RESUMED, "chrome", "Main", s(1)),
            // The next screen resumes before the last one pauses
            Event(ACTIVITY_RESUMED, "chrome", "Settings", s(60)),
            Event(ACTIVITY_PAUSED, "chrome", "Main", s(61)),
            Event(ACTIVITY_PAUSED, "chrome", "Settings", s(120)),
            // Back within 10 s, as after a share sheet, is the same session
            Event(ACTIVITY_RESUMED, "chrome", "Main", s(125)),
            Event(ACTIVITY_PAUSED, "chrome", "Main", s(200)),
            Event(ACTIVITY_RESUMED, "maps", "Main", s(200)),
            Event(SCREEN_NON_INTERACTIVE, "android", null, s(500)),
        )
        val result = SessionCollector.sessionsFrom(events, now = s(1000), ignored = setOf("launcher"))
        assertEquals(listOf(Triple("chrome", s(1), s(200)), Triple("maps", s(200), s(500))), result.sessions.map { Triple(it.packageName, it.start, it.end) })
        assertNull(result.openSince)
    }

    @Test
    fun aSessionStillOpenEndsNowAndTheNextReadStartsAtIt() {
        val events = listOf(Event(ACTIVITY_RESUMED, "chrome", "Main", s(10)))
        val result = SessionCollector.sessionsFrom(events, now = s(100), ignored = emptySet())
        assertEquals(s(100), result.sessions.single().end)
        assertEquals(s(10), result.openSince)
    }

    @Test
    fun aGlimpseIsDropped() {
        val events = listOf(Event(ACTIVITY_RESUMED, "chrome", "Main", s(10)), Event(ACTIVITY_PAUSED, "chrome", "Main", s(12)))
        assertEquals(0, SessionCollector.sessionsFrom(events, now = s(100), ignored = emptySet()).sessions.size)
    }

    @Test
    fun nothingFromAPauseOrBeforeTurningOnIsRead() {
        val events = listOf(
            Event(ACTIVITY_RESUMED, "maps", "Main", s(5)),
            Event(ACTIVITY_PAUSED, "maps", "Main", s(15)),
            Event(ACTIVITY_RESUMED, "chrome", "Main", s(20)),
            // Paused from 100 to 200 s: Chrome ends at the pause, and the bank app opened during it isn't read
            Event(ACTIVITY_PAUSED, "chrome", "Main", s(120)),
            Event(ACTIVITY_RESUMED, "bank", "Main", s(130)),
            Event(ACTIVITY_PAUSED, "bank", "Main", s(190)),
            Event(ACTIVITY_RESUMED, "clock", "Main", s(210)),
            Event(ACTIVITY_PAUSED, "clock", "Main", s(260)),
        )
        val recorded = SessionCollector.recorded(events, onSince = s(18), pauses = listOf(s(100) to s(200)))
        val result = SessionCollector.sessionsFrom(recorded, now = s(300), ignored = emptySet())
        assertEquals(listOf(Triple("chrome", s(20), s(100)), Triple("clock", s(210), s(260))), result.sessions.map { Triple(it.packageName, it.start, it.end) })
    }
}
