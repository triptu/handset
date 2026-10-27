package ai.tushar.handset.context

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which times count as not recorded: before a source was turned on, during a pause, and while stopped */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RecordingTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private var now = 10 * HOUR
    private val recording = Recording(context, context.getSharedPreferences("recording-test", Context.MODE_PRIVATE)) { now }

    @Test
    fun aSourceStartsFromWhenItWasTurnedOn() {
        recording.setOn(Source.APP_USE, true)
        assertTrue(recording.excluded(Source.APP_USE, now - 1))
        assertFalse(recording.excluded(Source.APP_USE, now))
        // Turning it on again while on keeps the first time
        now += HOUR
        recording.setOn(Source.APP_USE, true)
        assertFalse(recording.excluded(Source.APP_USE, now - 1))
    }

    @Test
    fun aPauseIsKeptAsTheSpanItLasted() {
        val start = now
        recording.pauseForAnHour()
        now += 20 * 60_000L
        recording.resume()
        assertFalse(recording.paused)
        assertEquals(listOf(start to now), recording.pauses())
        assertTrue(recording.excluded(Source.NOTIFICATIONS, start + 1))
        assertFalse(recording.excluded(Source.NOTIFICATIONS, now))
        assertFalse(recording.excluded(Source.NOTIFICATIONS, start - 1))
    }

    @Test
    fun stoppingLastsUntilResumedAndKeepsEachSwitch() {
        recording.setOn(Source.SCREEN, true)
        val start = now
        recording.stop()
        now += 30 * 24 * HOUR
        assertTrue(recording.stopped)
        assertFalse(recording.recording(Source.SCREEN))
        recording.resume()
        assertFalse(recording.stopped)
        assertTrue(recording.isOn(Source.SCREEN))
        assertTrue(recording.excluded(Source.NOTIFICATIONS, now - 1))
        assertFalse(recording.excluded(Source.NOTIFICATIONS, now))
        assertEquals(listOf(start to now), recording.pauses())
    }

    private companion object {
        const val HOUR = 60 * 60_000L
    }
}
