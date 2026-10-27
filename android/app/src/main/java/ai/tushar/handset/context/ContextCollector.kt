package ai.tushar.handset.context

import ai.tushar.handset.Handset
import ai.tushar.handset.device.PhoneAccessibilityService
import ai.tushar.handset.device.ScreenReader
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Reads the text on screen while Screen text is on and recording isn't paused, and hands each frame to [sink]. A window
 * is read once it has been still for a moment; the same text is not handed on twice in a row, but coming back to an
 * app reads it again, so each visit to an app has its frame; this app,
 * keyboards, excluded apps and password fields are never read. Nothing leaves the phone.
 */
class ContextCollector(private val handset: Handset, private val sink: ScreenTextSink) {
    private var lastFrame: Pair<String, Int>? = null
    private var pendingRead: Job? = null

    // Apart from "Let agents use this phone": Stop ends what agents do, not what the phone remembers
    private val collecting: Boolean get() = handset.recording.recording(Source.SCREEN)

    /** Drops the read that waits for a window to settle, when recording is paused or turned off */
    @Synchronized
    fun stop() {
        pendingRead?.cancel()
    }

    fun onEvent(service: PhoneAccessibilityService, event: AccessibilityEvent) {
        if (!collecting) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName == service.packageName || ExcludedApps.isExcluded(packageName)) {
            // The launcher or another skipped window came to the front, so the app after it is read again
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) synchronized(this) { lastFrame = null }
            return
        }
        // Read after the window settles, not on every change while it draws
        synchronized(this) {
            pendingRead?.cancel()
            pendingRead = handset.scope.launch {
                delay(SETTLE_MS)
                read(service)
            }
        }
    }

    private fun read(service: PhoneAccessibilityService) {
        val root = service.rootInActiveWindow ?: return
        val packageName = root.packageName?.toString() ?: return
        if (packageName == service.packageName || ExcludedApps.isExcluded(packageName) || isKeyboard(service, packageName)) return
        val text = ScreenReader.visibleText(root).take(MAX_CHARS)
        if (text.length < MIN_CHARS) return
        val frame = synchronized(this) {
            // Turned off while the window settled
            if (!collecting) return
            if (lastFrame == packageName to text.hashCode()) return
            lastFrame = packageName to text.hashCode()
            ScreenFrame(packageName, service.appLabel(packageName), text, Instant.now())
        }
        sink.accept(frame)
    }

    /** Any keyboard the user has turned on, beyond the ones [ExcludedApps] names */
    private fun isKeyboard(service: PhoneAccessibilityService, packageName: String): Boolean =
        service.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList.orEmpty().any { it.packageName == packageName }

    private companion object {
        const val SETTLE_MS = 2_500L
        const val MIN_CHARS = 20
        const val MAX_CHARS = 10_000
    }
}
