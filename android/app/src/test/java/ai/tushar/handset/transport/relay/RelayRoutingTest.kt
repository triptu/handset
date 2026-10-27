package ai.tushar.handset.transport.relay

import ai.tushar.handset.runner.ToolResult
import ai.tushar.handset.tools.PhoneTool
import ai.tushar.handset.tools.ToolOutput
import ai.tushar.handset.tools.objectSchema
import ai.tushar.handset.transport.mcp.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RelayRoutingTest {
    @Test fun relayHeadersBindTheGrantAndSupportModernRouting() = runBlocking {
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"device_status"}}"""
        val headers = relayHeaders(body, buildJsonObject {
            put("authorization", "Bearer attacker"); put("origin", "https://attacker.example")
            put("MCP-Protocol-Version", "2026-07-28"); put("mcp-name", "read_sms")
        }, "approved-grant")
        assertEquals("Bearer approved-grant", headers["authorization"])
        assertFalse(headers.containsKey("origin"))
        assertEquals("device_status", headers["mcp-name"])
        val handler = McpHandler(
            tools = listOf(PhoneTool("device_status", "Status", objectSchema()) { ToolOutput("") }),
            agentFor = { grant -> Agent("a", "Inspector", "").takeIf { grant == "approved-grant" } },
            run = { ToolResult.Done(it.id, ToolOutput("battery"), 0) }, instructions = "", version = "test",
        )
        val reply = serveHttp("POST", "/mcp", headers, { body }, handler, loopback = false)
        assertEquals(200, reply.status)
        assertTrue(reply.json!!.contains("battery"))
    }

    @Test fun grantsUseCurrentSourceSwitchesAndCannotAuthenticateLocally() = runBlocking {
        var agent: Agent? = Agent("phone-agent", "Inspector", "", relayGrantId = "grant")
        var executed = 0
        val handler = McpHandler(
            tools = listOf(PhoneTool("read_sms", "Messages", objectSchema()) { ToolOutput("message") }),
            agentFor = { grant -> agent?.takeIf { it.relayGrantId == grant } },
            run = { call -> executed++; ToolResult.Done(call.id, ToolOutput("message"), 0) },
            instructions = "", version = "test",
        )
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"read_sms","arguments":{}}}"""
        suspend fun request() = serveHttp("POST", "/mcp", mapOf("authorization" to "Bearer grant"), { body }, handler, loopback = false)
        assertTrue(request().json!!.contains("Text messages access is off for Inspector"))
        assertEquals(0, executed)
        agent = agent!!.copy(sources = setOf(Source.SMS))
        assertTrue(request().json!!.contains("message"))
        assertEquals(1, executed)
        assertNull(Agents.find(listOf(agent!!), "grant"))
        agent = null
        assertEquals(401, request().status)
        assertEquals(1, executed)
        assertEquals(405, serveHttp("GET", "/mcp", emptyMap(), { "" }, handler, loopback = false).status)
    }

    @Test fun cancellingRelayFrameCancelsSharedRunnerCall() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()
        val handler = McpHandler(
            tools = listOf(PhoneTool("get_screen", "Screen", objectSchema()) { ToolOutput("") }),
            agentFor = { Agent("a", "Inspector", "", relayGrantId = "grant") },
            run = {
                started.complete(Unit)
                try { awaitCancellation() } finally { ended.complete(Unit) }
            }, instructions = "", version = "test",
        )
        val call = launch {
            handler.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_screen"}}""", mapOf("authorization" to "Bearer grant"))
        }
        withTimeout(1_000) { started.await() }
        call.cancelAndJoin()
        assertTrue(ended.isCompleted)
    }
}
