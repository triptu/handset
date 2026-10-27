package ai.tushar.handset.device

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * Shows the user where the agent touches the screen: a ring that grows and fades at a tap, and a dot that moves along a
 * swipe. It draws in an accessibility overlay, which takes no touches and needs no permission of its own. A screenshot
 * waits until the drawing is gone, so the agent never sees it.
 */
class TouchIndicator(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** When the last drawing ends, in uptime milliseconds */
    @Volatile private var busyUntil = 0L

    /** When a drawing's window was last added or removed, so the service can tell its events from the screen's */
    @Volatile var changedAt = 0L
        private set

    fun tap(x: Float, y: Float, long: Boolean) {
        val duration = if (long) LONG_PRESS_MS else TAP_MS
        show(duration) { canvas, progress, view -> drawRing(canvas, view, x, y, progress) }
    }

    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long) {
        show(durationMs + FADE_MS) { canvas, progress, view ->
            val total = durationMs + FADE_MS
            val moved = (progress * total / durationMs).coerceAtMost(1f)
            val fade = if (progress * total > durationMs) 1f - (progress * total - durationMs) / FADE_MS else 1f
            drawSwipe(canvas, view, fromX, fromY, fromX + (toX - fromX) * moved, fromY + (toY - fromY) * moved, fade)
        }
    }

    /** Removes whatever is drawn, so a screenshot shows the screen as it is. Returns after the next frame. */
    suspend fun hideNow() {
        if (busyUntil <= SystemClock.uptimeMillis()) return
        val removed = CompletableDeferred<Unit>()
        main.post {
            showing.toList().forEach { remove(it) }
            removed.complete(Unit)
        }
        removed.await()
        delay(FRAME_MS)
    }

    private val showing = ArrayList<View>()

    private fun remove(view: View) {
        if (!showing.remove(view)) return
        changedAt = SystemClock.uptimeMillis()
        runCatching { windowManager.removeView(view) }
    }

    /**
     * Draws for [durationMs] from the clock, frame by frame. A ValueAnimator would end at once while battery saver is
     * on, because it turns animators off.
     */
    private fun show(durationMs: Long, draw: (Canvas, Float, View) -> Unit) {
        busyUntil = maxOf(busyUntil, SystemClock.uptimeMillis() + durationMs)
        main.post {
            val view = Drawing(service, SystemClock.uptimeMillis(), durationMs, draw)
            changedAt = SystemClock.uptimeMillis()
            if (runCatching { windowManager.addView(view, layoutParams()) }.isFailure) return@post
            showing.add(view)
            main.postDelayed({ remove(view) }, durationMs)
        }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE }
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    /** A light disc with a dark edge, so it shows on a light screen and a dark one */
    private fun drawRing(canvas: Canvas, view: View, x: Float, y: Float, progress: Float) {
        val (left, top) = origin(view)
        val density = view.resources.displayMetrics.density
        val radius = (START_RADIUS_DP + (END_RADIUS_DP - START_RADIUS_DP) * progress) * density
        val alpha = 1f - progress
        fill.alpha = (FILL_ALPHA * alpha).toInt()
        ring.alpha = (RING_ALPHA * alpha).toInt()
        ring.strokeWidth = STROKE_DP * density
        canvas.drawCircle(x - left, y - top, radius, fill)
        canvas.drawCircle(x - left, y - top, radius, ring)
    }

    private fun drawSwipe(canvas: Canvas, view: View, fromX: Float, fromY: Float, x: Float, y: Float, fade: Float) {
        val (left, top) = origin(view)
        val density = view.resources.displayMetrics.density
        trail.alpha = (FILL_ALPHA * fade).toInt()
        trail.strokeWidth = TRAIL_DP * density
        canvas.drawLine(fromX - left, fromY - top, x - left, y - top, trail)
        fill.alpha = (FILL_ALPHA * fade).toInt()
        ring.alpha = (RING_ALPHA * fade).toInt()
        ring.strokeWidth = STROKE_DP * density
        canvas.drawCircle(x - left, y - top, START_RADIUS_DP * density, fill)
        canvas.drawCircle(x - left, y - top, START_RADIUS_DP * density, ring)
    }

    /** Where the overlay starts on screen, because the agent's points are in screen pixels */
    private fun origin(view: View): Pair<Int, Int> {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return location[0] to location[1]
    }

    private class Drawing(context: Context, private val startedAt: Long, private val durationMs: Long, private val draw: (Canvas, Float, View) -> Unit) :
        View(context) {
        private val easing = DecelerateInterpolator()

        override fun onDraw(canvas: Canvas) {
            val elapsed = (SystemClock.uptimeMillis() - startedAt).toFloat() / durationMs
            draw(canvas, easing.getInterpolation(elapsed.coerceIn(0f, 1f)), this)
            if (elapsed < 1f) postInvalidateOnAnimation()
        }
    }

    private companion object {
        const val TAP_MS = 300L
        const val LONG_PRESS_MS = 700L
        const val FADE_MS = 150L
        const val FRAME_MS = 32L
        const val START_RADIUS_DP = 14f
        const val END_RADIUS_DP = 34f
        const val STROKE_DP = 2f
        const val TRAIL_DP = 6f
        const val FILL_ALPHA = 140
        const val RING_ALPHA = 110
    }
}
