package ai.tushar.handset.transport.relay

import kotlinx.serialization.json.*

internal fun relayBusyResponse(request: JsonObject): String = buildJsonObject {
    put("type", "response")
    put("id", request.getValue("id"))
    put("status", 429)
    put("json", buildJsonObject {
        put("jsonrpc", "2.0")
        val id = runCatching { Json.parseToJsonElement(request.getValue("body").jsonPrimitive.content).jsonObject["id"] as? JsonPrimitive }.getOrNull()
        put("id", id?.takeIf { it.isString || it == JsonNull || it.doubleOrNull != null } ?: JsonNull)
        put("error", buildJsonObject {
            put("code", -32004)
            put("message", "32 requests are already pending")
        })
    })
}.toString()
