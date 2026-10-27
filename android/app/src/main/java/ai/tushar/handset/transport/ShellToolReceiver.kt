package ai.tushar.handset.transport

import ai.tushar.handset.Handset
import ai.tushar.handset.device.Access
import ai.tushar.handset.device.PhoneAccessibilityService
import ai.tushar.handset.device.RecordedScreen
import ai.tushar.handset.runner.ToolCall
import ai.tushar.handset.runner.ToolResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Runs tools from adb through the [ai.tushar.handset.runner.ToolRunner], as any other transport's calls run, and
 * writes each result to logcat under `Handset/Shell`. The manifest guards it with the DUMP permission, which only the
 * shell and the system hold:
 *
 *     adb shell am broadcast -n ai.tushar.handset/.transport.ShellToolReceiver --es tool get_screen --es args '{}'
 */
class ShellToolReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slug = intent.getStringExtra("tool") ?: return
        val args = intent.getStringExtra("args")?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        val handset = Handset.instance
        if (slug == "relay") {
            try {
                if ((args["off"] as? JsonPrimitive)?.booleanOrNull == true) handset.relay.unpair()
                (args["remove"] as? JsonPrimitive)?.contentOrNull?.let { id ->
                    handset.mcp.agents.all.value.firstOrNull { it.id == id || it.relayGrantId == id }?.let(handset.mcp.agents::remove)
                }
                (args["url"] as? JsonPrimitive)?.contentOrNull?.let(handset.relay::pair)
                Log.i(TAG, "relay ${handset.relay.state.value}; phoneId=${handset.relay.phoneId.value}; mcp=${handset.relay.publicMcpUrl.value}")
                handset.mcp.agents.all.value.filter { it.relayGrantId != null }.forEach { Log.i(TAG, "relay agent ${it.id} grant=${it.relayGrantId}") }
            } catch (_: Exception) { Log.e(TAG, "Relay setup failed; use an HTTPS origin") }
            return
        }
        // `--es tool access --es args '{"access":"CONTACTS","on":true}'` sets an access switch, as Settings does, for testing tools
        if (slug == "access") {
            val name = (args["access"] as? JsonPrimitive)?.contentOrNull
            val access = Access.entries.firstOrNull { it.name == name }
            if (access == null) {
                Log.e(TAG, "no access $name")
                return
            }
            handset.setAccessOn(access, (args["on"] as? JsonPrimitive)?.booleanOrNull != false)
            Log.i(TAG, "${access.name} on=${handset.isAccessOn(access)} granted=${access.granted(context)}")
            return
        }
        // `--es tool dump --es args '{"name":"clock-alarm"}'` records the screen in front for the screen reader's tests, to
        // the app's files: adb pull /sdcard/Android/data/ai.tushar.handset/files/screens/clock-alarm.json
        if (slug == "dump") {
            val name = (args["name"] as? JsonPrimitive)?.contentOrNull ?: "screen"
            handset.scope.launch {
                try {
                    val screen = PhoneAccessibilityService.require().record()
                    val file = File(context.getExternalFilesDir("screens"), "$name.json")
                    file.writeText(Json.encodeToString(RecordedScreen.serializer(), screen))
                    Log.i(TAG, "recorded ${file.absolutePath}")
                } catch (error: Exception) {
                    Log.e(TAG, "dump failed: $error")
                }
            }
            return
        }
        // `--es tool stop` does what the pill's Stop does
        if (slug == "stop") {
            handset.switchControl(false)
            Log.i(TAG, "stopped")
            return
        }
        // Submitted here, on the receiver's thread, so the calls keep their order. Not held with goAsync: Android delivers
        // one broadcast at a time to a receiver, so a stop sent while a call runs would wait for the call to end. The bound
        // accessibility service keeps the process alive.
        val batch = "adb-${SystemClock.uptimeMillis()}"
        // `--es tool calls --es args '{"calls":[{"tool":"read_sms","params":{}},...]}'` runs several calls as an agent's
        // calls run: in one queue, with reads beside actions, and approval. Each result is logged with when it finished.
        if (slug == "calls") {
            val started = SystemClock.uptimeMillis()
            (args["calls"] as? JsonArray).orEmpty().forEachIndexed { index, call ->
                val fields = call.jsonObject
                val tool = (fields["tool"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val params = fields["params"] as? JsonObject ?: JsonObject(emptyMap())
                val result = handset.runner.submit(ToolCall("$batch-${index + 1}", tool, params))
                handset.scope.launch { logSummary(result.await(), SystemClock.uptimeMillis() - started) }
            }
            return
        }
        val result = handset.runner.submit(ToolCall(batch, slug, args))
        handset.scope.launch { logFull(slug, result.await()) }
    }

    /** One line per call of a batch: its id, how it ended and when, and the first line of what it returned */
    private fun logSummary(result: ToolResult, atMs: Long) {
        when (result) {
            is ToolResult.Done -> Log.i(TAG, "${result.callId} completed at +$atMs ms, ran ${result.runMs} ms: ${result.output.text.lineSequence().firstOrNull().orEmpty()}")
            is ToolResult.Failed -> Log.i(TAG, "${result.callId} ${result.code.name.lowercase()} at +$atMs ms: ${result.message}")
        }
    }

    /** Everything a single call returned, a line at a time */
    private fun logFull(slug: String, result: ToolResult) {
        when (result) {
            is ToolResult.Done -> {
                result.output.text.lines().forEach { Log.i(TAG, it) }
                result.output.images.forEach { Log.i(TAG, "image ${it.mimeType}, ${it.base64.length} base64 chars") }
            }
            is ToolResult.Failed -> Log.e(TAG, "$slug failed (${result.code.name.lowercase()}): ${result.message}")
        }
    }

    private companion object {
        const val TAG = "Handset/Shell"
    }
}
