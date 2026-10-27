package ai.tushar.handset.transport.relay

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayResponsesTest {
    @Test fun busyResponseUsesHttp429AndKeepsBothRequestIds() {
        for (rpcId in listOf("7", "\"client-id\"", "null")) {
            val response = response("{\"id\":$rpcId}")
            assertEquals("frame-id", response.getValue("id").jsonPrimitive.content)
            assertEquals(429, response.getValue("status").jsonPrimitive.int)
            val rpc = response.getValue("json").jsonObject
            assertEquals(Json.parseToJsonElement(rpcId), rpc["id"])
            assertEquals(-32004, rpc.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
        }
    }

    @Test fun notificationsAndInvalidBodiesHaveNullRpcId() {
        for (body in listOf("{}", "invalid", "{\"id\":true}", "{\"id\":{}}")) {
            assertEquals(JsonNull, response(body).getValue("json").jsonObject["id"])
        }
    }

    private fun response(body: String) = Json.parseToJsonElement(relayBusyResponse(buildJsonObject {
        put("id", "frame-id"); put("body", body)
    })).jsonObject
}
