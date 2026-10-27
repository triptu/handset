package ai.tushar.handset.transport.mcp

import ai.tushar.handset.runner.ToolCall
import ai.tushar.handset.runner.ToolResult
import ai.tushar.handset.tools.PhoneTool
import ai.tushar.handset.tools.ToolOutput
import ai.tushar.handset.tools.objectSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpHandlerTest {
    private val token = Agents.newToken()
    private val agent = Agent("1", "Claude Code", Agents.hash(token), sources = setOf(Source.CONTACTS))
    private val otherToken = Agents.newToken()
    private val other = Agent("2", "Codex", Agents.hash(otherToken))
    private val calls = mutableListOf<ToolCall>()
    private val history = mutableListOf<String>()
    private val waiting = CompletableDeferred<Unit>()
    private val waitEnded = CompletableDeferred<Throwable>()

    private val tools = listOf(
        PhoneTool("get_screen", "Reads the screen", objectSchema()) { ToolOutput("App: Clock") },
        PhoneTool("screenshot", "A picture of the screen", objectSchema()) { ToolOutput("Screenshot", listOf(ToolOutput.Image("AQID", "image/jpeg"))) },
        PhoneTool("tap", "Taps", objectSchema()) { ToolOutput("[ok] Tapped") },
        PhoneTool("read_sms", "Reads texts", objectSchema()) { ToolOutput("a text") },
        PhoneTool("find_contact", "Finds a contact", objectSchema()) { ToolOutput("no contact") },
        PhoneTool("wait_for", "Waits", objectSchema()) { ToolOutput("never") },
        PhoneTool("now_playing", "Media playing", objectSchema()) { ToolOutput("Spotify: playing") },
        PhoneTool("search_context", "Searches", objectSchema()) { args -> ToolOutput("without notifications: ${args.boolean("without_notifications")}") },
        PhoneTool("day_timeline", "A day", objectSchema()) { args -> ToolOutput("without location: ${args.boolean("without_location")}, caller: ${args.string("caller")}") },
        PhoneTool("not_for_mcp", "Hidden", objectSchema()) { ToolOutput("hidden") },
    )

    private val handler = McpHandler(
        tools = tools,
        agentFor = { Agents.find(listOf(agent, other), it) },
        run = { call ->
            calls += call
            when (call.tool) {
                "wait_for" -> try {
                    waiting.complete(Unit)
                    awaitCancellation()
                } catch (ended: CancellationException) {
                    waitEnded.complete(ended)
                    throw ended
                }
                "find_contact" -> ToolResult.Failed(call.id, ToolResult.Code.EXECUTION_ERROR, "Contacts access is off. Ask the user to turn on Contacts in Handset's Settings.")
                else -> ToolResult.Done(call.id, tools.first { it.slug == call.tool }.run(ai.tushar.handset.tools.ToolArgs(call.params)), 5)
            }
        },
        instructions = "Operate the phone.",
        version = "test",
        refused = { call, detail -> history += "${call.agent} ${call.tool}: $detail" },
    )

    private val auth = mapOf("Authorization" to "Bearer $token")

    private fun send(body: String, headers: Map<String, String> = auth) = runBlocking { handler.handle(body, headers) }

    private fun result(body: String, headers: Map<String, String> = auth): JsonObject {
        val reply = send(body, headers)
        assertEquals(reply.json, 200, reply.status)
        return Json.parseToJsonElement(reply.json!!).jsonObject["result"]!!.jsonObject
    }

    private fun call(name: String) = result("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"$name","arguments":{}}}""")

    private fun JsonObject.text() = this["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    @Test fun discoverSpeaksTheNewEraWithoutASession() {
        val discovered = result(
            """{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}""",
            auth + mapOf("MCP-Protocol-Version" to "2026-07-28", "Mcp-Method" to "server/discover"),
        )
        assertTrue(discovered.toString(), "2026-07-28" in discovered["supportedVersions"].toString())
        assertEquals("Operate the phone.", discovered["instructions"]!!.jsonPrimitive.content)
        assertEquals("complete", discovered["resultType"]!!.jsonPrimitive.content)
    }

    private val modern = "2026-07-28"
    private val legacyMeta = """"_meta":{"io.modelcontextprotocol/protocolVersion":"2025-06-18"}"""
    private val modernMeta = """"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}"""
    private fun tapBody(meta: String) = """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"tap","arguments":{},$meta}}"""
    private fun httpSend(body: String, headers: Map<String, String>) = runBlocking { handler.handle(body, auth + headers, http = true) }
    private fun assertMismatch(reply: McpReply) {
        assertEquals(reply.json, 400, reply.status)
        assertTrue(reply.json!!, reply.json.contains("-32020"))
        assertTrue(calls.isEmpty())
    }

    @Test fun aLegacyBodyVersionCannotSkipModernHeaderChecks() {
        assertMismatch(httpSend(tapBody(legacyMeta), mapOf("MCP-Protocol-Version" to modern)))
        assertMismatch(httpSend(tapBody(modernMeta), mapOf("MCP-Protocol-Version" to "2025-06-18", "Mcp-Method" to "tools/call", "Mcp-Name" to "tap")))
    }

    @Test fun aModernCallNeedsItsMethodAndNameHeaders() {
        val version = mapOf("MCP-Protocol-Version" to modern)
        assertMismatch(httpSend(tapBody(modernMeta), emptyMap()))
        assertMismatch(httpSend(tapBody(modernMeta), version))
        assertMismatch(httpSend(tapBody(modernMeta), version + mapOf("Mcp-Method" to "tools/list", "Mcp-Name" to "tap")))
        assertMismatch(httpSend(tapBody(modernMeta), version + mapOf("Mcp-Method" to "tools/call")))
        assertMismatch(httpSend(tapBody(modernMeta), version + mapOf("Mcp-Method" to "tools/call", "Mcp-Name" to "screenshot")))
        assertMismatch(httpSend(tapBody(modernMeta), version + mapOf("Mcp-Method" to "tools/call", "Mcp-Name" to "=?base64?!!?=")))
        assertMismatch(httpSend("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""", version + mapOf("Mcp-Method" to "initialize")))
    }

    @Test fun aModernCallWithMatchingHeadersRuns() {
        val headers = mapOf("MCP-Protocol-Version" to modern, "Mcp-Method" to "tools/call")
        assertEquals(200, httpSend(tapBody(modernMeta), headers + ("Mcp-Name" to "tap")).status)
        assertEquals(200, httpSend(tapBody(modernMeta), headers + ("Mcp-Name" to "=?base64?dGFw?=")).status)
        assertEquals(2, calls.size)
    }

    @Test fun aRelayFrameWithoutHeadersStillRuns() {
        assertEquals(200, send(tapBody(modernMeta)).status)
        assertEquals("Claude Code", calls.single().agent)
    }

    @Test fun initializeAgreesOnTheClientsVersion() {
        val initialized = result("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{}}}""")
        assertEquals("2025-06-18", initialized["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("handset", initialized["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(202, send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").status)
    }

    @Test fun listsOnlyMcpToolsWithTheirHints() {
        val listed = result("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("get_screen", "screenshot", "tap", "read_sms", "find_contact", "wait_for", "now_playing", "search_context", "day_timeline"), listed.map { it["name"]!!.jsonPrimitive.content })
        val hints = listed.associate { it["name"]!!.jsonPrimitive.content to it["annotations"]!!.jsonObject }
        assertTrue(hints["get_screen"]!!["readOnlyHint"]!!.jsonPrimitive.boolean)
        assertFalse(hints["tap"]!!["readOnlyHint"]!!.jsonPrimitive.boolean)
        assertTrue(hints["tap"]!!["destructiveHint"]!!.jsonPrimitive.boolean)
        assertEquals("object", listed[0]["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun aCallRunsThroughTheRunnerAsTheAgent() {
        val tapped = call("tap")
        assertEquals("[ok] Tapped", tapped.text())
        assertFalse(tapped["isError"]!!.jsonPrimitive.boolean)
        assertEquals("Claude Code", calls.single().agent)
        assertEquals("1", calls.single().agentId)
    }

    @Test fun imagesComeBackAsImageContent() {
        val image = call("screenshot")["content"]!!.jsonArray[1].jsonObject
        assertEquals("image", image["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", image["mimeType"]!!.jsonPrimitive.content)
        assertEquals("AQID", image["data"]!!.jsonPrimitive.content)
    }

    @Test fun aSourceTheAgentLacksIsRefusedBeforeTheRunner() {
        val refused = call("read_sms")
        assertTrue(refused["isError"]!!.jsonPrimitive.boolean)
        assertEquals(
            "Text messages access is off for Claude Code. Ask the user to turn on Text messages for Claude Code in Handset's Settings, under Agents.",
            refused.text(),
        )
        assertTrue(calls.isEmpty())
        assertEquals(listOf("Claude Code read_sms: Text messages access is off for Claude Code."), history)
    }

    @Test fun searchLeavesOutNotificationsForAnAgentWithoutThem() {
        val sent = """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_context","arguments":{"query":"x","without_notifications":false}}}"""
        assertEquals("without notifications: true", result(sent)["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val allowed = Agent("3", "Claude", Agents.hash(token), sources = setOf(Source.NOTIFICATIONS))
        val withNotifications = McpHandler(tools, { allowed }, { ToolResult.Done(it.id, tools.first { t -> t.slug == it.tool }.run(ai.tushar.handset.tools.ToolArgs(it.params)), 1) }, "", "test")
        val reply = runBlocking { withNotifications.handle(sent, auth) }
        assertTrue(reply.json!!, reply.json.contains("without notifications: false"))
    }

    @Test fun theTimelineIsScopedToTheCallingAgent() {
        val sent = """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"day_timeline","arguments":{"without_location":false,"caller":"2"}}}"""
        // The caller is the agent's id, which no other agent shares, and not what the agent sent
        assertEquals("without location: true, caller: 1", result(sent).text())
        val allowed = Agent("3", "Claude", Agents.hash(token), sources = setOf(Source.LOCATION))
        val withLocation = McpHandler(tools, { allowed }, { ToolResult.Done(it.id, tools.first { t -> t.slug == it.tool }.run(ai.tushar.handset.tools.ToolArgs(it.params)), 1) }, "", "test")
        assertTrue(runBlocking { withLocation.handle(sent, auth) }.json!!.contains("without location: false"))
    }

    @Test fun nowPlayingNeedsNotifications() {
        assertTrue(call("now_playing")["isError"]!!.jsonPrimitive.boolean)
        assertTrue(calls.isEmpty())
    }

    @Test fun aRunnerFailureIsAToolErrorWithItsMessage() {
        val failed = call("find_contact")
        assertTrue(failed["isError"]!!.jsonPrimitive.boolean)
        assertTrue(failed.text().startsWith("Contacts access is off"))
    }

    private fun cancel(id: Int) = """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":$id,"reason":"user"}}"""

    @Test fun anAgentCancelsItsOwnCallAndNoOneElses() = runBlocking {
        val running = async(Dispatchers.Default) {
            handler.handle("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"wait_for","arguments":{}}}""", auth)
        }
        withTimeout(2_000) { waiting.await() }
        assertEquals(202, handler.handle(cancel(7), mapOf("Authorization" to "Bearer $otherToken")).status)
        delay(200)
        assertTrue("another agent's cancel ended the call", running.isActive)
        assertEquals(202, handler.handle(cancel(7), auth).status)
        val reply = withTimeout(2_000) { running.await() }
        assertTrue(reply.json!!, reply.json.contains("cancelled"))
        assertTrue(waitEnded.isCompleted)
    }

    @Test fun aBadTokenIsUnauthorized() {
        val body = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
        assertEquals(401, send(body, mapOf("Authorization" to "Bearer ${Agents.newToken()}")).status)
        assertEquals(401, send(body, emptyMap()).status)
        assertEquals(401, send(body, mapOf("authorization" to token)).status)
    }

    @Test fun aRevokedTokenIsUnauthorized() {
        val revoked = McpHandler(tools, { Agents.find(emptyList(), it) }, { error("never runs") }, "", "test")
        assertEquals(401, runBlocking { revoked.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", auth) }.status)
    }

    @Test fun aWebPageIsForbiddenEvenWithTheToken() {
        val reply = send("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"tap"}}""", auth + mapOf("Origin" to "https://evil.example"))
        assertEquals(403, reply.status)
        assertTrue(calls.isEmpty())
    }

    @Test fun unknownToolIsAProtocolError() {
        val reply = send("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"not_for_mcp"}}""")
        assertTrue(reply.json!!, reply.json.contains("-32602"))
        assertNull(calls.firstOrNull())
    }

    @Test fun httpAllowsOnlyPostOnMcp() = runBlocking {
        assertEquals(405, serveHttp("GET", "/mcp", auth, { "" }, handler).status)
        assertEquals(405, serveHttp("DELETE", "/mcp", auth, { "" }, handler).status)
        assertEquals(404, serveHttp("POST", "/other", auth, { "" }, handler).status)
        val unauthorized = serveHttp("POST", "/mcp", emptyMap(), { "{}" }, handler)
        assertEquals("Bearer", unauthorized.headers["WWW-Authenticate"])
    }
}
