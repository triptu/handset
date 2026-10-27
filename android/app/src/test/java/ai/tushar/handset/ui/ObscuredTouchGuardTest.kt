package ai.tushar.handset.ui

import ai.tushar.handset.ui.ObscuredTouchGuard.Verdict
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_UP
import android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED
import android.view.MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
import org.junit.Assert.assertEquals
import org.junit.Test

class ObscuredTouchGuardTest {
    /** Allow is the strip below y = 100 */
    private val guard = ObscuredTouchGuard { _, y -> y > 100 }

    @Test fun aClearTapOnAllowPasses() {
        assertEquals(Verdict.PASS, guard.check(ACTION_DOWN, 0, 10f, 150f))
        assertEquals(Verdict.PASS, guard.check(ACTION_UP, 0, 10f, 150f))
    }

    @Test fun aTapOnAllowUnderAnotherWindowIsCancelledThenDropped() {
        for (flag in listOf(FLAG_WINDOW_IS_OBSCURED, FLAG_WINDOW_IS_PARTIALLY_OBSCURED)) {
            assertEquals(Verdict.CANCEL, guard.check(ACTION_DOWN, flag, 10f, 150f))
            assertEquals(Verdict.DROP, guard.check(ACTION_UP, 0, 10f, 150f))
            // The next gesture starts afresh
            assertEquals(Verdict.PASS, guard.check(ACTION_DOWN, 0, 10f, 150f))
            assertEquals(Verdict.PASS, guard.check(ACTION_UP, 0, 10f, 150f))
        }
    }

    @Test fun aWindowThatAppearsMidTapStillStopsIt() {
        assertEquals(Verdict.PASS, guard.check(ACTION_DOWN, 0, 10f, 150f))
        assertEquals(Verdict.CANCEL, guard.check(ACTION_MOVE, FLAG_WINDOW_IS_PARTIALLY_OBSCURED, 10f, 150f))
        assertEquals(Verdict.DROP, guard.check(ACTION_UP, 0, 10f, 150f))
    }

    @Test fun aSecondFingerOnAllowCounts() {
        assertEquals(Verdict.PASS, guard.check(ACTION_DOWN, 0, 10f, 50f))
        assertEquals(Verdict.CANCEL, guard.check(ACTION_POINTER_DOWN, FLAG_WINDOW_IS_OBSCURED, 10f, 150f))
    }

    @Test fun theRestOfTheScreenStillScrollsUnderAnotherWindow() {
        assertEquals(Verdict.PASS, guard.check(ACTION_DOWN, FLAG_WINDOW_IS_OBSCURED, 10f, 50f))
        assertEquals(Verdict.PASS, guard.check(ACTION_MOVE, FLAG_WINDOW_IS_OBSCURED, 10f, 50f))
        assertEquals(Verdict.PASS, guard.check(ACTION_UP, FLAG_WINDOW_IS_OBSCURED, 10f, 50f))
    }
}
