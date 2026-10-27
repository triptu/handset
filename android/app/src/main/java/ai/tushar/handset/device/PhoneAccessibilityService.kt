package ai.tushar.handset.device

import ai.tushar.handset.Handset
import ai.tushar.handset.tools.ToolFailure
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The hands of the app. The system binds it once the user turns it on in Accessibility settings, and the
 * tools reach it through [instance]. A bound accessibility service also lets the app start activities from
 * the background, which `open_app` and `open_url` need.
 */
class PhoneAccessibilityService : AccessibilityService() {
    @Volatile private var snapshot: ScreenSnapshot? = null

    /** When [snapshot] was read, in uptime milliseconds */
    @Volatile private var snapshotAt = 0L
    private val touches by lazy { TouchIndicator(this) }

    /** What the agent is doing, at the top of the screen, with Stop */
    val pill by lazy { StatusPill(this) }

    /** When any event last arrived, the overlays' own included, after which the cached screen is not trusted */
    @Volatile private var lastEventAt = 0L

    /** When the screen last changed, from the accessibility events, so a tool can wait until it stops changing */
    @Volatile var lastChangeAt: Long = 0L
        private set

    /** When a window last opened, closed or moved, as a page that opens does, after which it can still be sliding into place */
    @Volatile var lastWindowChangeAt: Long = 0L
        private set

    /** A screenshot as JPEG, with its size and the size of the screen it shows */
    class Screenshot(val base64: String, val width: Int, val height: Int, val screenWidth: Int, val screenHeight: Int) {
        val note: String get() = "Screenshot ${width}x$height of the ${screenWidth}x$screenHeight screen. " +
            "Tap with screen pixels: multiply a point in the screenshot by ${"%.3f".format(screenWidth.toFloat() / width)}."
    }

    /** Clears the app list that open_app keeps, when an app is installed or removed */
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = Handset.instance.tools.phone.actions.forgetApps()
    }

    override fun onServiceConnected() {
        instance = this
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(packages, filter)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(packages) }
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        // The touch indicator's and the pill's own windows changing is not the screen changing. Window changes are told
        // apart by the window's id; the pill's own content changes, by package and time.
        val ownOverlay = if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            isOverlayWindow(event.windowId)
        } else {
            event.packageName == packageName && now - maxOf(touches.changedAt, pill.changedAt) < OVERLAY_EVENT_MS
        }
        // Every event makes the cached screen stale; only the screen's own changes hold off settling
        lastEventAt = now
        if (!ownOverlay) {
            lastChangeAt = now
            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) lastWindowChangeAt = now
        }
        Handset.instance.collector.onEvent(this, event)
    }

    /** The ids of accessibility overlay windows seen, kept so a removed one, gone from [windows], is still known */
    private val overlayWindowIds = HashSet<Int>()

    private fun isOverlayWindow(id: Int): Boolean {
        if (id in overlayWindowIds) return true
        val overlay = windows.any { it.id == id && it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        if (overlay) overlayWindowIds.add(id)
        return overlay
    }

    override fun onInterrupt() = Unit

    /**
     * Reads the screen. [keep] makes it the screen the agent's element ids refer to; a read that only looks, as for
     * whether an action changed anything, leaves those ids as they were.
     */
    suspend fun readScreen(keep: Boolean = true): ScreenSnapshot {
        // Taken before the tree is fetched, so a change during the fetch makes this read stale, not current
        val readAt = SystemClock.uptimeMillis()
        // The active window is missing for a moment while windows change, so look again before giving up
        var root = activeRoot(prefetch = true)
        var tries = 0
        while (root == null && tries++ < ROOT_RETRIES) {
            delay(ROOT_RETRY_MS)
            root = activeRoot(prefetch = true)
        }
        if (root == null) throw ToolFailure(noWindowReason())
        requireOtherApp(root)
        val metrics = resources.displayMetrics
        // Between two pages Android lists no app window for a moment, and the window that comes in slides from past the edge.
        // The root and the window list can each report the end of the slide before the other does.
        val screen = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val window = Rect().also { root.getBoundsInScreen(it) }
        val listed = windows.firstOrNull { it.id == root.windowId }?.let { listed -> Rect().also { listed.getBoundsInScreen(it) } }
        // Only for a moment after a window changes: a pop-up window can stay partly off the screen
        val moving = SystemClock.uptimeMillis() - lastWindowChangeAt < WINDOW_SETTLE_MS && (
            windows.none { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } || window.isEmpty || !screen.contains(window) ||
                (listed != null && !screen.contains(listed))
            )
        val read = ScreenReader.read(root, appLabel(root.packageName?.toString().orEmpty()), metrics.widthPixels, metrics.heightPixels, moving)
        if (keep) {
            snapshot = read
            snapshotAt = readAt
        }
        return read
    }

    /** Where [text] shows in the window in front, hidden layers included: see [ScreenReader.marks] */
    fun marks(text: String): Set<String> = ScreenReader.marks(activeRoot(prefetch = true)?.also(::requireOtherApp) ?: throw ToolFailure(noWindowReason()), text)

    /** The window in front as it is now, every node of it, for a test of the screen reader */
    suspend fun record(): RecordedScreen {
        val root = activeRoot(prefetch = true) ?: throw ToolFailure(noWindowReason())
        requireOtherApp(root)
        val metrics = resources.displayMetrics
        return RecordedScreen(appLabel(root.packageName?.toString().orEmpty()), metrics.widthPixels, metrics.heightPixels, RecordedNode.record(AndroidNode(root)))
    }

    /** The screen's text as it is now, read without changing what the agent's element ids refer to */
    suspend fun peekText(): String = peekScreen().text

    /**
     * The screen as it is now, read without replacing the screen the agent's ids and points belong to, so a check that
     * looks, as for approval, can't hide that another app has come to the front
     */
    suspend fun peekScreen(): ScreenSnapshot {
        val last = snapshot
        return if (last != null && snapshotAt > lastEventAt) last else readScreen(keep = false)
    }

    /** The last screen read when nothing has changed since, which saves a read of the whole tree, or a new read */
    suspend fun currentScreen(): ScreenSnapshot {
        val last = snapshot
        return if (last != null && snapshotAt > lastEventAt) last else readScreen()
    }

    /** The element [query] names on the screen as it is now, by its label, hint or view id, with its id */
    suspend fun find(query: String, avoidFields: Boolean = false): Pair<Int, AccessibilityNodeInfo> {
        val screen = currentScreen()
        val id = screen.find(query, avoidFields) ?: throw ToolFailure("Nothing on screen matches \"$query\". Call get_screen to see what is there.")
        return id to element(id)
    }

    /** The package of the app in front, or null when no window can be read */
    fun currentPackage(): String? = activeRoot()?.packageName?.toString()

    /** The package of the last screen the agent read, which its element ids belong to */
    val snapshotPackage: String? get() = snapshot?.packageName

    fun currentAppLabel(): String? = currentPackage()?.let { appLabel(it) }

    /**
     * The root of the window in front. With [prefetch], Android 13 and later send the whole tree in one go, where each
     * child is otherwise its own call to the app that draws it.
     */
    private fun activeRoot(prefetch: Boolean = false): AccessibilityNodeInfo? {
        val strategy = if (prefetch && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID else 0
        fun AccessibilityWindowInfo.rootNode() = if (strategy != 0) getRoot(strategy) else root
        return (if (strategy != 0) getRootInActiveWindow(strategy) else rootInActiveWindow)
            ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isFocused || it.isActive) }?.rootNode()
            ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.rootNode()
    }

    /**
     * Handset's own screens hold the agents' tokens and the switches that say what each may read, so no agent, over any
     * transport, may read or act on them. Its overlays, the pill and the touch ring, are not application windows.
     */
    private fun requireOtherApp(root: AccessibilityNodeInfo? = null) {
        val own = root?.packageName == packageName ||
            windows.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName == packageName }
        if (own) throw ToolFailure(OWN_SCREENS_PRIVATE)
    }

    private fun noWindowReason(): String {
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        return when {
            !power.isInteractive -> "The screen is off. Ask the user to turn it on."
            keyguard.isKeyguardLocked -> "The phone is locked. Ask the user to unlock it."
            // Android hides its permission prompts from accessibility services, so an app can't grant itself access
            else -> "The screen could not be read. A system prompt, such as a permission request, may be open; only the user " +
                "can answer it. Try again, or take a screenshot."
        }
    }

    fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /** The element with this id in the last screen read, refreshed, or a failure that tells the agent to read again */
    fun element(id: Int): AccessibilityNodeInfo {
        requireOtherApp()
        val node = snapshot?.element(id) ?: throw ToolFailure("No element [$id]. Call get_screen and use an id from it.")
        if (!node.refresh() || !node.isVisibleToUser) throw ToolFailure("Element [$id] is no longer on screen. Call get_screen again.")
        return node
    }

    /** A screenshot without the touch indicator in it */
    suspend fun screenshot(maxSide: Int = SCREENSHOT_MAX_SIDE): Screenshot {
        requireOtherApp()
        touches.hideNow()
        return pill.hiddenFor { capture(maxSide) }
    }

    private suspend fun capture(maxSide: Int): Screenshot = suspendCancellableCoroutine { continuation ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    result.hardwareBuffer.close()
                    if (hardware == null) {
                        continuation.resumeWithException(ToolFailure("The screenshot could not be read"))
                        return
                    }
                    val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                    val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
                    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
                    val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
                    continuation.resume(Screenshot(Base64.encodeToString(bytes, Base64.NO_WRAP), scaled.width, scaled.height, bitmap.width, bitmap.height))
                }

                override fun onFailure(errorCode: Int) {
                    val reason = if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) "the app blocks screenshots" else "error $errorCode"
                    continuation.resumeWithException(ToolFailure("No screenshot: $reason"))
                }
            },
        )
    }

    suspend fun tap(node: AccessibilityNodeInfo, long: Boolean) {
        requireOtherApp()
        val center = Rect().also { node.getBoundsInScreen(it) }
        touches.tap(center.exactCenterX(), center.exactCenterY(), long)
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            val can = if (long) current.isLongClickable else current.isClickable
            if (can && current.performAction(action)) return
            current = current.parent
        }
        // Nothing in the tree takes the click, as in a canvas or a web page: tap where the element is drawn
        touchAt(center.exactCenterX(), center.exactCenterY(), long)
    }

    suspend fun tapAt(x: Float, y: Float, long: Boolean) {
        requireOtherApp()
        touches.tap(x, y, long)
        touchAt(x, y, long)
    }

    private suspend fun touchAt(x: Float, y: Float, long: Boolean) {
        val path = Path().apply { moveTo(x, y) }
        gesture(path, if (long) LONG_PRESS_MS else TAP_MS)
    }

    suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long) {
        requireOtherApp()
        touches.swipe(fromX, fromY, toX, toY, durationMs)
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        gesture(path, durationMs)
    }

    fun setText(node: AccessibilityNodeInfo, text: String) {
        requireOtherApp()
        if (!node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) throw ToolFailure("The field did not take the text")
    }

    /**
     * Moves a slider to [value], in the units of its range. A slider that does not take the value, as Samsung's do,
     * is touched at the point along it that matches the value.
     */
    suspend fun setProgress(node: AccessibilityNodeInfo, value: Float) {
        requireOtherApp()
        // The action reports failure when the slider is already at the value
        if (node.rangeInfo?.current == value) return
        val arguments = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }
        if (node.performAction(AccessibilityAction.ACTION_SET_PROGRESS.id, arguments)) return
        val range = node.rangeInfo ?: throw ToolFailure("The element did not take the value")
        val fraction = if (range.max > range.min) (value - range.min) / (range.max - range.min) else 0f
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.width() >= bounds.height()) {
            tapAt(bounds.left + bounds.width() * fraction, bounds.exactCenterY(), long = false)
        } else {
            tapAt(bounds.exactCenterX(), bounds.bottom - bounds.height() * fraction, long = false)
        }
    }

    fun pressEnter(node: AccessibilityNodeInfo): Boolean {
        requireOtherApp()
        return node.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun focusedInput(): AccessibilityNodeInfo? {
        requireOtherApp()
        return activeRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    fun scroll(node: AccessibilityNodeInfo, direction: String): Boolean {
        requireOtherApp()
        val directional = when (direction) {
            "up" -> AccessibilityAction.ACTION_SCROLL_UP
            "down" -> AccessibilityAction.ACTION_SCROLL_DOWN
            "left" -> AccessibilityAction.ACTION_SCROLL_LEFT
            else -> AccessibilityAction.ACTION_SCROLL_RIGHT
        }
        if (node.performAction(directional.id)) return true
        val fallback = if (direction == "up" || direction == "left") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        return node.performAction(fallback)
    }

    fun globalAction(name: String) {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> throw ToolFailure("Unknown key $name")
        }
        if (!performGlobalAction(action)) throw ToolFailure("The phone did not do $name")
    }

    /**
     * Closes the notification shade or Quick Settings, and waits for them to go. Android reports the dismiss as done even
     * with the shade closed, so it only runs when System UI is in front.
     */
    suspend fun closeShadeIfOpen() {
        if (currentPackage() != SYSTEM_UI || !performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)) return
        val deadline = SystemClock.uptimeMillis() + SHADE_CLOSE_MS
        while (currentPackage() == SYSTEM_UI && SystemClock.uptimeMillis() < deadline) delay(SHADE_POLL_MS)
    }

    /** Starts an activity over whatever is in front. The notification shade and Quick Settings close first, or they would stay on top. */
    suspend fun launch(intent: Intent) {
        val target = intent.`package` ?: intent.component?.packageName
            ?: packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if (target == packageName) throw ToolFailure(OWN_SCREENS_PRIVATE)
        closeShadeIfOpen()
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            throw ToolFailure("No app on the phone opens this")
        }
    }

    /** The apps with a launcher icon, as label and package */
    fun launchableApps(): List<Pair<String, String>> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // The ResolveInfoFlags overload is API 33; this one works on every version the app runs on
        @Suppress("DEPRECATION")
        return packageManager.queryIntentActivities(query, 0)
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }

    private suspend fun gesture(path: Path, durationMs: Long) = pill.passThrough { dispatch(path, durationMs) }

    private suspend fun dispatch(path: Path, durationMs: Long) {
        val description = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val done = suspendCancellableCoroutine { continuation ->
            val dispatched = dispatchGesture(
                description,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) = continuation.resume(true)
                    override fun onCancelled(gestureDescription: GestureDescription?) = continuation.resume(false)
                },
                null,
            )
            if (!dispatched) continuation.resume(false)
        }
        if (!done) throw ToolFailure("The gesture was cancelled")
    }

    companion object {
        @Volatile var instance: PhoneAccessibilityService? = null
            private set

        fun require(): PhoneAccessibilityService =
            instance ?: throw ToolFailure("Phone control is off. Ask the user to turn on Handset in Settings, Accessibility.")

        const val OWN_SCREENS_PRIVATE = "Handset's own screens are private to the user."
        private const val ROOT_RETRIES = 4
        private const val ROOT_RETRY_MS = 250L
        private const val SHADE_CLOSE_MS = 400L
        private const val SHADE_POLL_MS = 16L
        private const val OVERLAY_EVENT_MS = 150L

        /** How long after a window change a window past the screen's edge, or an empty one, counts as still moving */
        const val WINDOW_SETTLE_MS = 1_000L
        private const val SYSTEM_UI = "com.android.systemui"
        private const val TAP_MS = 60L
        private const val LONG_PRESS_MS = 700L
        private const val SCREENSHOT_MAX_SIDE = 1280
        private const val JPEG_QUALITY = 70
    }
}
