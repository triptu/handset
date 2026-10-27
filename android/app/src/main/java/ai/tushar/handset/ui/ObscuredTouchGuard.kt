package ai.tushar.handset.ui

import android.view.MotionEvent

/**
 * Drops a gesture on a button once any of its touches arrives while another app's window covers this one, fully or in
 * part, since that window could have tricked the tap. Android hides most overlays only from Android 12, so this checks
 * every touch, on every version, before Compose sees it.
 */
class ObscuredTouchGuard(private val onButton: (x: Float, y: Float) -> Boolean) {
    enum class Verdict { PASS, CANCEL, DROP }

    private var onTarget = false
    private var dropping = false

    /** [x] and [y] are where the pointer that went down or up is, in the window. CANCEL ends the gesture once, then DROP. */
    fun check(action: Int, flags: Int, x: Float, y: Float): Verdict {
        if (action == MotionEvent.ACTION_DOWN) {
            onTarget = false
            dropping = false
        }
        if (dropping) return Verdict.DROP
        if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) && onButton(x, y)) onTarget = true
        if (!onTarget || flags and OBSCURED == 0) return Verdict.PASS
        dropping = true
        return Verdict.CANCEL
    }

    private companion object {
        const val OBSCURED = MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
    }
}
