package ai.tushar.handset.tools

import ai.tushar.handset.context.ContextStore.TimelineItem
import ai.tushar.handset.context.NotificationCollector
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** How the context tools read times, and how one-time codes are masked before they are kept */
class ContextToolsTest {
    private val now = LocalDateTime.of(2026, 10, 27, 15, 0)
    private fun ms(time: LocalDateTime) = time.toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun sinceReadsATimeASpanOrADate() {
        assertEquals(ms(now.withHour(9)), ContextTools.parseSince("09:00", now, ZoneOffset.UTC))
        // A time still ahead today means yesterday
        assertEquals(ms(now.withHour(18).minusDays(1)), ContextTools.parseSince("18:00", now, ZoneOffset.UTC))
        assertEquals(ms(now.minusHours(3)), ContextTools.parseSince("3h", now, ZoneOffset.UTC))
        assertEquals(ms(now.minusMinutes(90)), ContextTools.parseSince("90m", now, ZoneOffset.UTC))
        assertEquals(ms(LocalDateTime.of(2026, 10, 26, 8, 30)), ContextTools.parseSince("2026-10-26T08:30", now, ZoneOffset.UTC))
        assertEquals(LocalDate.of(2026, 10, 26), ContextTools.parseDate("yesterday", LocalDate.of(2026, 10, 27)))
    }

    private fun args(vararg values: Pair<String, Any>) = ToolArgs(JsonObject(values.associate { (k, v) -> k to (if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString())) }))

    private val day = listOf(
        TimelineItem.Session(1, 2, "com.android.chrome", "Chrome"),
        TimelineItem.Visit(3, 4, 1, "Home", 12.97, 77.59),
        TimelineItem.Notification(5, "com.whatsapp", "WhatsApp", "Asha", "Coming?", "c"),
    )

    @Test
    fun theTimelineLeavesOutWhatTheCallerCantRead() {
        assertEquals(day, ContextTools.visible(day, args()))
        assertEquals(listOf(day[0], day[2]), ContextTools.visible(day, args(ContextTools.WITHOUT_LOCATION to true)))
        assertEquals(listOf(day[0]), ContextTools.visible(day, args(ContextTools.WITHOUT_LOCATION to true, ContextTools.WITHOUT_NOTIFICATIONS to true)))
    }

    @Test
    fun anAgentWithTheSameNameStillSeesOnlyTheTool() {
        val mine = TimelineItem.AgentAction(1, "Claude", "tap", "Tapping “Pay”", "ok", agentId = "phone-1")
        val theirs = TimelineItem.AgentAction(2, "Claude", "tap", "Tapping “Asha: the code is 1234”", "ok", agentId = "relay-7")
        // A step kept before ids were has no owner to match, so it shows as another agent's
        val older = TimelineItem.AgentAction(3, "Claude", "tap", "Tapping “Send”", "ok")
        assertEquals(
            listOf(mine, theirs.copy(summary = ""), older.copy(summary = "")),
            ContextTools.visible(listOf(mine, theirs, older), args(ContextTools.CALLER to "phone-1")),
        )
    }

    @Test
    fun anotherAgentsStepsShowOnlyTheTool() {
        val steps = listOf(
            TimelineItem.AgentAction(1, "Claude", "tap", "Tapping “Asha: the code is 1234”", "ok", agentId = "a1"),
            TimelineItem.AgentAction(2, "Claude", "reply_notification", "Replying to a message", "No notification from Asha: “see you at 8”", agentId = "a1"),
            TimelineItem.AgentAction(3, "Codex", "tap", "Tapping “Save”", "ok", agentId = "a2"),
        )
        assertEquals(
            listOf(
                TimelineItem.AgentAction(1, "Claude", "tap", "", "ok", agentId = "a1"),
                TimelineItem.AgentAction(2, "Claude", "reply_notification", "", "failed", agentId = "a1"),
                steps[2],
            ),
            ContextTools.visible(steps, args(ContextTools.CALLER to "a2")),
        )
        // Over adb, with no agent, every step is shown in full
        assertEquals(steps, ContextTools.visible(steps, args()))
    }

    @Test
    fun oneTimeCodesAreMasked() {
        assertEquals("Your verification code is ••••. Don't share it.", NotificationCollector.maskCodes("Your verification code is 482913. Don't share it."))
        assertEquals("Table for 4 at 2030", NotificationCollector.maskCodes("Table for 4 at 2030"))
    }
}
