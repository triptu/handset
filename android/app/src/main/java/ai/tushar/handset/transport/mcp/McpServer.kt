package ai.tushar.handset.transport.mcp

import ai.tushar.handset.BuildConfig
import ai.tushar.handset.Handset
import ai.tushar.handset.tools.Toolkit
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The MCP server's switch and the agents that may use it. The server is off until the user turns it on, and comes back
 * after a restart when it was on. [McpService] runs it.
 */
class McpServer(private val context: Context) {
    private val preferences = context.getSharedPreferences("mcp", Context.MODE_PRIVATE)
    val agents = Agents(preferences)

    private val _on = MutableStateFlow(preferences.getBoolean(ON, false))
    val on: StateFlow<Boolean> get() = _on

    private val _wifi = MutableStateFlow(preferences.getBoolean(WIFI, false))

    /** Whether the server also listens on Wi-Fi. Off by default, since anyone on the Wi-Fi could read its plain HTTP. */
    val wifi: StateFlow<Boolean> get() = _wifi

    /** Why the server isn't listening, as a port another app holds, or null */
    val problem = MutableStateFlow<String?>(null)

    /** True while Handset's own socket holds the loopback port, so what reaches 127.0.0.1 there reaches Handset */
    val listeningLocally = MutableStateFlow(false)

    /** Where agents on other devices reach the server now, kept by [McpService] as networks come and go */
    val addresses = MutableStateFlow<List<Address>>(emptyList())

    fun shouldRun(localAvailable: Boolean = true): Boolean = (_on.value && localAvailable) || Handset.instance.relay.enabled

    fun switch(on: Boolean) {
        preferences.edit().putBoolean(ON, on).apply()
        _on.value = on
        resume()
    }

    fun allowWifi(on: Boolean) {
        preferences.edit().putBoolean(WIFI, on).apply()
        _wifi.value = on
        // A running service listens again on the addresses this allows
        if (shouldRun()) start()
    }

    /**
     * Turns the server on and waits up to [timeoutMs] until Handset holds the loopback port. False when another app holds
     * it or the server didn't come up in time; the server is then off again unless it was on before.
     */
    suspend fun awaitLocal(timeoutMs: Long): Boolean {
        if (listeningLocally.value) return true
        val wasOn = _on.value
        problem.value = null
        if (wasOn) start() else switch(true)
        val bound = awaitBound(listeningLocally, problem, timeoutMs)
        if (!bound && !wasOn) switch(false)
        return bound
    }

    /** Starts the service when the user left the server on, as after a reboot */
    fun resume() {
        if (shouldRun()) start() else context.stopService(Intent(context, McpService::class.java))
    }

    private fun start() = context.startForegroundService(Intent(context, McpService::class.java))

    companion object {
        const val PORT = 8765
        private const val ON = "server_on"
        private const val WIFI = "server_on_wifi"
    }
}

/** Waits until [listening], true, or [problem] says the port is someone else's, false, or [timeoutMs] passes, false */
suspend fun awaitBound(listening: StateFlow<Boolean>, problem: StateFlow<String?>, timeoutMs: Long): Boolean =
    withTimeoutOrNull(timeoutMs) { combine(listening, problem) { up, why -> if (up) true else if (why != null) false else null }.filterNotNull().first() } ?: false

/** The handler every MCP transport shares: the socket server here, and the relay */
fun Handset.mcpHandler(agentFor: (String) -> Agent? = mcp.agents::find) = McpHandler(
    tools = tools.all,
    agentFor = agentFor,
    run = runner::run,
    instructions = "${Toolkit.INSTRUCTIONS} ${McpHandler.DATA_NOT_INSTRUCTIONS}",
    version = BuildConfig.VERSION_NAME,
    refused = { call, detail -> recordAgentAction(call, detail, ok = false) },
)
