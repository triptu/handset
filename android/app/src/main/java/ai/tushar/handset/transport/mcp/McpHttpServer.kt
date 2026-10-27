package ai.tushar.handset.transport.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/** An HTTP answer, before it is written to the socket */
class HttpReply(val status: Int, val json: String?, val headers: Map<String, String> = emptyMap())

/**
 * What HTTP adds in front of [McpHandler]: one path, POST only, since this server keeps no stream to GET and no
 * session to DELETE, and the header that asks for a token.
 */
suspend fun serveHttp(method: String, path: String, headers: Map<String, String>, body: () -> String, handler: McpHandler): HttpReply {
    if (path.substringBefore('?') != PATH) return HttpReply(404, null)
    if (method != "POST") return HttpReply(405, null, mapOf("Allow" to "POST"))
    val reply = handler.handle(body(), headers, http = true)
    val challenge = if (reply.status == 401) mapOf("WWW-Authenticate" to "Bearer") else emptyMap()
    return HttpReply(reply.status, reply.json, challenge)
}

const val PATH = "/mcp"

private fun now() = System.nanoTime() / 1_000_000

/**
 * A small HTTP/1.1 server on a plain socket, enough for MCP clients: keep-alive, and bodies sent with Content-Length or
 * chunked. A thread per connection, since a tool call can wait minutes for the screen.
 *
 * A peer that never sends a token must not be able to stop the server, so connections are few, a request has seconds
 * to arrive whole, and a connection waiting for its next request gives way to a new one when all are taken. Only a
 * connection whose request is being answered keeps its place.
 */
class McpHttpServer(private val port: Int, private val handler: McpHandler) {
    private val pool = Executors.newCachedThreadPool()

    /** A socket for each address listened on, as loopback and Tailscale */
    private val listeners = HashMap<InetAddress, ServerSocket>()

    private class Connection(val socket: Socket) {
        /** Answering a request, which is the only time the connection can't be closed for a newer one */
        @Volatile var busy = false
        @Volatile var idleSince = now()
    }

    private val connections = LinkedHashSet<Connection>()

    /** The port listened on, which differs from [port] only when that is 0, as in a test */
    val localPort: Int get() = synchronized(listeners) { listeners.values.firstOrNull()?.localPort } ?: port

    /**
     * Listens on [addresses] and on no others, keeping the sockets already open, so it can run again each time the
     * networks change. Returns the addresses it couldn't listen on, as when another app holds the port.
     */
    fun listen(addresses: Set<InetAddress>): Set<InetAddress> = synchronized(listeners) {
        (listeners.keys - addresses).forEach { address -> runCatching { listeners.remove(address)?.close() } }
        (addresses - listeners.keys).filterTo(HashSet()) { address ->
            val server = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(address, port))
                }
            }.getOrNull() ?: return@filterTo true
            listeners[address] = server
            pool.execute { accept(server) }
            false
        }
    }

    fun stop() {
        synchronized(listeners) {
            listeners.values.forEach { runCatching { it.close() } }
            listeners.clear()
        }
        synchronized(connections) { connections.forEach { runCatching { it.socket.close() } } }
        pool.shutdownNow()
    }

    private fun accept(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            admit(client)?.let { connection -> pool.execute { serve(connection) } }
        }
        // A network that went away ends its socket; forgetting it lets the next listen open it again
        synchronized(listeners) { listeners.entries.removeAll { it.value === server } }
        runCatching { server.close() }
    }

    /** Makes room by closing the connection idle the longest, or turns [client] away when every connection is answering */
    private fun admit(client: Socket): Connection? = synchronized(connections) {
        if (connections.size >= MAX_CONNECTIONS) {
            val idle = connections.filter { !it.busy }.minByOrNull { it.idleSince }
            if (idle == null) {
                runCatching {
                    client.getOutputStream().write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
                runCatching { client.close() }
                return null
            }
            runCatching { idle.socket.close() }
            connections.remove(idle)
        }
        Connection(client).also { connections.add(it) }
    }

    private fun serve(connection: Connection) {
        val client = connection.socket
        val deadline = DeadlineInput(client)
        val input = BufferedInputStream(deadline)
        val output = client.getOutputStream()
        // A byte of the next request, read while watching for the peer to leave during a call
        var early = -1
        try {
            while (true) {
                connection.idleSince = now()
                // Waiting for the next request may take a while; once it starts, all of it must arrive within seconds
                deadline.until(connection.idleSince + IDLE_TIMEOUT_MS)
                val first = if (early >= 0) early else input.read()
                early = -1
                if (first < 0) return
                if (first == '\n'.code) continue
                deadline.until(now() + REQUEST_TIMEOUT_MS)
                val requestLine = (first.toChar() + (readLine(input) ?: return)).trimEnd('\r')
                if (requestLine.isEmpty()) continue
                val parts = requestLine.split(' ')
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    if (headers.size >= MAX_HEADERS) error("too many headers")
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                // Read even when the request is refused, so the next request on the connection starts where it should
                val body = readBody(input, headers)
                connection.busy = true
                val reply = try {
                    runBlocking {
                        val answer = async(Dispatchers.IO) { serveHttp(parts[0], parts.getOrElse(1) { "/" }, headers, { body }, handler) }
                        // A client that closes the connection mid-call has given up on it, so the call stops
                        if (withTimeoutOrNull(WATCH_AFTER_MS) { answer.join() } == null) early = watch(answer, input, deadline)
                        if (answer.isCancelled) null else answer.await()
                    }
                } finally {
                    connection.busy = false
                } ?: return
                val bytes = reply.json?.toByteArray() ?: ByteArray(0)
                val head = StringBuilder("HTTP/1.1 ${reply.status} ${reason(reply.status)}\r\n")
                if (reply.json != null) head.append("Content-Type: application/json\r\n")
                reply.headers.forEach { (name, value) -> head.append("$name: $value\r\n") }
                head.append("Content-Length: ${bytes.size}\r\n\r\n")
                output.write(head.toString().toByteArray())
                output.write(bytes)
                output.flush()
                if (headers["connection"].equals("close", ignoreCase = true)) return
            }
        } catch (_: Exception) {
            // A peer gone, too slow or malformed ends its own connection and nothing else
        } finally {
            synchronized(connections) { connections.remove(connection) }
            runCatching { client.close() }
        }
    }

    /** Reads [input] while [answer] runs, and cancels it when the peer leaves. Returns a byte of the next request, or -1. */
    private fun watch(answer: Job, input: InputStream, deadline: DeadlineInput): Int {
        while (answer.isActive) {
            deadline.until(now() + WATCH_POLL_MS)
            val byte = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                -1
            }
            if (byte >= 0) return byte
            answer.cancel()
        }
        return -1
    }

    /** The socket's input, which fails a read once [until]'s time has passed however slowly the bytes trickle in */
    private class DeadlineInput(private val socket: Socket) : InputStream() {
        private val input = socket.getInputStream()
        private var deadline = Long.MAX_VALUE

        fun until(at: Long) {
            deadline = at
        }

        override fun read(): Int {
            arm()
            return input.read()
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            arm()
            return input.read(bytes, offset, length)
        }

        private fun arm() {
            val left = deadline - now()
            if (left <= 0) throw SocketTimeoutException("request too slow")
            socket.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): String {
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val size = readLine(input)?.substringBefore(';')?.trim()?.toInt(16) ?: break
                if (size < 0) error("bad chunk")
                if (size == 0) {
                    readLine(input)
                    break
                }
                if (buffer.size() + size > MAX_BODY) error("body too large")
                buffer.write(readExactly(input, size))
                readLine(input)
            }
            return buffer.toString(Charsets.UTF_8.name())
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY) error("body too large")
        return String(readExactly(input, length), Charsets.UTF_8)
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(bytes, read, count - read)
            if (n < 0) error("connection closed mid-body")
            read += n
        }
        return bytes
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length >= MAX_LINE) error("line too long")
            line.append(c.toChar())
        }
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Status"
    }

    private companion object {
        const val MAX_CONNECTIONS = 8
        const val IDLE_TIMEOUT_MS = 60_000L
        const val REQUEST_TIMEOUT_MS = 5_000L
        const val WATCH_AFTER_MS = 200L
        const val WATCH_POLL_MS = 100L
        const val MAX_HEADERS = 100
        const val MAX_LINE = 16_384
        const val MAX_BODY = 262_144
    }
}
