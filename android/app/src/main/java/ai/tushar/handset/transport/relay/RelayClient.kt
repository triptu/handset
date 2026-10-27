package ai.tushar.handset.transport.relay

import ai.tushar.handset.device.Consent
import ai.tushar.handset.Handset
import ai.tushar.handset.transport.mcp.mcpHandler
import ai.tushar.handset.transport.mcp.serveHttp
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.TimeUnit
import kotlin.random.Random

sealed interface RelayState {
    data object Off : RelayState
    data object Connecting : RelayState
    data object Connected : RelayState
    data class Offline(val reason: String) : RelayState
}

/** One phone identity, serviced by the existing foreground service and its wake/Wi-Fi locks. */
class RelayClient(private val context: Context, private val handset: Handset) {
    private val prefs = context.getSharedPreferences("relay", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, _ ->
        // Never include a frame or exception message in logs: either can carry phone content.
        Log.w(TAG, "Relay operation failed")
    })
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val _state = MutableStateFlow<RelayState>(RelayState.Off)
    val state: StateFlow<RelayState> = _state
    private val _publicMcpUrl = MutableStateFlow(prefs.getString("origin", null)?.let { "$it/mcp" })
    val publicMcpUrl: StateFlow<String?> = _publicMcpUrl
    private val _phoneId = MutableStateFlow(prefs.getString("phoneId", null))
    val phoneId: StateFlow<String?> = _phoneId
    val paired: Boolean get() = prefs.contains("origin")
    val enabled: Boolean get() = paired && prefs.getBoolean("enabled", true)

    fun setEnabled(on: Boolean) {
        check(prefs.edit().putBoolean("enabled", on).commit())
        if (!on) stop()
    }
    private val networkEvents = Channel<Unit>(Channel.CONFLATED)
    private var failures = 0
    private var connection: Job? = null
    private var socket: WebSocket? = null
    private val calls = mutableMapOf<String, Job>()
    private val executing = mutableSetOf<String>()
    private val callGrants = mutableMapOf<String, String>()
    private val replies = mutableMapOf<String, String>()
    private val prompts = mutableMapOf<String, Job>()
    private val handler = handset.mcpHandler { grant ->
        if (grant in tombstones()) null else handset.mcp.agents.all.value.firstOrNull { it.relayGrantId == grant }
    }

    init {
        handset.mcp.agents.beforeRevoke = { grant ->
            tombstone(grant)
            scope.launch {
                callGrants.filterValues { it == grant }.keys.toList().forEach { calls[it]?.cancel() }
                sendRevoke(grant)
            }
        }
    }

    /** Call from the UI with the pasted/scanned HTTPS origin. No network starts until this call. */
    fun pair(url: String) {
        val origin = relayOrigin(url)
        if (prefs.getString("origin", null) != origin && paired) unpair()
        val identity = RelayIdentity()
        check(prefs.edit().putString("origin", origin).putString("phoneId", identity.phoneId).putBoolean("enabled", true).commit())
        _phoneId.value = identity.phoneId
        _publicMcpUrl.value = "$origin/mcp"
        handset.mcp.resume()
    }

    fun unpair() {
        // Revoke locally before dropping the socket. Tombstones survive until this origin acknowledges them.
        handset.mcp.agents.all.value.filter { it.relayGrantId != null }.forEach(handset.mcp.agents::remove)
        prefs.edit().remove("origin").remove("phoneId").commit()
        stop()
        _publicMcpUrl.value = null
        _phoneId.value = null
        handset.mcp.resume()
    }

    /** Only McpService starts/stops the connection. A network change interrupts backoff and stale sockets. */
    fun start() {
        if (!enabled || connection?.isActive == true) return
        connection = scope.launch { connectLoop() }
    }
    fun networkChanged() {
        if (!enabled) return
        socket?.cancel()
        networkEvents.trySend(Unit)
        start()
    }
    fun stop() {
        connection?.cancel(); connection = null
        socket?.cancel(); socket = null
        prompts.values.toList().forEach { it.cancel() }; prompts.clear()
        calls.values.toList().forEach { it.cancel() }; calls.clear(); executing.clear(); callGrants.clear(); replies.clear()
        _state.value = if (paired) RelayState.Offline("Agent service stopped") else RelayState.Off
    }

    private suspend fun connectLoop() {
        while (currentCoroutineContext().isActive && enabled) {
            _state.value = RelayState.Connecting
            try {
                session()
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                _state.value = RelayState.Offline("Relay handshake timed out; retrying")
                Log.i(TAG, "handshake timed out; reconnecting")
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                _state.value = RelayState.Offline("Relay connection lost; retrying")
                Log.i(TAG, "offline; reconnecting")
            } finally {
                socket?.cancel(); socket = null
                prompts.values.toList().forEach { it.cancel() }; prompts.clear()
            }
            _state.value = RelayState.Offline("Relay connection lost; retrying")
            val backoff = ((1_000L shl failures.coerceAtMost(6)).coerceAtMost(60_000) * Random.nextDouble(0.75, 1.0)).toLong().coerceAtLeast(1_000)
            withTimeoutOrNull(backoff) { networkEvents.receive() }
            failures++
        }
    }

    private suspend fun session() = coroutineScope {
        val origin = prefs.getString("origin", null) ?: return@coroutineScope
        val identity = withContext(Dispatchers.IO) { RelayIdentity() }
        val incoming = Channel<String?>(64)
        val ws = http.newWebSocket(Request.Builder().url(origin.replaceFirst("http", "ws") + "/phone/${identity.phoneId}").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.toByteArray().size > 1_048_576 || !incoming.trySend(text).isSuccess) webSocket.cancel()
            }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) { webSocket.close(1008, "Text frames required") }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { incoming.trySend(null); incoming.close() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { incoming.trySend(null); incoming.close() }
        })
        try {
            val hello = withTimeout(15_000) {
                val challenge = Json.parseToJsonElement(incoming.receive() ?: error("disconnected")).jsonObject
                require(challenge.text("type") == "challenge" && challenge.text("origin") == origin && challenge.text("phoneId") == identity.phoneId)
                val signature = withContext(Dispatchers.IO) { identity.sign(origin, challenge.text("nonce")) }
                ws.send(frame("hello") { put("version", 1); put("publicKey", base64url(identity.publicKey)); put("signature", signature) })
                Json.parseToJsonElement(incoming.receive() ?: error("disconnected")).jsonObject.also { require(it.text("type") == "hello" && it["version"]?.jsonPrimitive?.int == 1) }
            }
            socket = ws
            failures = 0
            _state.value = RelayState.Connected
            Log.i(TAG, "connected")
            reconcile(hello)
            replies.values.forEach(ws::send)
            var waitingPong = false
            val heartbeat = launch {
                while (isActive) {
                    delay(60_000)
                    waitingPong = true
                    ws.send("{\"type\":\"ping\"}")
                    delay(15_000)
                    if (waitingPong) { ws.cancel(); break }
                    if (handset.mcp.agents.all.value.any { agent ->
                        agent.relayGrantId?.let { grant -> prefs.getLong("provisional:$grant", Long.MAX_VALUE) <= System.currentTimeMillis() } == true
                    }) { ws.cancel(); break }
                }
            }
            try {
                for (raw in incoming) {
                    if (raw == null) break
                    val message = Json.parseToJsonElement(raw).jsonObject
                    when (message.text("type")) {
                        "pong" -> waitingPong = false
                        "request" -> request(message)
                        "cancel" -> { val id = message.text("id"); calls[id]?.cancel(); replies.remove(id) }
                        "approval_request" -> prompt(message)
                        "revoked" -> forgetTombstone(message.text("grantId"))
                        else -> error("Unknown relay frame")
                    }
                }
            } finally { heartbeat.cancel() }
        } finally { ws.cancel(); incoming.cancel() }
    }

    private fun request(message: JsonObject) {
        val id = message.text("id")
        if (id in calls) return // Never execute a frame twice, including across reconnects.
        if (executing.size >= 32) {
            socket?.send(relayBusyResponse(message))
            return
        }
        val grant = message.text("grantId")
        executing.add(id)
        callGrants[id] = grant
        calls[id] = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(120_000) {
                    val body = message.text("body")
                    val headers = relayHeaders(body, message["headers"] as? JsonObject ?: JsonObject(emptyMap()), grant)
                    require(body.toByteArray().size <= 262_144)
                    val reply = try {
                        serveHttp(message.text("method"), "/mcp", headers, { body }, handler, loopback = false)
                    } finally { executing.remove(id) }
                    val response = frame("response") {
                        put("id", id); put("status", reply.status)
                        put("json", reply.json?.let(Json::parseToJsonElement) ?: JsonNull)
                    }
                    val bounded = if (response.toByteArray().size <= 1_048_576) response else frame("response") {
                        put("id", id); put("status", 413); put("json", JsonNull)
                    }
                    replies[id] = bounded
                    socket?.send(bounded)
                    // Keep completed answers until the relay's original deadline, to resend after a disconnect.
                    delay(120_000)
                }
            } finally { calls.remove(id); executing.remove(id); callGrants.remove(id); replies.remove(id) }
        }
        calls.getValue(id).start()
    }

    private fun prompt(message: JsonObject) {
        val id = message.text("id")
        if (id in prompts) return
        if (prompts.isNotEmpty()) {
            denyApproval(id, "Another approval is already pending on the phone")
            return
        }
        val expires = message["expiresAt"]!!.jsonPrimitive.long
        if (expires <= System.currentTimeMillis()) {
            denyApproval(id, "Approval expired")
            return
        }
        prompts[id] = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val host = message.text("redirectHost")
                val name = relayClientName(message.text("clientName"))
                val detail = "Compare code: ${message.text("code")}\n\n$host\n${message.text("prompt")}\n\nClient: $name\n${message.text("clientId")}\nScopes: ${message["scopes"]!!.jsonArray.joinToString { it.jsonPrimitive.content }}"
                val answer = handset.consent.ask(detail, title = "Allow a new agent?", expiresAt = expires) { show ->
                    handset.runner.withScreenPaused(show)
                }
                val grant = message.text("grantId")
                val allow = answer == Consent.Answer.ALLOWED && System.currentTimeMillis() < expires && grant !in tombstones()
                if (allow) {
                    check(prefs.edit().putLong("provisional:$grant", expires).commit())
                    handset.mcp.agents.addRelay(name, grant, host, message.text("clientId"))
                }
                if (allow) socket?.send(frame("approval_answer") { put("id", id); put("allow", true) })
                else denyApproval(id, when (answer) {
                    Consent.Answer.TIMED_OUT -> "Approval timed out on the phone"
                    Consent.Answer.CANNOT_ASK -> "Phone notifications are off"
                    else -> "Approval denied on the phone"
                })
            } finally { prompts.remove(id) }
        }
        prompts.getValue(id).start()
    }

    private fun denyApproval(id: String, reason: String) {
        socket?.send(frame("approval_answer") { put("id", id); put("allow", false); put("reason", reason) })
    }

    private fun reconcile(hello: JsonObject) {
        val active = hello["grants"]!!.jsonArray.map { it.jsonObject.text("grantId") }.toSet()
        val known = handset.mcp.agents.all.value.mapNotNull { it.relayGrantId }.toSet()
        (active - known).forEach(::tombstone)
        handset.mcp.agents.all.value.filter { agent ->
            val grant = agent.relayGrantId
            grant != null && grant !in active && prefs.getLong("provisional:$grant", 0) <= System.currentTimeMillis()
        }.forEach(handset.mcp.agents::remove)
        active.forEach { prefs.edit().remove("provisional:$it").commit() }
        tombstones().forEach(::sendRevoke)
    }
    private fun revocationKey(): String = "revoked:${prefs.getString("origin", "")}"
    @Synchronized private fun tombstones(): Set<String> = prefs.getStringSet(revocationKey(), emptySet())!!.toSet()
    @Synchronized private fun tombstone(grant: String) { check(prefs.edit().putStringSet(revocationKey(), tombstones() + grant).commit()) }
    @Synchronized private fun forgetTombstone(grant: String) { check(prefs.edit().putStringSet(revocationKey(), tombstones() - grant).commit()) }
    private fun sendRevoke(grant: String) { socket?.send(frame("revoke") { put("grantId", grant) }) }
    private companion object {
        const val TAG = "Handset/Relay"
    }
}

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
private fun frame(type: String, body: JsonObjectBuilder.() -> Unit = {}): String = buildJsonObject { put("type", type); body() }.toString()
