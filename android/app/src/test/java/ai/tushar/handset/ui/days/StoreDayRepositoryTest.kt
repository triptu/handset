package ai.tushar.handset.ui.days

import ai.tushar.handset.context.AgentActionRow
import ai.tushar.handset.context.ContextDatabase
import ai.tushar.handset.context.ContextStore
import ai.tushar.handset.context.FrameRow
import ai.tushar.handset.context.NotificationRow
import ai.tushar.handset.context.SendStatus
import ai.tushar.handset.context.SessionRow
import ai.tushar.handset.events.EventType
import ai.tushar.handset.events.PhoneEvent
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The Days screens read from a real store in memory: a day's moments, a moment opened, search and an agent's runs */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StoreDayRepositoryTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 10, 27)
    private val midnight = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private val now = midnight + 20 * HOUR
    private lateinit var database: ContextDatabase
    private lateinit var store: ContextStore
    private lateinit var days: StoreDayRepository

    @Before
    fun open() {
        database = ContextDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = ContextStore(database) { now }
        days = StoreDayRepository(store, zone) { now }
    }

    @After
    fun close() = database.close()

    private fun at(hour: Int, minute: Int = 0) = midnight + hour * HOUR + minute * MIN

    private suspend fun frame(at: Long, text: String, packageName: String = "com.android.chrome", app: String = "Chrome") =
        store.addFrame(FrameRow(at = at, packageName = packageName, app = app, text = text))

    private suspend fun message(key: String, at: Long, sender: String, text: String, conversation: String = "wa|Asha") =
        store.putNotification(NotificationRow(dedupe = key, at = at, updatedAt = at, packageName = "com.whatsapp", app = "WhatsApp", sender = sender, text = text, conversation = conversation))

    @Test
    fun aSessionShowsItsFirstLineAndOpensToItsScreensEachLineOnce() = runTest {
        store.putSessions(listOf(SessionRow(packageName = "com.android.chrome", app = "Chrome", start = at(9), end = at(9, 20))))
        frame(at(9, 1), "Chrome\nE-bikes under 1 lakh, tested\nThe Hero Lectro C5 rides well")
        frame(at(9, 5), "The Hero Lectro C5 rides well\nIts battery lasts 40 km")
        frame(at(9, 12), "Checkout\nPay 4,999")
        val moment = days.timeline(day).moments.single() as Moment.AppSession
        assertEquals("E-bikes under 1 lakh, tested", moment.line)
        val detail = days.detail(moment.id) as MomentDetail.ScreenText
        val texts = detail.blocks.map { if (it is ScreenBlock.Text) it.text else "--" }
        assertEquals(listOf("Chrome\nE-bikes under 1 lakh, tested\nThe Hero Lectro C5 rides well", "Its battery lasts 40 km", "--", "Checkout\nPay 4,999"), texts)
    }

    @Test
    fun screensReadWithoutAppUseStillMakeMomentsAndAShortVisitWithNothingReadDoesNot() = runTest {
        store.putSessions(listOf(SessionRow(packageName = "com.example.launcherish", app = "Files", start = at(8), end = at(8) + 20_000)))
        frame(at(10), "Inbox\nYour order has shipped today", "com.google.android.gm", "Gmail")
        frame(at(10, 4), "Your order has shipped today\nTrack it", "com.google.android.gm", "Gmail")
        frame(at(11), "Inbox\nAnother morning email here", "com.google.android.gm", "Gmail")
        val moments = days.timeline(day).moments.map { it as Moment.AppSession }
        assertEquals(listOf(at(10), at(11)), moments.map { it.at.toEpochMilli() }.sorted())
    }

    @Test
    fun aConversationsCloseMessagesAreOneMomentThatOpensToTheDaysMessages() = runTest {
        message("1", at(9), "Asha", "Lunch?")
        message("2", at(13), "Me", "Sure, where?")
        message("3", at(13, 5), "Asha", "Toit at 1:30")
        val messages = days.timeline(day).moments.filterIsInstance<Moment.Message>().sortedBy { it.at }
        assertEquals(listOf("Lunch?", "Toit at 1:30"), messages.map { it.text })
        assertEquals("Asha", messages.last().sender)
        val detail = days.detail(messages.last().id) as MomentDetail.Conversation
        assertEquals(listOf("Lunch?", "Sure, where?", "Toit at 1:30"), detail.messages.map { it.text })
        assertEquals(listOf(false, true, false), detail.messages.map { it.fromYou })
    }

    @Test
    fun anAgentsStepsCloseTogetherAreOneRunNamedByWhatItDid() = runTest {
        store.addAgentAction(AgentActionRow(at = at(16, 9), agent = "Claude Code", tool = "get_screen", summary = "Reading the screen", outcome = "ok"))
        store.addAgentAction(AgentActionRow(at = at(16, 10), agent = "Claude Code", tool = "open_app", summary = "Opening Clock", outcome = "ok"))
        store.addAgentAction(AgentActionRow(at = at(16, 11), agent = "Claude Code", tool = "tap", summary = "Tapping “Timer”", outcome = "Declined: Tap “Timer”"))
        store.addAgentAction(AgentActionRow(at = at(18), agent = "Claude Code", tool = "screenshot", summary = "Looking at the screen", outcome = "ok"))
        val runs = days.agentRuns("cc-1", "Claude Code", 3)
        assertEquals(listOf("Looking at the screen", "Opening Clock, Tapping “Timer”"), runs.map { it.line })
        val steps = (days.detail(runs.last().id) as MomentDetail.AgentSteps).steps.map { it.text }
        assertEquals(listOf("Reading the screen", "Opening Clock", "Tapping “Timer”. Declined: Tap “Timer”"), steps)
    }

    @Test
    fun twoAgentsWithOneNameHaveTheirOwnRuns() = runTest {
        store.addAgentAction(AgentActionRow(at = at(16), agent = "Claude", tool = "open_app", summary = "Opening Clock", outcome = "ok", agentId = "phone-1"))
        store.addAgentAction(AgentActionRow(at = at(16, 1), agent = "Claude", tool = "open_app", summary = "Opening Maps", outcome = "ok", agentId = "relay-7"))
        // Kept before ids were, so it goes with the agent of that name
        store.addAgentAction(AgentActionRow(at = at(9), agent = "Claude", tool = "open_app", summary = "Opening Chrome", outcome = "ok"))
        val runs = days.timeline(day).moments.filterIsInstance<Moment.AgentRun>()
        assertEquals(listOf("Opening Chrome", "Opening Clock", "Opening Maps"), runs.sortedBy { it.at }.map { it.line })
        assertEquals(listOf("Opening Maps"), (days.detail(runs.first { it.line == "Opening Maps" }.id) as MomentDetail.AgentSteps).steps.map { it.text })
        assertEquals(listOf("Opening Clock", "Opening Chrome"), days.agentRuns("phone-1", "Claude", 5).map { it.line })
        assertEquals(listOf("Opening Maps", "Opening Chrome"), days.agentRuns("relay-7", "Claude", 5).map { it.line })
    }

    @Test
    fun searchFindsTheMomentAWordWasIn() = runTest {
        store.putSessions(listOf(SessionRow(packageName = "com.android.chrome", app = "Chrome", start = at(9), end = at(9, 20))))
        frame(at(9, 1), "Dinner plans\nArjun said try Toit on 100 Feet Road")
        frame(at(9, 2), "Dinner plans\nArjun said try Toit on 100 Feet Road\nThey have pizza")
        message("1", at(12), "Asha", "Meet at Toit?")
        val hits = days.search("toit")
        assertEquals(2, hits.size)
        val screen = hits.first { it.moment is Moment.AppSession }
        assertTrue(screen.snippet, "[" !in screen.snippet)
        assertEquals("Toit", screen.snippet.substring(screen.matches.single()))
        assertEquals(day, days.firstDay())
    }

    @Test
    fun aVisitStillGoingHasNoEnd() = runTest {
        store.addVisit(at(19), now - 5 * MIN, 12.97, 77.59)
        val visit = days.timeline(day).visits.single()
        assertEquals(Instant.ofEpochMilli(at(19)), visit.start)
        assertEquals(null, visit.end)
        assertEquals(StoreDayRepository.UNNAMED_PLACE, visit.label)
    }

    @Test
    fun aPlaceIsNamedRenamedAndForgottenFromItsVisit() = runTest {
        store.addVisit(at(9), at(11), 12.97, 77.59)
        val unnamed = days.timeline(day).visits.single()
        assertEquals(false, unnamed.named)
        days.namePlace(unnamed.id!!, "Office")
        assertEquals("Office" to true, days.timeline(day).visits.single().let { it.label to it.named })
        days.namePlace(unnamed.id!!, "Studio")
        assertEquals("Studio", days.timeline(day).visits.single().label)
        days.forgetPlace(unnamed.id!!)
        assertEquals(StoreDayRepository.UNNAMED_PLACE to false, days.timeline(day).visits.single().let { it.label to it.named })
    }

    @Test
    fun aPhoneEventSendIsAMomentThatOpensToWhatWasSent() = runTest {
        val row = store.claimEventSend(PhoneEvent(EventType.ARRIVED, at(9), "arrived|1", place = "Office").row())!!
        store.updateEventSend(row.id, SendStatus.FAILED, "The address answered 404.", 1)
        val moment = days.timeline(day).moments.single() as Moment.EventSent
        assertEquals("Arrived at Office", moment.title)
        assertEquals("Not sent. The address answered 404.", moment.line)
        val detail = days.detail(moment.id) as MomentDetail.EventFields
        assertTrue(detail.payload.contains("\"place\": \"Office\""))
        assertEquals("The phone arrived at Office.", detail.sentence)
    }

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
    }
}
