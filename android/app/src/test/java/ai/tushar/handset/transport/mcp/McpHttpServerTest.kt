package ai.tushar.handset.transport.mcp

import ai.tushar.handset.runner.ToolResult
import ai.tushar.handset.tools.PhoneTool
import ai.tushar.handset.tools.ToolOutput
import ai.tushar.handset.tools.objectSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

class McpHttpServerTest {
    private val handler = McpHandler(emptyList(), { null }, { ToolResult.Failed(it.id, ToolResult.Code.OFF, "off") }, "", "test")
    private val server = McpHttpServer(0, handler).also { it.listen(setOf(InetAddress.getLoopbackAddress())) }
    private val peers = mutableListOf<Socket>()

    @After fun stop() {
        peers.forEach { it.close() }
        server.stop()
    }

    private fun connect() = Socket(InetAddress.getLoopbackAddress(), server.localPort).also { peers += it }

    /** The status line of a request with no token, which the server answers 401 */
    private fun statusOf(socket: Socket = connect()): String {
        socket.soTimeout = 3_000
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        socket.getOutputStream().write("POST /mcp HTTP/1.1\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
        return socket.getInputStream().bufferedReader().readLine()
    }

    @Test fun idlePeersDoNotKeepARealRequestOut() {
        repeat(20) { connect() }
        Thread.sleep(200)
        assertEquals("HTTP/1.1 401 Unauthorized", statusOf())
    }

    @Test fun peersSendingHeadersSlowlyDoNotKeepARealRequestOut() {
        repeat(20) { connect().getOutputStream().write("POST /mcp HTTP/1.1\r\nX-Slow: ".toByteArray()) }
        Thread.sleep(200)
        assertEquals("HTTP/1.1 401 Unauthorized", statusOf())
    }

    @Test fun aRequestThatTricklesInIsCutOff() {
        val slow = connect()
        slow.soTimeout = 10_000
        val started = System.nanoTime()
        slow.getOutputStream().write("POST /mcp HTTP/1.1\r\n".toByteArray())
        val ended = runCatching {
            repeat(20) {
                Thread.sleep(500)
                slow.getOutputStream().write("X: y\r\n".toByteArray())
            }
            slow.getInputStream().read()
        }
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("ended after $seconds s: $ended", seconds < 8 && (ended.isFailure || ended.getOrNull() == -1))
    }

    @Test fun aClientThatHangsUpMidCallCancelsIt() {
        val token = Agents.newToken()
        val ended = CompletableDeferred<Unit>()
        val waits = PhoneTool("wait_for", "Waits", objectSchema()) { ToolOutput("never") }
        val agent = Agent("1", "Claude Code", Agents.hash(token))
        val handler = McpHandler(listOf(waits), { Agents.find(listOf(agent), it) }, {
            try {
                awaitCancellation()
            } finally {
                ended.complete(Unit)
            }
        }, "", "test")
        val waiting = McpHttpServer(0, handler).also { it.listen(setOf(InetAddress.getLoopbackAddress())) }
        try {
            val socket = Socket(InetAddress.getLoopbackAddress(), waiting.localPort)
            val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"wait_for","arguments":{}}}"""
            socket.getOutputStream().write("POST /mcp HTTP/1.1\r\nAuthorization: Bearer $token\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
            Thread.sleep(500)
            assertFalse("the call ended before the client left", ended.isCompleted)
            socket.close()
            runBlocking { withTimeout(2_000) { ended.await() } }
        } finally {
            waiting.stop()
        }
    }

    @Test fun anAppsTokenWorksOnTheLoopbackSocketAndNoOther() {
        val token = Agents.newToken()
        val app = Agent("1", "Chat", Agents.hash(token), app = PhoneApp("com.example.chat", "ab"))
        val handler = McpHandler(emptyList(), { Agents.find(listOf(app), it) }, { error("never runs") }, "", "test")
        val other = NetworkInterface.getNetworkInterfaces().asSequence().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }.firstOrNull { it is Inet4Address }
        // One port for both addresses, since 0 would give each its own
        val port = ServerSocket(0).use { it.localPort }
        val listening = McpHttpServer(port, handler)
        try {
            listening.listen(setOfNotNull(InetAddress.getLoopbackAddress(), other))
            fun statusFrom(address: InetAddress): String {
                val socket = Socket(address, listening.localPort).also { peers += it }
                socket.soTimeout = 3_000
                val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
                // A header can't claim loopback
                socket.getOutputStream().write("POST /mcp HTTP/1.1\r\nAuthorization: Bearer $token\r\nHost: 127.0.0.1\r\nX-Forwarded-For: 127.0.0.1\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
                return socket.getInputStream().bufferedReader().readLine()
            }
            assertEquals("HTTP/1.1 200 OK", statusFrom(InetAddress.getLoopbackAddress()))
            assumeTrue("no network address to connect over", other != null)
            assertEquals("HTTP/1.1 401 Unauthorized", statusFrom(other!!))
        } finally {
            listening.stop()
        }
    }

    @Test fun aBodyPastTheCapIsRefused() {
        val socket = connect()
        socket.soTimeout = 3_000
        socket.getOutputStream().write("POST /mcp HTTP/1.1\r\nContent-Length: 10000000\r\n\r\n".toByteArray())
        assertEquals(-1, socket.getInputStream().read())
    }
}
