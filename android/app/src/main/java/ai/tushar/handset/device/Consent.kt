package ai.tushar.handset.device

import ai.tushar.handset.R
import ai.tushar.handset.Handset
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asks the user to allow one action with a heads-up notification that has Allow and Deny buttons.
 * The phone asks itself, whichever agent sent the call.
 */
class Consent(private val context: Context) {
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()
    private val nextId = AtomicInteger(100)
    private val manager = context.getSystemService(NotificationManager::class.java)

    enum class Answer { ALLOWED, DENIED, TIMED_OUT, CANNOT_ASK }

    suspend fun ask(
        prompt: String,
        title: String = "Allow the agent to do this?",
        expiresAt: Long = Long.MAX_VALUE,
        whileShowing: suspend (suspend () -> Answer) -> Answer = { it() },
    ): Answer {
        val wait = (minOf(expiresAt, System.currentTimeMillis() + WAIT_MS) - System.currentTimeMillis())
        if (wait <= 0) return Answer.TIMED_OUT
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Permission requests", NotificationManager.IMPORTANCE_HIGH))
        // With Handset's notifications off, the user would never see the question
        val channel = manager.getNotificationChannel(CHANNEL)
        if (!manager.areNotificationsEnabled() || channel?.importance == NotificationManager.IMPORTANCE_NONE) return Answer.CANNOT_ASK
        val result = withTimeoutOrNull(wait) {
            whileShowing {
                val id = nextId.incrementAndGet()
                val answer = CompletableDeferred<Boolean>()
                pending[id] = answer
                try {
                    manager.notify(id, notification(id, prompt, title, wait))
                    if (answer.await()) Answer.ALLOWED else Answer.DENIED
                } finally {
                    pending.remove(id)
                    manager.cancel(id)
                }
            }
        } ?: Answer.TIMED_OUT
        PhoneAccessibilityService.instance?.closeShadeIfOpen()
        return result
    }

    fun answer(id: Int, allowed: Boolean) {
        pending[id]?.complete(allowed)
    }

    private fun notification(id: Int, prompt: String, title: String, wait: Long): Notification {
        fun action(label: String, allowed: Boolean): Notification.Action {
            val intent = Intent(context, ConsentReceiver::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_ALLOWED, allowed)
            val pendingIntent = PendingIntent.getBroadcast(context, id * 2 + if (allowed) 1 else 0, intent, PendingIntent.FLAG_IMMUTABLE)
            return Notification.Action.Builder(null, label, pendingIntent).build()
        }
        val deny = action("Deny", allowed = false)
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_handset)
            .setContentTitle(title)
            .setContentText(prompt.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(prompt))
            .setCategory(Notification.CATEGORY_CALL)
            .setDeleteIntent(deny.actionIntent)
            .addAction(deny)
            .addAction(action("Allow", allowed = true))
            .setTimeoutAfter(wait)
            .setAutoCancel(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "consent"
        const val EXTRA_ID = "id"
        const val EXTRA_ALLOWED = "allowed"
        private const val WAIT_MS = 90_000L
    }
}

class ConsentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Handset.instance.consent.answer(intent.getIntExtra(Consent.EXTRA_ID, -1), intent.getBooleanExtra(Consent.EXTRA_ALLOWED, false))
    }
}
