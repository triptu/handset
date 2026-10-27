package ai.tushar.handset.runner

import ai.tushar.handset.device.Consent
import ai.tushar.handset.tools.PhoneTool
import ai.tushar.handset.tools.ToolContent
import ai.tushar.handset.tools.ToolFailure
import ai.tushar.handset.tools.ToolOutput
import ai.tushar.handset.tools.objectSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRunnerTest {
    /** What the runner did, in order, as "start tap" and "end tap" */
    private val events = mutableListOf<String>()

    private class FakeHost : ToolRunner.Host {
        override var enabled = true
        override var askBeforeSending = true
        var answer = CompletableDeferred<Consent.Answer>()
        val asked = mutableListOf<String>()
        override suspend fun ask(prompt: String): Consent.Answer {
            asked.add(prompt)
            return answer.await()
        }
        override suspend fun currentScreen() = "screen"
        val recorded = mutableListOf<String>()
        override fun record(call: ToolCall, detail: String, ok: Boolean) {
            recorded.add("${call.agent} ${call.tool} ${if (ok) "ok" else "failed"}: $detail")
        }
        override fun showStatus(label: String, agent: String?) = Unit
        override fun clearStatus() = Unit
    }

    private fun tool(slug: String, ms: Long, concurrent: Boolean = false, prompt: String? = null) = PhoneTool(
        slug = slug,
        description = slug,
        inputSchema = objectSchema(),
        consentPrompt = { prompt },
        concurrent = concurrent,
    ) {
        events.add("start $slug")
        delay(ms)
        events.add("end $slug")
        ToolOutput("$slug done", listOf(ToolOutput.Image("aGk=", "image/jpeg")))
    }

    private fun TestScope.runner(host: ToolRunner.Host = FakeHost()) = ToolRunner(
        listOf(
            tool("tap", 1_000),
            tool("scroll", 1_000),
            tool("wait", 5_000),
            tool("read_sms", 100, concurrent = true),
            tool("pay", 100, prompt = "Tap \"Pay\""),
            PhoneTool("get_screen", "get_screen", objectSchema()) { throw ToolFailure("Handset's own screens are private to the user.") },
        ),
        host,
        clock = { testScheduler.currentTime },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `pairing approval blocks screen tools and releases them after dismissal`() = runTest {
        val runner = runner()
        val dismiss = CompletableDeferred<Unit>()
        val prompt = launch { runner.withScreenPaused { dismiss.await() } }
        runCurrent()
        val tap = runner.submit(ToolCall("pair-tap", "tap"))
        val sms = runner.submit(ToolCall("pair-sms", "read_sms"))
        advanceTimeBy(200)
        runCurrent()
        assertFalse(events.contains("start tap"))
        assertTrue(sms.isCompleted)
        prompt.cancel()
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(tap.await() is ToolResult.Done)
    }

    @Test
    fun `a refused screen call shows in the history with the agent's name`() = runTest {
        val host = FakeHost()
        val result = runner(host).submit(ToolCall("1", "get_screen", agent = "Claude Code"))
        advanceUntilIdle()
        assertEquals("Handset's own screens are private to the user.", (result.await() as ToolResult.Failed).message)
        assertEquals(listOf("Claude Code get_screen failed: Handset's own screens are private to the user."), host.recorded)
    }

    @Test
    fun `a call stopped while it runs is recorded once, and one stopped in the queue never ran`() = runTest {
        val host = FakeHost()
        val runner = runner(host)
        runner.submit(ToolCall("1", "tap", agent = "Claude Code"))
        val scroll = runner.submit(ToolCall("2", "scroll", agent = "Claude Code"))
        val wait = runner.submit(ToolCall("3", "wait", agent = "Claude Code"))
        advanceTimeBy(1_500)
        scroll.cancel()
        wait.cancel()
        advanceUntilIdle()
        assertEquals(listOf("Claude Code tap ok: tap done", "Claude Code scroll failed: Stopped"), host.recorded)
    }

    @Test
    fun `screen calls run one at a time in arrival order, and concurrent ones beside them`() = runTest {
        val runner = runner()
        val tap = runner.submit(ToolCall("1", "tap"))
        val scroll = runner.submit(ToolCall("2", "scroll"))
        val sms = runner.submit(ToolCall("3", "read_sms"))
        advanceUntilIdle()
        assertEquals(listOf("start tap", "end tap", "start scroll", "end scroll"), events.filter { "read_sms" !in it })
        // The read ran beside the tap, not after it
        assertTrue(events.indexOf("end read_sms") < events.indexOf("end tap"))
        assertTrue(tap.await() is ToolResult.Done)
        assertTrue(scroll.await() is ToolResult.Done)
        assertTrue(sms.await() is ToolResult.Done)
    }

    @Test
    fun `a result lists the text first, then the images`() = runTest {
        val result = runner().run(ToolCall("1", "read_sms")) as ToolResult.Done
        val content = result.content
        assertEquals("read_sms done", (content[0] as ToolContent.Text).text)
        assertEquals("image/jpeg", (content[1] as ToolOutput.Image).mimeType)
    }

    @Test
    fun `a call that waited past its budget doesn't run`() = runTest {
        val runner = runner()
        runner.submit(ToolCall("1", "tap"))
        val late = runner.submit(ToolCall("2", "scroll", timeoutMs = 2_500))
        advanceUntilIdle()
        val result = late.await() as ToolResult.Failed
        assertEquals(ToolResult.Code.EXECUTION_ERROR, result.code)
        assertFalse("start scroll" in events)
    }

    @Test
    fun `a call that runs past its budget times out`() = runTest {
        // A budget of 4 s, less the 2 s margin, for a tool that takes 5 s
        val result = runner().submit(ToolCall("1", "wait", timeoutMs = 4_000)).also { advanceUntilIdle() }.await()
        assertEquals(ToolResult.Code.TIMED_OUT, (result as ToolResult.Failed).code)
    }

    @Test
    fun `screen calls wait while the user is asked, and a no ends the call`() = runTest {
        val host = FakeHost()
        val runner = runner(host)
        val pay = runner.submit(ToolCall("1", "pay"))
        val tap = runner.submit(ToolCall("2", "tap"))
        val sms = runner.submit(ToolCall("3", "read_sms"))
        // The user takes 10 s to answer
        advanceTimeBy(10_000)
        assertEquals(listOf("Tap \"Pay\""), host.asked)
        assertEquals(listOf("start read_sms", "end read_sms"), events)
        host.answer.complete(Consent.Answer.DENIED)
        advanceUntilIdle()
        assertEquals(ToolResult.Code.DENIED, (pay.await() as ToolResult.Failed).code)
        assertTrue(tap.await() is ToolResult.Done)
        assertTrue(sms.await() is ToolResult.Done)
        assertFalse("start pay" in events)
    }

    @Test
    fun `stop ends the running call and the queued ones`() = runTest {
        val runner = runner()
        val tap = runner.submit(ToolCall("1", "tap"))
        val scroll = runner.submit(ToolCall("2", "scroll"))
        runCurrent()
        runner.stopAll()
        advanceUntilIdle()
        assertEquals(ToolResult.Code.STOPPED, (tap.await() as ToolResult.Failed).code)
        assertEquals(ToolResult.Code.STOPPED, (scroll.await() as ToolResult.Failed).code)
        assertEquals(listOf("start tap"), events)
        // The queue takes calls again after a stop
        assertTrue(runner.submit(ToolCall("3", "tap")).also { advanceUntilIdle() }.await() is ToolResult.Done)
    }

    @Test
    fun `stopping one agent ends its running and queued calls and leaves the others`() = runTest {
        val host = FakeHost()
        val runner = runner(host)
        val tap = runner.submit(ToolCall("1", "tap", agent = "Codex", agentId = "codex"))
        val scroll = runner.submit(ToolCall("2", "scroll", agent = "Codex", agentId = "codex"))
        val other = runner.submit(ToolCall("3", "wait", agent = "Claude", agentId = "claude"))
        runCurrent()
        runner.stopAgent("codex", "Paused")
        advanceUntilIdle()
        assertEquals("Paused", (tap.await() as ToolResult.Failed).message)
        assertEquals("Paused", (scroll.await() as ToolResult.Failed).message)
        assertTrue(other.await() is ToolResult.Done)
        assertEquals(listOf("start tap", "start wait", "end wait"), events)
        assertEquals("Codex tap failed: Stopped", host.recorded.first())
    }

    @Test
    fun `cancelling a queued call skips it`() = runTest {
        val runner = runner()
        runner.submit(ToolCall("1", "tap"))
        runner.submit(ToolCall("2", "scroll")).cancel()
        advanceUntilIdle()
        assertEquals(listOf("start tap", "end tap"), events)
    }

    @Test
    fun `calls are refused while off, and unknown tools fail`() = runTest {
        val host = FakeHost().apply { enabled = false }
        val runner = runner(host)
        assertEquals(ToolResult.Code.OFF, (runner.run(ToolCall("1", "tap")) as ToolResult.Failed).code)
        host.enabled = true
        assertEquals(ToolResult.Code.UNKNOWN_TOOL, (runner.run(ToolCall("2", "fly")) as ToolResult.Failed).code)
    }
}
