package ai.tushar.handset.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One tool, as agents see it and as a call runs it */
class PhoneTool(
    val slug: String,
    val description: String,
    val inputSchema: JsonObject,
    /** What the user is asked to allow before the call runs, or null when the call needs no permission */
    val consentPrompt: suspend (ToolArgs) -> String? = { null },
    /** It doesn't use the screen, so it runs as soon as it arrives instead of waiting behind the calls before it */
    val concurrent: Boolean = false,
    val run: suspend (ToolArgs) -> ToolOutput,
)

/** What a tool returns. [timings] are the phone's own milliseconds for the parts of the call. */
class ToolOutput(
    val text: String,
    val images: List<Image> = emptyList(),
    val timings: Map<String, Long> = emptyMap(),
) {
    class Image(val base64: String, val mimeType: String) : ToolContent

    /** The text first, then the images, as an MCP result lists them */
    val content: List<ToolContent> get() = listOf(ToolContent.Text(text)) + images
}

/** One block of a tool's result, as MCP's text and image content */
sealed interface ToolContent {
    class Text(val text: String) : ToolContent
}

/** The prompt the user allowed for the call that runs, so the tap can check it taps what was allowed */
class Approval(val prompt: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<Approval>
}

/** A failure the agent can act on; its message reaches the agent as the error */
class ToolFailure(message: String) : Exception(message)

/** runCatching that lets cancellation through, so Stop ends a call at once instead of being taken for a failure */
inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}

/** A call's arguments */
class ToolArgs(private val values: JsonObject) {
    fun objects(name: String): List<ToolArgs> =
        (values[name] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { step -> ToolArgs(step) } }

    fun string(name: String): String? = values[name]?.primitiveOrNull()?.contentOrNull?.takeIf { it.isNotBlank() }
    fun int(name: String): Int? = values[name]?.primitiveOrNull()?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }
    fun boolean(name: String): Boolean? = values[name]?.primitiveOrNull()?.booleanOrNull
    fun number(name: String): Float? = values[name]?.primitiveOrNull()?.let { it.floatOrNull ?: it.contentOrNull?.toFloatOrNull() }
    fun strings(name: String): List<String> = (values[name] as? JsonArray).orEmpty().mapNotNull { it.primitiveOrNull()?.contentOrNull }

    fun requireString(name: String): String = string(name) ?: throw ToolFailure("`$name` is required")
    fun requireInt(name: String): Int = int(name) ?: throw ToolFailure("`$name` is required and must be a number")
    fun requireNumber(name: String): Float = number(name) ?: throw ToolFailure("`$name` is required and must be a number")

    private fun JsonElement.primitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    /** The arguments as the call sent them, as JSON, for the history */
    override fun toString(): String = values.toString()
}

/** A JSON schema of an object with the given properties, for a tool's input */
fun objectSchema(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject = JsonObject(
    mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(properties.toMap()),
        "required" to JsonArray(required.map { JsonPrimitive(it) }),
    ),
)

fun stringListProp(description: String): JsonObject = JsonObject(
    mapOf("type" to JsonPrimitive("array"), "description" to JsonPrimitive(description), "items" to JsonObject(mapOf("type" to JsonPrimitive("string")))),
)

fun stringArrayProp(description: String, values: List<String>): JsonObject = JsonObject(
    mapOf(
        "type" to JsonPrimitive("array"),
        "description" to JsonPrimitive(description),
        "items" to JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to JsonArray(values.map { JsonPrimitive(it) }))),
    ),
)

fun prop(type: String, description: String, enum: List<String>? = null): JsonObject = JsonObject(
    buildMap {
        put("type", JsonPrimitive(type))
        put("description", JsonPrimitive(description))
        enum?.let { values -> put("enum", JsonArray(values.map { JsonPrimitive(it) })) }
    },
)
