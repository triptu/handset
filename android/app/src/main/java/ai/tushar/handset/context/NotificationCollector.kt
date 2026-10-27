package ai.tushar.handset.context

import android.app.Notification
import android.app.Person
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps notifications as they arrive while Notifications is on. A messaging notification gives a row per message, so a
 * chat's notification that grows with each message adds only the new ones. Any other notification keeps one row, which
 * its updates rewrite. Ongoing ones such as music, downloads and rides, this app's own, and excluded apps are skipped.
 * One-time codes are masked before they are written. A message is told apart by its sender and text, so the same words
 * from the same person in one chat are kept once. A message from while Notifications was off or paused isn't kept, even
 * when its chat's notification is posted again later.
 */
class NotificationCollector(private val context: Context, private val store: ContextStore, private val recording: Recording, private val scope: CoroutineScope) {
    fun accept(notification: StatusBarNotification, app: String) {
        if (!recording.recording(Source.NOTIFICATIONS)) return
        val rows = rows(notification, app, context.packageName) { recording.excluded(Source.NOTIFICATIONS, it) }
        if (rows.isEmpty()) return
        scope.launch { rows.forEach { store.putNotification(it) } }
    }

    companion object {
        private val SKIPPED_CATEGORIES = setOf(
            Notification.CATEGORY_PROGRESS, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_SERVICE, Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_STOPWATCH, Notification.CATEGORY_LOCATION_SHARING, Notification.CATEGORY_SYSTEM,
        )

        /** The rows a notification gives, none when it is skipped. A message or notification from an [excluded] time is left out. */
        fun rows(notification: StatusBarNotification, app: String, ownPackage: String, excluded: (Long) -> Boolean = { false }): List<NotificationRow> {
            val packageName = notification.packageName
            val n = notification.notification
            if (packageName == ownPackage || ExcludedApps.isExcluded(packageName)) return emptyList()
            if (notification.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.category in SKIPPED_CATEGORIES) return emptyList()
            val extras = n.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            val heading = listOfNotNull(title, conversationTitle).joinToString("\n")
            val conversation = "$packageName|" + (n.shortcutId ?: maskCodes(conversationTitle ?: title, heading))
            val now = System.currentTimeMillis()

            @Suppress("DEPRECATION")
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.filterIsInstance<Bundle>().orEmpty()
            if (messages.isNotEmpty()) {
                return messages.mapNotNull { message ->
                    val text = message.getCharSequence("text")?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val about = "$heading\n$text"
                    val sender = maskCodes(message.getCharSequence("sender")?.toString() ?: "Me", about)
                    val at = message.getLong("time").takeIf { it > 0 } ?: notification.postTime
                    if (excluded(at)) return@mapNotNull null
                    NotificationRow(
                        // Not the message's time: some apps stamp every message again when they repost the conversation
                        dedupe = "${notification.key}|$sender|${text.hashCode()}",
                        at = at,
                        updatedAt = now,
                        packageName = packageName,
                        app = app,
                        sender = sender,
                        text = maskCodes(text, about),
                        conversation = conversation,
                        fromPerson = true,
                    )
                }
            }
            val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            if (title.isBlank() && body.isBlank()) return emptyList()
            // An update posts again, so the notification's own time says when it first came
            if (excluded(notification.postTime) || (n.`when` > 0 && excluded(n.`when`))) return emptyList()
            @Suppress("DEPRECATION")
            val people = extras.getParcelableArrayList<Person>(Notification.EXTRA_PEOPLE_LIST).orEmpty()
            return listOf(
                NotificationRow(
                    dedupe = notification.key,
                    at = notification.postTime,
                    updatedAt = now,
                    packageName = packageName,
                    app = app,
                    sender = maskCodes(title.ifBlank { app }, "$heading\n$body"),
                    text = maskCodes(body, "$heading\n$body"),
                    conversation = conversation,
                    // Otherwise the title is the app's own words, often what the notification is about
                    fromPerson = title.isNotBlank() && (n.shortcutId != null || people.isNotEmpty()),
                ),
            )
        }

        private val CODE_WORDS = Regex("(?i)\\b(code|otp|one[- ]time|verification|passcode|pin)\\b")
        private val CODE = Regex("\\b\\d{4,8}\\b")

        /**
         * Masks the 4 to 8 digit numbers in [text] when it, or the rest of its notification in [about], reads as a
         * one-time code, so codes aren't kept: a title that says "Verification code" over a body of digits too.
         */
        fun maskCodes(text: String, about: String = text): String =
            if (CODE_WORDS.containsMatchIn(text) || CODE_WORDS.containsMatchIn(about)) CODE.replace(text, "••••") else text
    }
}
