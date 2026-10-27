package ai.tushar.handset.runner

import ai.tushar.handset.device.Consent
import ai.tushar.handset.tools.Approval
import ai.tushar.handset.tools.CallLabels
import ai.tushar.handset.tools.PhoneTool
import ai.tushar.handset.tools.ToolArgs
import ai.tushar.handset.tools.ToolFailure
import ai.tushar.handset.runner.ToolResult.Code
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs tool calls for every transport: adb today, and later an MCP server and the in-app chat.
 *
 * Calls that use the screen run one at a time, in the order they arrive, so actions an agent sends in one turn don't
 * race. That also means no other screen call runs while the user is asked to allow one, so an agent can't open the
 * notification shade and tap Allow itself. Calls whose tool is `concurrent`, as reading contacts, run at once beside
 * them. Time a call spends queued counts against its budget.
 */
class ToolRunner(
    val tools: List<PhoneTool>,
    private val host: Host,
    private val clock: () -> Long = SystemClock::uptimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** What the runner needs from the app around it */
    interface Host {
        /** Whether the user lets agents use the phone. Off refuses every call. */
        val enabled: Boolean

        /** Whether a call with a consent prompt, as a tap on Send, waits for the user's Allow */
        val askBeforeSending: Boolean

        suspend fun ask(prompt: String): Consent.Answer

        /** The screen as the agent reads it, for an error that says what changed */
        suspend fun currentScreen(): String

        /**
         * Adds a step to the record of what agents did: each call that reached a tool, once, with how it ended, and
         * each one refused or stopped on the way. [detail] is its first line.
         */
        fun record(call: ToolCall, detail: String, ok: Boolean)

        /** Says what the call does, in the pill at the top of the screen */
        fun showStatus(label: String, agent: String?)

        fun clearStatus()
    }

    private class Pending(val call: ToolCall, val tool: PhoneTool, val receivedAt: Long) {
        val result = CompletableDeferred<ToolResult>()
        @Volatile var job: Job? = null

        fun stopped() = result.complete(ToolResult.Failed(call.id, Code.STOPPED, "Handset was stopped on the phone, so the call ended"))
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** The screen calls waiting their turn. Replaced when [stopAll] ends the old queue. */
    private var queue = startQueue()

    private val screen = Mutex()

    /** Holds screen calls while a native pairing approval is visible, across every transport. */
    suspend fun <T> withScreenPaused(block: suspend () -> T): T = screen.withLock { block() }

    /** Every call taken and not yet ended, so [stopAgent] can find one agent's */
    private val live = ConcurrentHashMap.newKeySet<Pending>()

    /** How many calls are running, as reads run beside an action */
    private val running = AtomicInteger()

    fun tool(name: String): PhoneTool? = tools.firstOrNull { it.slug == name }

    /**
     * Takes [call] and returns at once. Calls submitted one after another keep that order. The result completes when the
     * call ends, whatever the outcome; cancelling it cancels the call.
     */
    fun submit(call: ToolCall): Deferred<ToolResult> {
        if (!host.enabled) return CompletableDeferred(ToolResult.Failed(call.id, Code.OFF, "Handset is turned off on the phone. Ask the user to turn it on."))
        val tool = tool(call.tool) ?: return CompletableDeferred(ToolResult.Failed(call.id, Code.UNKNOWN_TOOL, "This phone has no tool ${call.tool}"))
        val pending = Pending(call, tool, clock())
        live += pending
        pending.result.invokeOnCompletion { cause ->
            live -= pending
            if (cause != null) pending.job?.cancel()
        }
        synchronized(this) {
            if (tool.concurrent) track(pending, scope.launch { handle(pending) }) else queue.trySend(pending)
        }
        return pending.result
    }

    /** Runs [call] and waits for how it ended. Cancelling the caller cancels the call. */
    suspend fun run(call: ToolCall): ToolResult {
        val result = submit(call)
        return try {
            result.await()
        } catch (cancelled: CancellationException) {
            result.cancel(cancelled)
            throw cancelled
        }
    }

    /** Ends every running and queued call at once, as Stop does. Each ends as [Code.STOPPED]. */
    fun stopAll() = synchronized(this) {
        queue.cancel()
        scope.coroutineContext.cancelChildren()
        queue = startQueue()
    }

    /** Ends the running and queued calls of the agent with [agentId] at once, each with [message] */
    fun stopAgent(agentId: String, message: String) = live.filter { it.call.agentId == agentId }.forEach { pending ->
        pending.result.complete(ToolResult.Failed(pending.call.id, Code.STOPPED, message))
        pending.job?.cancel()
    }

    private fun startQueue(): Channel<Pending> {
        val channel = Channel<Pending>(Channel.UNLIMITED, onUndeliveredElement = { it.stopped() })
        scope.launch {
            for (pending in channel) {
                // Cancelled by its caller while it waited
                if (pending.result.isCompleted) continue
                track(pending, launch { handle(pending) }).join()
            }
        }
        return channel
    }

    /** Ties [pending] to the [job] that runs it, so cancelling the result ends the job, and a job that ends early ends the result */
    private fun track(pending: Pending, job: Job): Job {
        pending.job = job
        job.invokeOnCompletion { pending.stopped() }
        if (pending.result.isCancelled) job.cancel()
        return job
    }

    private suspend fun handle(pending: Pending) {
        val call = pending.call
        val tool = pending.tool
        val args = ToolArgs(call.params)
        val queuedMs = clock() - pending.receivedAt
        val budgetMs = call.timeoutMs - TIMEOUT_MARGIN_MS - queuedMs
        if (budgetMs < MIN_BUDGET_MS) {
            host.record(call, "Skipped: it waited too long behind other steps", ok = false)
            pending.result.complete(
                ToolResult.Failed(call.id, Code.EXECUTION_ERROR, "This call waited ${queuedMs / 1000} s behind earlier calls and ran out of time, so it did not run."),
            )
            return
        }
        val result = try {
            withTimeout(budgetMs) {
                if (tool.concurrent) approveAndExecute(call, tool, args)
                else screen.withLock { approveAndExecute(call, tool, args) }
            }
        } catch (_: TimeoutCancellationException) {
            host.record(call, "Timed out", ok = false)
            ToolResult.Failed(call.id, Code.TIMED_OUT, "This call ran out of time after ${budgetMs / 1000} s")
        } catch (cancelled: CancellationException) {
            host.record(call, "Stopped", ok = false)
            throw cancelled
        } catch (error: Exception) {
            // A call whose arguments don't fit fails in the approval check, before the tool runs; it must not end the queue
            val message = if (error is ToolFailure) error.message.orEmpty() else error.toString()
            host.record(call, message.substringBefore("\n\n"), ok = false)
            ToolResult.Failed(call.id, Code.EXECUTION_ERROR, message)
        }
        pending.result.complete(result)
    }

    private suspend fun approveAndExecute(call: ToolCall, tool: PhoneTool, args: ToolArgs): ToolResult {
        val id = call.id
        val prompt = if (host.askBeforeSending) tool.consentPrompt(args) else null
        if (prompt == null) return execute(call, tool, args)
        when (host.ask(prompt)) {
            Consent.Answer.ALLOWED -> Unit
            // Declined, so the agent shouldn't retry; or not answered in time
            Consent.Answer.DENIED -> {
                host.record(call, "Declined: $prompt", ok = false)
                return ToolResult.Failed(id, Code.DENIED, "The user declined on the phone: $prompt")
            }
            Consent.Answer.CANNOT_ASK -> {
                host.record(call, "Couldn't ask: $prompt", ok = false)
                return ToolResult.Failed(
                    id,
                    Code.DENIED,
                    "This needs the user's approval, but Handset's notifications are off, so the phone can't ask. " +
                        "Ask the user to turn them on, or to turn off asking before Send and Pay in Handset's Settings, under Safety: $prompt",
                )
            }
            Consent.Answer.TIMED_OUT -> {
                host.record(call, "Not answered: $prompt", ok = false)
                return ToolResult.Failed(id, Code.CONSENT_TIMEOUT, "The user did not answer on the phone: $prompt")
            }
        }
        // The screen can change while the user is asked, and the element id with it
        if (tool.consentPrompt(args) != prompt) {
            val screen = runCatching { host.currentScreen() }.getOrDefault("")
            return ToolResult.Failed(id, Code.EXECUTION_ERROR, "The screen changed while waiting for the user's Allow, so nothing was tapped. The screen now:\n\n$screen")
        }
        // The tap checks that it taps what the user allowed
        return withContext(Approval(prompt)) { execute(call, tool, args) }
    }

    private suspend fun execute(call: ToolCall, tool: PhoneTool, args: ToolArgs): ToolResult {
        val id = call.id
        val started = clock()
        // A read that runs beside an action leaves the pill saying what the action does
        if (running.getAndIncrement() == 0 || !tool.concurrent) host.showStatus(CallLabels.describe(tool.slug, args), call.agent)
        try {
            val output = tool.run(args)
            // The status and summary lines, without the screen that follows them
            host.record(call, output.text.substringBefore("\n\n"), ok = true)
            return ToolResult.Done(id, output, clock() - started)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A ToolFailure's message is written for the agent; anything else keeps its type
            val message = if (error is ToolFailure) error.message.orEmpty() else error.toString()
            host.record(call, message.substringBefore("\n\n"), ok = false)
            return ToolResult.Failed(id, Code.EXECUTION_ERROR, message)
        } finally {
            // A read that runs beside an action ends first; the pill stays up for the action
            if (running.decrementAndGet() == 0) host.clearStatus()
        }
    }

    private companion object {
        /** Kept back from a call's budget, so its result reaches the caller before the caller gives up */
        const val TIMEOUT_MARGIN_MS = 2_000L
        const val MIN_BUDGET_MS = 1_000L
    }
}
