package ai.tushar.handset.context

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant

/** The text of one settled window: the app it belongs to, what it said, and when */
class ScreenFrame(val packageName: String, val app: String, val text: String, val at: Instant)

/** Where [ContextCollector] hands each frame */
fun interface ScreenTextSink {
    fun accept(frame: ScreenFrame)
}

/** Writes each frame to the [ContextStore], in the app's private storage. Nothing is sent anywhere. */
class StoredScreenText(private val store: ContextStore, private val scope: CoroutineScope) : ScreenTextSink {
    override fun accept(frame: ScreenFrame) {
        scope.launch {
            store.addFrame(FrameRow(at = frame.at.toEpochMilli(), packageName = frame.packageName, app = frame.app, text = frame.text))
        }
    }
}
