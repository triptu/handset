package ai.tushar.handset.context

import android.app.Application
import android.app.Notification
import android.app.Person
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Notifications into rows: a message per row, updates rewriting their row, and what is skipped */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationCollectorTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun posted(notification: Notification, tag: String = "chat", packageName: String = "com.whatsapp") =
        @Suppress("DEPRECATION")
        StatusBarNotification(packageName, packageName, 1, tag, 10_001, 0, 0, notification, Process.myUserHandle(), 1_000L)

    private fun chat(vararg messages: Pair<String, Long>): Notification {
        val style = Notification.MessagingStyle(Person.Builder().setName("Me").build()).setConversationTitle("Dinner")
        messages.forEach { (text, at) -> style.addMessage(text.substringAfter(": "), at, Person.Builder().setName(text.substringBefore(": ")).build()) }
        return Notification.Builder(context, "c").setSmallIcon(android.R.drawable.ic_dialog_info).setStyle(style).build()
    }

    private fun plain(title: String, text: String, ongoing: Boolean = false) = Notification.Builder(context, "c")
        .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text).setOngoing(ongoing).build()

    @Test
    fun aRepostedChatAddsOnlyItsNewMessage() = runTest {
        val store = ContextStore(ContextDatabase.inMemory(context))
        val first = NotificationCollector.rows(posted(chat("Asha: Coming?" to 10L, "Ravi: Try Toit" to 20L)), "WhatsApp", context.packageName)
        // Reposted with every message stamped again, and one more
        val second = NotificationCollector.rows(posted(chat("Asha: Coming?" to 30L, "Ravi: Try Toit" to 31L, "Asha: Booked for 8" to 32L)), "WhatsApp", context.packageName)
        (first + second).forEach { store.putNotification(it) }
        assertEquals(3L, store.counts()["notifications"])
        assertEquals(listOf("Asha", "Ravi"), first.map { it.sender })
        assertTrue(first.all { it.conversation == first.first().conversation })
    }

    @Test
    fun anUpdateRewritesItsRow() = runTest {
        val store = ContextStore(ContextDatabase.inMemory(context))
        listOf("Arrives today", "Arrives today", "Out for delivery").forEach { text ->
            NotificationCollector.rows(posted(plain("Courier", text), tag = "parcel"), "Courier", context.packageName).forEach { store.putNotification(it) }
        }
        assertEquals(1L, store.counts()["notifications"])
        assertEquals("Out for delivery", store.inboxSince(0).single().messages.single().text)
    }

    @Test
    fun onlyAChatOrAPersonMarksTheSenderAsAPerson() {
        assertTrue(NotificationCollector.rows(posted(chat("Asha: Coming?" to 10L)), "WhatsApp", context.packageName).single().fromPerson)
        val order = NotificationCollector.rows(posted(plain("Order 5521 is on its way", "2 minutes away"), packageName = "in.swiggy.android"), "Swiggy", context.packageName)
        assertFalse(order.single().fromPerson)
        val call = Notification.Builder(context, "c").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Asha").setContentText("Missed call")
            .addPerson(Person.Builder().setName("Asha").build()).build()
        assertTrue(NotificationCollector.rows(posted(call, packageName = "com.android.dialer"), "Phone", context.packageName).single().fromPerson)
    }

    @Test
    fun ongoingOwnAndExcludedAreSkipped() {
        assertTrue(NotificationCollector.rows(posted(plain("Music", "Playing", ongoing = true)), "Music", context.packageName).isEmpty())
        assertTrue(NotificationCollector.rows(posted(plain("Hi", "There"), packageName = context.packageName), "Me", context.packageName).isEmpty())
        assertTrue(NotificationCollector.rows(posted(plain("Paid", "₹200"), packageName = "com.phonepe.app"), "PhonePe", context.packageName).isEmpty())
    }

    @Test
    fun aChatPostedAgainAfterAPauseBringsOnlyNewMessages() {
        val paused = { at: Long -> at in 100L until 200L }
        val rows = NotificationCollector.rows(
            posted(chat("Asha: Before" to 50L, "Asha: During the pause" to 150L, "Asha: After" to 250L)), "WhatsApp", context.packageName, paused,
        )
        assertEquals(listOf("Before", "After"), rows.map { it.text })
        // A plain notification first shown during the pause, updated after it
        val updated = Notification.Builder(context, "c").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Bank").setContentText("Balance").setWhen(150L).build()
        assertTrue(NotificationCollector.rows(posted(updated), "Bank", context.packageName, paused).isEmpty())
    }

    @Test
    fun codesInTheTitleOrUnderAVerificationTitleAreMasked() {
        val inTitle = NotificationCollector.rows(posted(plain("482913 is your login code", "Don't share it"), tag = "a", packageName = "com.bank"), "Bank", context.packageName).single()
        assertEquals("•••• is your login code", inTitle.sender)
        assertTrue(inTitle.conversation, "482913" !in inTitle.conversation)
        val digitsOnly = NotificationCollector.rows(posted(plain("Verification code", "482913"), tag = "b", packageName = "com.bank"), "Bank", context.packageName).single()
        assertEquals("••••", digitsOnly.text)
        assertEquals("Verification code", digitsOnly.sender)
    }
}
