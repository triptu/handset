package ai.tushar.handset.runner

import ai.tushar.handset.tools.ToolContent
import ai.tushar.handset.tools.ToolOutput
import kotlinx.serialization.json.JsonObject

/**
 * A call to one tool, from any transport. [id] is the transport's own id for it, used in logs. [timeoutMs] is the
 * caller's budget, counted from when the runner takes the call, so time spent queued behind other calls counts.
 * [agent] is the name the user gave the agent that sent it, for the history and the pill, or null when the transport
 * doesn't know, as adb. [agentId] is that agent's own id, kept with each step so agents that share a name stay apart.
 */
class ToolCall(
    val id: String,
    val tool: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val agent: String? = null,
    val agentId: String? = null,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }
}

/** How a call ended. Every call submitted to the runner ends in exactly one of these. */
sealed interface ToolResult {
    val callId: String

    /** The tool ran. [runMs] is how long it ran, without the time it waited. */
    class Done(override val callId: String, val output: ToolOutput, val runMs: Long) : ToolResult {
        /** The text first, then the images, as an MCP result lists them */
        val content: List<ToolContent> get() = output.content
    }

    /** The call didn't run, or failed. [message] is written for the agent. */
    class Failed(override val callId: String, val code: Code, val message: String) : ToolResult

    enum class Code {
        /** No tool has that name */
        UNKNOWN_TOOL,

        /** The tool failed, or the call couldn't run, as when it waited too long behind others */
        EXECUTION_ERROR,

        /** The user said no on the phone, or the phone can't ask; the agent shouldn't retry */
        DENIED,

        /** The user didn't answer the prompt in time */
        CONSENT_TIMEOUT,

        /** The call ran out of its budget */
        TIMED_OUT,

        /** The user has turned Handset off */
        OFF,

        /** Stop ended the call, or its caller cancelled it */
        STOPPED,
    }
}
