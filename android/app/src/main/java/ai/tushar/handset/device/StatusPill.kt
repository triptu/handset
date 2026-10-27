package ai.tushar.handset.device

import ai.tushar.handset.Handset
import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A small pill at the top of the screen while an agent works: what it is doing, and Stop, which turns Handset off
 * at once. It is an accessibility overlay. While the agent's own touch is on its way, the pill lets touches through, so
 * it never catches one, and screenshots hide it, so the agent never sees it.
 */
class StatusPill(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: LinearLayout? = null
    private var label: TextView? = null
    private val hide = Runnable { remove() }

    /** When the pill's window last changed, so the service can tell its events from the screen's */
    @Volatile var changedAt = 0L
        private set

    // Main thread only: how many of the agent's touches are on their way, and how many screenshots are being taken
    private var passThroughs = 0
    private var captures = 0

    /** Shows [text], or replaces what the pill says */
    fun show(text: String) = main.post {
        main.removeCallbacks(hide)
        changedAt = SystemClock.uptimeMillis()
        val shown = view ?: create() ?: return@post
        label?.text = text
        shown.visibility = if (captures > 0) View.INVISIBLE else View.VISIBLE
    }

    /** Leaves the last words up for a moment after the call ends, so the user can read them */
    fun done() = main.post {
        main.removeCallbacks(hide)
        main.postDelayed(hide, LINGER_MS)
    }

    /** Takes the pill away at once, as when Handset's own screen, which says the same, comes to the front */
    fun hideNow() = main.post {
        main.removeCallbacks(hide)
        remove()
    }

    /**
     * Runs [touch] with the pill letting touches through, so the agent's touch reaches the app under it. A pill created
     * while the touch is on its way starts out letting touches through too, and the pill takes touches again even when
     * the call is cancelled.
     */
    suspend fun <T> passThrough(touch: suspend () -> T): T {
        // Taken without cancellation, so the release below always pairs with it
        val shown = withContext(NonCancellable) {
            onMain {
                passThroughs++
                applyTouchable()
                view != null
            }
        }
        try {
            // The window manager needs a frame to stop sending touches to the pill
            if (shown) delay(FRAME_MS)
            return touch()
        } finally {
            withContext(NonCancellable) {
                onMain {
                    passThroughs--
                    applyTouchable()
                }
            }
        }
    }

    /** Runs [capture] with the pill hidden, so a screenshot never shows it */
    suspend fun <T> hiddenFor(capture: suspend () -> T): T {
        val shown = withContext(NonCancellable) {
            onMain {
                captures++
                changedAt = SystemClock.uptimeMillis()
                view?.visibility = View.INVISIBLE
                view != null
            }
        }
        try {
            if (shown) delay(FRAME_MS)
            return capture()
        } finally {
            withContext(NonCancellable) {
                onMain {
                    captures--
                    changedAt = SystemClock.uptimeMillis()
                    if (captures == 0) view?.visibility = View.VISIBLE
                }
            }
        }
    }

    private suspend fun <T> onMain(block: () -> T): T {
        val result = CompletableDeferred<T>()
        main.post { result.complete(block()) }
        return result.await()
    }

    private fun applyTouchable() {
        val shown = view ?: return
        val params = shown.layoutParams as? WindowManager.LayoutParams ?: return
        changedAt = SystemClock.uptimeMillis()
        params.flags = touchFlags(params.flags)
        runCatching { windowManager.updateViewLayout(shown, params) }
    }

    private fun touchFlags(flags: Int): Int =
        if (passThroughs > 0) flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()

    private fun create(): LinearLayout? {
        val density = service.resources.displayMetrics.density
        fun dp(value: Float) = (value * density).toInt()
        val dot = View(service).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACCENT) }
            layoutParams = LinearLayout.LayoutParams(dp(8f), dp(8f)).apply { marginEnd = dp(8f) }
        }
        val text = TextView(service).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(220f)
        }
        val stop = TextView(service).apply {
            this.text = "Stop"
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12f), dp(4f), dp(4f), dp(4f))
            contentDescription = "Stop agents using this phone"
            setOnClickListener {
                // The same as the switch: the connection closes, and the call in progress ends
                Handset.instance.switchControl(false)
                label?.text = "Stopped. Turn it on again in Handset."
                main.removeCallbacks(hide)
                main.postDelayed(hide, LINGER_MS)
            }
        }
        val pill = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(8f), dp(10f), dp(8f))
            background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(BACKGROUND) }
            elevation = dp(6f).toFloat()
            addView(dot)
            addView(text)
            addView(stop)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            flags = touchFlags(flags)
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(TOP_DP)
        }
        if (runCatching { windowManager.addView(pill, params) }.isFailure) return null
        view = pill
        label = text
        return pill
    }

    private fun remove() {
        changedAt = SystemClock.uptimeMillis()
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        label = null
    }

    private companion object {
        const val LINGER_MS = 1_500L
        const val FRAME_MS = 32L
        const val TOP_DP = 44f
        val BACKGROUND = Color.argb(235, 24, 24, 27)
        val ACCENT = Color.rgb(74, 222, 128)
    }
}
