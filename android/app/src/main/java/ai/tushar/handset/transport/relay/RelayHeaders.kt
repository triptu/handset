package ai.tushar.handset.transport.relay

import kotlinx.serialization.json.*

/** OAuth stays at the relay. Only the frame's grant selects a phone agent. */
internal fun relayHeaders(body: String, forwarded: JsonObject, grant: String): Map<String, String> {
    val allowed = setOf("content-type", "accept", "mcp-protocol-version", "mcp-session-id", "last-event-id")
    val headers = forwarded.mapNotNull { (key, value) ->
        key.lowercase().takeIf { it in allowed }?.let { it to value.jsonPrimitive.content }
    }.toMap().toMutableMap()
    headers["authorization"] = "Bearer $grant"
    // Protocol v1 strips MCP routing headers. Reconstruct them for the shared handler's modern RPC checks.
    val rpc = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
    (rpc?.get("method") as? JsonPrimitive)?.contentOrNull?.let { headers["mcp-method"] = it }
    val params = rpc?.get("params") as? JsonObject
    (params?.get("name") as? JsonPrimitive)?.contentOrNull?.let { headers["mcp-name"] = it }
    return headers
}
