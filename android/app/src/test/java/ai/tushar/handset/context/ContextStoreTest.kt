package ai.tushar.handset.context

import android.app.Application
import androidx.room3.RoomRawQuery
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** The day record's store on the bundled SQLite, in memory: search, notification updates, retention and the day's queries */
@RunWith(RobolectricTestRunner::class)
// A plain Application, so the app's own start-up (WorkManager, location) stays out of the test
@Config(sdk = [35], application = Application::class)
class ContextStoreTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 10, 27)
    private val midnight = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private var now = midnight + 18 * HOUR
    private lateinit var database: ContextDatabase
    private lateinit var store: ContextStore

    @Before
    fun open() {
        database = ContextDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = ContextStore(database) { now }
    }

    @After
    fun close() = database.close()

    private suspend fun frame(at: Long, text: String, packageName: String = "com.android.chrome") =
        store.addFrame(FrameRow(at = at, packageName = packageName, app = "Chrome", text = text))

    private fun notification(dedupe: String, at: Long, sender: String, text: String, conversation: String = "wa|$sender") = NotificationRow(
        dedupe = dedupe, at = at, updatedAt = at, packageName = "com.whatsapp", app = "WhatsApp", sender = sender, text = text, conversation = conversation,
    )

    /** Rows the full-text index holds for [word], read from the index itself, not through its table */
    private suspend fun indexed(table: String, word: String): Int = database.dao().searchHits(
        RoomRawQuery("SELECT '' AS kind, rowid AS id, 0 AS at, '' AS packageName, '' AS app, NULL AS sender, '' AS snippet, 0.0 AS rank FROM ${table}_fts WHERE ${table}_fts MATCH ?") {
            it.bindText(1, word)
        },
    ).size

    @Test
    fun searchFindsAFrameByAWordInItAndReturnsItsSnippet() = runTest {
        frame(now - HOUR, "Dinner plans\nArjun said try Toit on 100 Feet Road, they have great pizza\nReply")
        frame(now - 2 * HOUR, "Weather today\nSunny, 31 degrees")
        val hits = store.search("toit", since = midnight)
        assertEquals(1, hits.size)
        val hit = hits.single()
        assertEquals("screen", hit.kind)
        assertEquals("Chrome", hit.app)
        assertEquals(now - HOUR, hit.at)
        assertTrue(hit.snippet, hit.snippet.contains("[Toit]"))
        // Words match by their start, and punctuation in the query is not FTS syntax
        assertEquals(1, store.search("pizz", since = midnight).size)
        assertEquals(1, store.search("\"Toit\" (feet*", since = midnight).size)
        assertEquals(0, store.search("toit", since = now).size)
    }

    @Test
    fun searchListsTheSameSnippetOnceAndFindsNotifications() = runTest {
        frame(now - 3 * HOUR, "Tracking number AB123456789IN\nOut for delivery")
        frame(now - 2 * HOUR, "Tracking number AB123456789IN\nOut for delivery")
        store.putNotification(notification("k1", now - HOUR, "Courier", "Your parcel AB123456789IN arrives today"))
        val hits = store.search("AB123456789IN", since = midnight)
        assertEquals(listOf("screen", "notification").sorted(), hits.map { it.kind }.sorted())
        assertEquals(now - 2 * HOUR, hits.first { it.kind == "screen" }.at)
        assertEquals("Courier", hits.first { it.kind == "notification" }.sender)
        assertEquals(listOf("screen"), store.search("AB123456789IN", since = midnight, notifications = false).map { it.kind })
    }

    @Test
    fun aRepeatedNotificationUpdateDoesNotAddARow() = runTest {
        assertTrue(store.putNotification(notification("0|com.whatsapp|1|null|10", now - HOUR, "Asha", "Are you coming?")))
        // The same notification posted again, unchanged, then updated with new text
        assertTrue(!store.putNotification(notification("0|com.whatsapp|1|null|10", now - HOUR, "Asha", "Are you coming?")))
        assertTrue(store.putNotification(notification("0|com.whatsapp|1|null|10", now - HOUR, "Asha", "Are you coming? We're at the table")))
        assertEquals(1L, store.counts()["notifications"])
        // The search index follows the update: the new words are found, and the row is found once
        assertEquals(1, indexed("notifications", "table"))
        assertEquals(1, indexed("notifications", "coming"))
    }

    @Test
    fun retentionDeletesOldRowsAndTheirSearchEntries() = runTest {
        frame(now - 31 * DAY, "Old receipt for the blue kettle")
        frame(now - 29 * DAY, "Newer receipt for the red kettle")
        store.putNotification(notification("old", now - 31 * DAY, "Shop", "Your kettle shipped"))
        store.putSessions(listOf(SessionRow(packageName = "a", app = "A", start = now - 400 * DAY, end = now - 400 * DAY + HOUR)))
        store.putSessions(listOf(SessionRow(packageName = "a", app = "A", start = now - 100 * DAY, end = now - 100 * DAY + HOUR)))
        store.addAgentAction(AgentActionRow(at = now - 366 * DAY, agent = "adb", tool = "tap", summary = "Tapping", outcome = "ok"))
        assertEquals(2, indexed("frames", "kettle"))

        store.clean()

        assertEquals(1L, store.counts()["frames"])
        assertEquals(0L, store.counts()["notifications"])
        assertEquals(1L, store.counts()["sessions"])
        assertEquals(0L, store.counts()["agent_actions"])
        assertEquals(1, indexed("frames", "kettle"))
        assertEquals(0, indexed("frames", "blue"))
        assertEquals(0, indexed("notifications", "kettle"))
    }

    @Test
    fun deleteTodayKeepsYesterday() = runTest {
        frame(now - HOUR, "Today's page about bikes")
        frame(now - DAY, "Yesterday's page about bikes")
        store.addVisit(now - 3 * HOUR, now - 2 * HOUR, 12.97, 77.59)
        store.deleteToday(zone)
        assertEquals(1L, store.counts()["frames"])
        assertEquals(0L, store.counts()["visits"])
        assertEquals(0, indexed("frames", "today"))
    }

    @Test
    fun whatDeleteTodayRemovedIsntWrittenAgain() = runTest {
        store.putNotification(notification("chat|Asha|1", now - HOUR, "Asha", "Meet at the kettle shop"))
        store.putNotification(notification("parcel", now - 2 * HOUR, "Courier", "Arrives today"))
        store.putSessions(listOf(SessionRow(packageName = "com.android.chrome", app = "Chrome", start = now - HOUR, end = now - MIN)))
        store.deleteToday(zone)
        assertEquals(now, store.deletedThrough())
        now += MIN
        // The chat posted again with its whole bundle, the parcel notification updated, and app use read back
        assertFalse(store.putNotification(notification("chat|Asha|1", now - HOUR, "Asha", "Meet at the kettle shop")))
        assertFalse(store.putNotification(notification("parcel", now, "Courier", "Out for delivery")))
        store.putSessions(listOf(SessionRow(packageName = "com.android.chrome", app = "Chrome", start = now - HOUR, end = now)))
        assertEquals(0L, store.counts()["notifications"])
        assertEquals(0L, store.counts()["sessions"])
        assertEquals(0, indexed("notifications", "kettle"))
        // What comes after is kept
        assertTrue(store.putNotification(notification("chat|Asha|2", now, "Asha", "Running late")))
    }

    @Test
    fun aVersionOneDatabaseOpensWithDeletions() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val path = context.getDatabasePath("migration-test.db").apply { parentFile?.mkdirs(); delete() }.absolutePath
        ContextDatabase.open(context, path).apply {
            ContextStore(this).addFrame(FrameRow(at = now, packageName = "p", app = "App", text = "kept text"))
            close()
        }
        // Back to version 1: no deletion tables
        androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(path).apply {
            listOf("DROP TABLE deletions", "DROP TABLE deleted_notifications", "DROP TABLE event_sends", "DROP TABLE event_posts", "ALTER TABLE notifications DROP COLUMN fromPerson", "ALTER TABLE agent_actions DROP COLUMN agentId", "PRAGMA user_version = 1").forEach { sql -> prepare(sql).use { it.step() } }
            close()
        }
        val upgraded = ContextDatabase.open(context, path)
        val upgradedStore = ContextStore(upgraded) { now }
        upgradedStore.deleteToday(zone)
        assertEquals(now, upgradedStore.deletedThrough())
        upgraded.close()
    }

    @Test
    fun aVersionTwoDatabaseOpensWithEventSendsAndSenderKinds() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val path = context.getDatabasePath("migration-3-test.db").apply { parentFile?.mkdirs(); delete() }.absolutePath
        ContextDatabase.open(context, path).apply {
            ContextStore(this).addFrame(FrameRow(at = now, packageName = "p", app = "App", text = "kept text"))
            ContextStore(this).putNotification(notification("n", now, "Order 5521 is on its way", "2 minutes away"))
            close()
        }
        androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(path).apply {
            listOf("DROP TABLE event_sends", "DROP TABLE event_posts", "ALTER TABLE notifications DROP COLUMN fromPerson", "ALTER TABLE agent_actions DROP COLUMN agentId", "PRAGMA user_version = 2").forEach { sql -> prepare(sql).use { it.step() } }
            close()
        }
        val upgraded = ContextDatabase.open(context, path)
        val row = EventSendRow(occurrence = "test|1", at = now, type = "test", status = SendStatus.PENDING.name)
        assertEquals("test|1", ContextStore(upgraded) { now }.claimEventSend(row)?.occurrence)
        // A notification kept before senders were told apart is offered only as its app
        assertFalse(ContextStore(upgraded) { now }.notificationSenders(0, 10).single().fromPerson)
        assertNull(ContextStore(upgraded) { now }.reserveEventPost(cap = 20))
        upgraded.close()
    }

    @Test
    fun aVersionFourDatabaseKeepsItsStepsAndGainsAgentIds() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val path = context.getDatabasePath("migration-4-test.db").apply { parentFile?.mkdirs(); delete() }.absolutePath
        ContextDatabase.open(context, path).apply {
            ContextStore(this).addAgentAction(AgentActionRow(at = now - HOUR, agent = "Claude", tool = "tap", summary = "Tapping “Pay”", outcome = "ok"))
            close()
        }
        androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(path).apply {
            listOf("ALTER TABLE agent_actions DROP COLUMN agentId", "PRAGMA user_version = 4").forEach { sql -> prepare(sql).use { it.step() } }
            close()
        }
        val upgraded = ContextDatabase.open(context, path)
        val upgradedStore = ContextStore(upgraded) { now }
        upgradedStore.addAgentAction(AgentActionRow(at = now, agent = "Claude", tool = "tap", summary = "Tapping “Send”", outcome = "ok", agentId = "relay-7"))
        val steps = upgradedStore.dayTimeline(day, zone).filterIsInstance<ContextStore.TimelineItem.AgentAction>()
        assertEquals(listOf(null, "relay-7"), steps.map { it.agentId })
        assertEquals("Tapping “Pay”", steps.first().summary)
        // The older step still shows on its agent's page, by name
        assertEquals(2, upgradedStore.latestAgentActions("relay-7", "Claude", 10).size)
        assertTrue(upgradedStore.latestAgentActions("phone-1", "Codex", 10).isEmpty())
        upgraded.close()
    }

    @Test
    fun theDayTimelineIsInTimeOrder() = runTest {
        store.putSessions(listOf(SessionRow(packageName = "com.android.chrome", app = "Chrome", start = midnight + 9 * HOUR, end = midnight + 9 * HOUR + 30 * MIN)))
        store.addVisit(midnight + 8 * HOUR, midnight + 10 * HOUR, 12.97, 77.59)
        store.putNotification(notification("n", midnight + 9 * HOUR + 10 * MIN, "Asha", "Lunch?"))
        store.addAgentAction(AgentActionRow(at = midnight + 9 * HOUR + 5 * MIN, agent = "adb", tool = "open_app", summary = "Opening Chrome", outcome = "ok"))
        // Yesterday's entries stay out
        store.addAgentAction(AgentActionRow(at = midnight - HOUR, agent = "adb", tool = "tap", summary = "Tapping", outcome = "ok"))

        val items = store.dayTimeline(day, zone)
        assertEquals(
            listOf("Visit", "Session", "AgentAction", "Notification"),
            items.map { it::class.simpleName },
        )
    }

    @Test
    fun theInboxGroupsByConversationNewestFirst() = runTest {
        store.putNotification(notification("a1", now - 3 * HOUR, "Asha", "Lunch?"))
        store.putNotification(notification("r1", now - 2 * HOUR, "Ravi", "Call me"))
        store.putNotification(notification("a2", now - HOUR, "Asha", "At the usual place"))
        store.putNotification(notification("old", now - 2 * DAY, "Old", "Yesterday's news"))
        val groups = store.inboxSince(now - 4 * HOUR)
        assertEquals(listOf("Asha", "Ravi"), groups.map { it.sender })
        assertEquals(listOf("Lunch?", "At the usual place"), groups.first().messages.map { it.text })
    }

    @Test
    fun namingAPlaceNamesItsVisitsAndLaterOnes() = runTest {
        val first = store.addVisit(now - 5 * HOUR, now - 4 * HOUR, 12.9716, 77.5946)
        val far = store.addVisit(now - 3 * HOUR, now - 2 * HOUR, 12.9352, 77.6245)
        store.nameVisit(first, "Home")
        // 100 m away
        store.addVisit(now - HOUR, now, 12.9725, 77.5946)
        val labels = store.dayTimeline(day, zone).filterIsInstance<ContextStore.TimelineItem.Visit>().associate { it.visitId to it.label }
        assertEquals("Home", labels[first])
        assertNull(labels[far])
        assertEquals(listOf("Home", null, "Home"), labels.values.toList())
    }

    @Test
    fun renamingAPlaceRenamesEveryVisitAndWhatPhoneEventsRead() = runTest {
        val first = store.addVisit(now - 5 * HOUR, now - 4 * HOUR, 12.9716, 77.5946)
        val placeId = store.nameVisit(first, "Home")
        val later = store.addVisit(now - HOUR, now, 12.9725, 77.5946)
        // Arriving at or leaving a place sends the name read here at that moment
        assertEquals("Home", store.visitLabel(later))
        assertEquals(placeId, store.nameVisit(later, " Flat 4B "))
        assertEquals("Flat 4B", store.visitLabel(first))
        assertEquals("Flat 4B", store.visitLabel(later))
        assertEquals(1L, store.counts()["places"])
    }

    @Test
    fun forgettingAPlaceUnnamesItsVisitsAndLaterOnes() = runTest {
        val first = store.addVisit(now - 5 * HOUR, now - 4 * HOUR, 12.9716, 77.5946)
        store.nameVisit(first, "Gym")
        val second = store.addVisit(now - 3 * HOUR, now - 2 * HOUR, 12.9716, 77.5946)
        store.forgetPlace(second)
        assertNull(store.visitLabel(first))
        assertNull(store.visitLabel(second))
        assertNull(store.visitLabel(store.addVisit(now - HOUR, now, 12.9716, 77.5946)))
        assertEquals(0L, store.counts()["places"])
        assertEquals(3L, store.counts()["visits"])
    }

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val DAY = 24 * HOUR
    }
}
