package ai.tushar.handset.device

import ai.tushar.handset.Handset
import ai.tushar.handset.tools.ToolFailure
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads the notifications on the phone and answers messages through their inline Reply action, so the agent can
 * reply in WhatsApp or another messenger without opening it. The user turns it on in Notification access.
 */
class NotificationWatcher : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        // What arrived while the listener was off; rows already kept stay one row each
        activeNotifications.orEmpty().forEach { Handset.instance.notifications.accept(it, appLabel(it.packageName)) }
    }

    /** Hands each notification to the day record, which keeps it while Notifications is on, and to phone events */
    override fun onNotificationPosted(notification: StatusBarNotification) {
        val app = appLabel(notification.packageName)
        Handset.instance.notifications.accept(notification, app)
        Handset.instance.events.onNotification(notification, app)
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    class Message(
        val key: String,
        val app: String,
        val packageName: String,
        val title: String,
        val text: String,
        val postedAt: Long,
        val canReply: Boolean,
        val ongoing: Boolean,
    )

    /** Every notification but this app's own, ongoing ones such as an order or a ride included */
    fun messages(): List<Message> {
        val all = activeNotifications.orEmpty().filter { it.packageName != packageName }
        // A group's summary only repeats its children, but some apps post the summary alone
        val groupsWithChildren = all.filter { !it.isSummary }.mapNotNull { it.groupKey }.toSet()
        return all
            .filter { !it.isSummary || it.groupKey !in groupsWithChildren }
            .sortedByDescending { it.postTime }
            .map { notification ->
                val extras = notification.notification.extras
                Message(
                    key = notification.key,
                    app = appLabel(notification.packageName),
                    packageName = notification.packageName,
                    title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                    text = body(extras),
                    postedAt = notification.postTime,
                    canReply = replyAction(notification) != null,
                    ongoing = notification.isOngoing,
                )
            }
    }

    private val StatusBarNotification.isSummary: Boolean get() = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

    /** Sends [text] through the notification's Reply action, as typing in the notification shade does */
    fun reply(key: String, text: String): Message {
        val notification = activeNotifications.orEmpty().firstOrNull { it.key == key }
            ?: throw ToolFailure("That notification is gone. Call list_notifications again.")
        val action = replyAction(notification) ?: throw ToolFailure("That notification has no Reply action")
        val results = Bundle()
        val inputs = action.remoteInputs.orEmpty()
        inputs.forEach { results.putCharSequence(it.resultKey, text) }
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        try {
            action.actionIntent.send(this, 0, intent)
        } catch (_: PendingIntent.CanceledException) {
            throw ToolFailure("The app no longer takes replies to this notification. Open the conversation instead.")
        }
        return messages().firstOrNull { it.key == key }
            ?: Message(key, appLabel(notification.packageName), notification.packageName, "", text, notification.postTime, canReply = false, ongoing = false)
    }

    private fun replyAction(notification: StatusBarNotification): Notification.Action? =
        notification.notification.actions.orEmpty().firstOrNull { action ->
            action.remoteInputs.orEmpty().any { it.allowFreeFormInput }
        }

    /** The conversation lines of a messaging notification, or its text */
    private fun body(extras: Bundle): String {
        // The typed overload is API 33; this one works on every version the app runs on
        @Suppress("DEPRECATION")
        val lines = extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.filterIsInstance<Bundle>()
            ?.map { bundle -> "${bundle.getCharSequence("sender") ?: "Me"}: ${bundle.getCharSequence("text") ?: ""}" }
        if (!lines.isNullOrEmpty()) return lines.takeLast(MAX_LINES).joinToString("\n")
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.takeIf { it.isNotBlank() && it !in text }
        return listOfNotNull(text.takeIf { it.isNotBlank() }, sub).joinToString("\n")
    }

    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    companion object {
        @Volatile var instance: NotificationWatcher? = null
            private set

        private const val MAX_LINES = 10

        fun require(): NotificationWatcher =
            instance ?: throw ToolFailure("Notification access is off. Ask the user to allow it for Handset.")
    }
}
