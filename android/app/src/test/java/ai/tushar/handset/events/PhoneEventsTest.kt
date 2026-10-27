package ai.tushar.handset.events

import ai.tushar.handset.context.ContextDatabase
import ai.tushar.handset.context.ContextStore
import ai.tushar.handset.context.NotificationRow
import ai.tushar.handset.context.NotificationSender
import ai.tushar.handset.context.Recording
import ai.tushar.handset.context.SendStatus
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhoneEventsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private var now = 1_793_000_000_000L
    private lateinit var database: ContextDatabase
    private lateinit var store: ContextStore
    private val plain = Destination("https://example.com/hook", "secret")
    private val routine = Destination("https://api.anthropic.com/v1/claude_code/routines/trig_01ABC/fire", "sk-ant-oat01-x")

    @Before
    fun open() {
        database = ContextDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = ContextStore(database) { now }
    }

    @After
    fun close() = database.close()

    /** A sender that answers from [answers] in turn and counts its posts */
    private class Scripted(vararg answers: Answer) : Sender {
        private val queue = ArrayDeque(answers.toList())
        val bodies = ArrayList<String>()
        override suspend fun post(destination: Destination, body: String): Answer {
            bodies += body
            return queue.removeFirst()
        }
    }

    private fun event(occurrence: String, type: EventType = EventType.BATTERY_LOW) = PhoneEvent(type, now, occurrence, battery = 15)

    private suspend fun claim(event: PhoneEvent): Long? = store.claimEventSend(event.row())?.id

    private val allowed = setOf("event_id", "type", "time", "battery", "place", "person", "app")

    @Test
    fun noPayloadCarriesMessageText() {
        val message = NotificationRow(
            dedupe = "k|Asha|1", at = now, updatedAt = now, packageName = "com.whatsapp", app = "WhatsApp", sender = "Asha",
            text = "Lunch at Toit on 100 Feet Road at 1:30?", conversation = "com.whatsapp|Asha", fromPerson = true,
        )
        val order = NotificationRow(
            dedupe = "k2", at = now, updatedAt = now, packageName = "in.swiggy.android", app = "Swiggy", sender = "Order 5521 is on its way",
            text = "Ravi is 2 minutes from 14 MG Road", conversation = "in.swiggy.android|Order",
        )
        val fromAsha = PhoneEvents.notificationEvent(message, listOf(From.Person("Asha")), now)!!
        val fromSwiggy = PhoneEvents.notificationEvent(order, listOf(From.App("in.swiggy.android", "Swiggy")), now)!!
        val events = EventType.entries.map { PhoneEvent(it, now, it.code, battery = 40, place = "Office") } + fromAsha + fromSwiggy
        for (event in events) {
            for (asRoutine in listOf(false, true)) {
                val body = Payload.body(event, asRoutine, zone)
                listOf(message.text, order.text, order.sender, "Toit", "MG Road").forEach { assertFalse("$body holds $it", body.contains(it)) }
                assertTrue(Payload.fields(event, zone).keys.all { it in allowed })
            }
        }
        assertEquals("Asha", Payload.fields(fromAsha, zone)["person"]?.jsonPrimitive?.content)
        assertNull(Payload.fields(fromSwiggy, zone)["person"])
    }

    @Test
    fun aChosenTitleThatIsNoPersonSendsTheAppAlone() {
        val order = NotificationRow(
            dedupe = "k2", at = now, updatedAt = now, packageName = "in.swiggy.android", app = "Swiggy", sender = "Order 5521 is on its way",
            text = "Ravi is 2 minutes away", conversation = "in.swiggy.android|Order",
        )
        val event = PhoneEvents.notificationEvent(order, listOf(From.Person("Order 5521 is on its way")), now)!!
        assertNull(event.person)
        assertEquals("Swiggy", event.app)
        assertFalse(Payload.body(event, routine = true, zone).contains("5521"))
    }

    @Test
    fun onlyPeopleFromChatsAreOfferedAsPeople() {
        val senders = listOf(
            NotificationSender("com.whatsapp", "WhatsApp", "Asha", 9, fromPerson = true),
            NotificationSender("com.whatsapp", "WhatsApp", "Me", 3, fromPerson = true),
            NotificationSender("in.swiggy.android", "Swiggy", "Order 5521 is on its way", 4, fromPerson = false),
        )
        val (people, apps) = PhoneEvents.choices(senders, emptyList())
        assertEquals(listOf(From.Person("Asha")), people)
        assertEquals(listOf("com.whatsapp", "in.swiggy.android"), apps.map { it.packageName })
    }

    @Test
    fun aRoutineGetsTheFieldsInsideText() {
        val body = Json.parseToJsonElement(Payload.body(PhoneEvent(EventType.ARRIVED, now, "a|1", place = "Office"), routine = true, zone)).jsonObject
        val text = body.getValue("text").jsonPrimitive.content
        assertEquals(setOf("text"), body.keys)
        assertTrue(text.startsWith("Handset phone event. The phone arrived at Office."))
        assertTrue(text.contains("\"type\": \"arrived\""))
        assertTrue(text.contains("\"place\": \"Office\""))
    }

    @Test
    fun everyTryCarriesTheSameEventId() = runTest {
        val sender = Scripted(Answer.Busy("503"), Answer.Sent(200))
        val delivery = Delivery(store, { routine }, sender)
        val id = claim(event("battery_low|7"))!!
        delivery.attempt(id)
        delivery.attempt(id)
        val ids = sender.bodies.map { Regex("\"event_id\": \"(evt_[0-9a-f]+)\"").find(Json.parseToJsonElement(it).jsonObject.getValue("text").jsonPrimitive.content)!!.groupValues[1] }
        assertEquals(1, ids.distinct().size)
        assertEquals(ids.first(), Payload.fields(event("battery_low|7"), zone)["event_id"]?.jsonPrimitive?.content)
        assertFalse(ids.first() == event("battery_low|8").id)
    }

    @Test
    fun oneOccurrenceIsSentOnceThroughRetries() = runTest {
        val sender = Scripted(Answer.Busy("503"), Answer.Busy("timeout"), Answer.Sent(200))
        val delivery = Delivery(store, { plain }, sender)
        val id = claim(event("battery_low|1"))!!
        // The same discharge seen again isn't kept again
        assertNull(claim(event("battery_low|1")))
        assertEquals(Delivery.Next.Retry, delivery.attempt(id))
        assertEquals(Delivery.Next.Retry, delivery.attempt(id))
        assertEquals(Delivery.Next.Done, delivery.attempt(id))
        // A worker run again after the send posts nothing
        assertEquals(Delivery.Next.Done, delivery.attempt(id))
        assertEquals(3, sender.bodies.size)
        val row = store.eventSend(id)!!
        assertEquals(SendStatus.SENT.name, row.status)
        assertEquals(3, row.attempts)
    }

    @Test
    fun aSendLeftUnqueuedIsQueuedOnceOnStart() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val events = PhoneEvents(context, store, Recording(context, context.getSharedPreferences("recording-test", 0)), this) { now }
        // Kept, and the process died before it was queued
        val stranded = claim(event("battery_low|3"))!!
        val sent = claim(event("battery_low|4"))!!
        store.updateEventSend(sent, SendStatus.SENT, "HTTP 200", 1)
        events.resume()
        events.resume()
        val work = WorkManager.getInstance(context)
        assertEquals(1, work.getWorkInfosForUniqueWork("event_send_$stranded").get().size)
        assertTrue(work.getWorkInfosForUniqueWork("event_send_$sent").get().isEmpty())
    }

    @Test
    fun aRefusalIsNeverRetried() = runTest {
        val sender = Scripted(Answer.Refused("The address answered 404."))
        val delivery = Delivery(store, { plain }, sender)
        val id = claim(event("x"))!!
        assertEquals(Delivery.Next.Done, delivery.attempt(id))
        assertEquals(Delivery.Next.Done, delivery.attempt(id))
        assertEquals(1, sender.bodies.size)
        assertEquals(SendStatus.FAILED.name, store.eventSend(id)!!.status)
        assertEquals("The address answered 404.", store.eventSend(id)!!.detail)
    }

    @Test
    fun aBusyAddressIsGivenUpOn() = runTest {
        val sender = Scripted(*Array(Delivery.MAX_ATTEMPTS) { Answer.Busy("The address answered 503.") })
        val delivery = Delivery(store, { plain }, sender)
        val id = claim(event("x"))!!
        repeat(Delivery.MAX_ATTEMPTS - 1) { assertEquals(Delivery.Next.Retry, delivery.attempt(id)) }
        assertEquals(Delivery.Next.Done, delivery.attempt(id))
        assertEquals(SendStatus.FAILED.name, store.eventSend(id)!!.status)
        assertEquals(Delivery.MAX_ATTEMPTS, sender.bodies.size)
    }

    @Test
    fun theCapCountsEveryPostAndHoldsTheRestForLater() = runTest {
        val busyThenSent = Scripted(*Array(PhoneEvents.CAP / 2) { Answer.Busy("503") }, *Array(PhoneEvents.CAP / 2) { Answer.Sent(200) }, Answer.Sent(200))
        val delivery = Delivery(store, { plain }, busyThenSent)
        val first = (1..PhoneEvents.CAP / 2).map { claim(event("e$it"))!! }
        first.forEach { assertEquals(Delivery.Next.Retry, delivery.attempt(it)) }
        first.forEach { assertEquals(Delivery.Next.Done, delivery.attempt(it)) }
        // Ten events and their ten tries make twenty POSTs, so the next event waits for the hour rather than being dropped
        val late = claim(event("late"))!!
        val start = now
        assertEquals(Delivery.Next.Wait(start + 60 * 60_000L), delivery.attempt(late))
        assertEquals(PhoneEvents.CAP, busyThenSent.bodies.size)
        assertEquals(SendStatus.WAITING.name, store.eventSend(late)!!.status)
        assertTrue(PhoneEvent.outcome(store.eventSend(late)!!).startsWith("Waiting."))
        assertTrue(store.unsentEventSends().any { it.id == late })
        now = start + 60 * 60_000L
        assertEquals(Delivery.Next.Done, delivery.attempt(late))
        assertEquals(SendStatus.SENT.name, store.eventSend(late)!!.status)
    }

    @Test
    fun batteryLowFiresOncePerDischarge() {
        var memory: Battery.Memory? = null
        val sent = ArrayList<PhoneEvent>()
        fun read(at: Long, level: Int, plugged: Boolean) {
            val step = Battery.step(memory, Battery.Reading(at, level, plugged), threshold = 20)
            memory = step.memory
            sent += step.events
        }
        read(0, 50, plugged = false)
        read(1, 19, plugged = false)
        read(2, 18, plugged = false)
        read(3, 15, plugged = false)
        read(4, 16, plugged = true)
        read(5, 30, plugged = false)
        read(6, 12, plugged = false)
        assertEquals(
            listOf(EventType.BATTERY_LOW, EventType.CHARGING_STARTED, EventType.CHARGING_STOPPED, EventType.BATTERY_LOW),
            sent.map { it.type },
        )
        assertEquals(listOf("battery_low|0", "battery_low|5"), sent.filter { it.type == EventType.BATTERY_LOW }.map { it.occurrence })
        // The first reading is the state the switch found, not news
        assertTrue(Battery.step(null, Battery.Reading(0, 5, plugged = false), 20).events.isEmpty())
    }

    @Test
    fun aClaudeRoutineIsToldApartAndGetsItsHeaders() {
        assertTrue(routine.routine)
        assertEquals("Claude routine trig_01ABC", routine.label)
        assertEquals(mapOf("Authorization" to "Bearer sk-ant-oat01-x", "anthropic-version" to Destination.ANTHROPIC_VERSION), routine.headers())
        assertFalse(plain.routine)
        assertEquals(mapOf("Authorization" to "Bearer secret"), plain.headers())
        assertTrue(Destination.parse("http://192.168.1.4/hook", "").isFailure)
        assertTrue(Destination.parse(routine.url, "").isFailure)
        assertEquals(Destination("http://127.0.0.1:9000/hook"), Destination.parse(" http://127.0.0.1:9000/hook ", "").getOrThrow())
    }

    @Test
    fun theDestinationIsKeptSealed() {
        val preferences = ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("events-test", 0)
        val flip = object : Sealer {
            override fun seal(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
            override fun open(sealed: ByteArray) = seal(sealed)
        }
        DestinationStore(preferences, flip).save(routine)
        assertFalse(preferences.all.values.joinToString().contains("sk-ant"))
        assertEquals(routine, DestinationStore(preferences, flip).load())
    }

    @Test
    fun httpAnswersAreSortedForRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.example"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val client = OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).followRedirects(false).build()
            val sender = HttpSender(client)
            val to = Destination(server.url("/hook").toString(), "secret")
            val answers = (1..6).map { sender.post(to, "{}") }
            assertEquals(Answer.Sent(200), answers[0])
            assertTrue(answers[1] is Answer.Busy)
            assertTrue(answers[2] is Answer.Refused)
            assertTrue(answers[3] is Answer.Refused)
            assertTrue(answers[4] is Answer.Refused)
            assertTrue(answers[5] is Answer.Busy)
            val first = server.takeRequest()
            assertEquals("Bearer secret", first.getHeader("Authorization"))
            assertNotNull(first.getHeader("Content-Type")?.takeIf { it.startsWith("application/json") })
        }
    }
}
