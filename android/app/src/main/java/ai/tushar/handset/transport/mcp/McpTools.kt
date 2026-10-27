package ai.tushar.handset.transport.mcp

/**
 * How each tool appears to MCP agents. [readOnly] tools only read; ChatGPT asks the user before any tool without it.
 * [destructive] marks writes that can't be taken back, as a tap that sends. [source] is what the agent must be allowed
 * to read for the call to run. A [scoped] tool's answer mixes sources, so the handler tells it, in arguments the agent
 * can't set, which of them the agent may read; it leaves out the rest.
 */
class McpToolPolicy(val readOnly: Boolean, val destructive: Boolean = false, val source: Source? = null, val scoped: Boolean = false)

private fun read(source: Source? = null) = McpToolPolicy(readOnly = true, source = source)
private fun readScoped() = McpToolPolicy(readOnly = true, scoped = true)
private fun write(destructive: Boolean, source: Source? = null) = McpToolPolicy(readOnly = false, destructive = destructive, source = source)

/**
 * Every tool MCP agents may call. A tool missing here is not listed and can't be called, so a new tool that reads
 * something private stays hidden until it has its [Source].
 */
val MCP_TOOLS: Map<String, McpToolPolicy> = mapOf(
    "get_screen" to read(),
    "read_text" to read(),
    "wait_for" to read(),
    "screenshot" to read(),
    "list_apps" to read(),
    "device_status" to read(),
    "screen_time" to read(),
    "tap" to write(destructive = true),
    "type_text" to write(destructive = true),
    "set_value" to write(destructive = true),
    "swipe" to write(destructive = true),
    "run_steps" to write(destructive = true),
    "scroll" to write(destructive = false),
    "press" to write(destructive = false),
    "open_app" to write(destructive = false),
    "open_url" to write(destructive = false),
    "web_search" to write(destructive = false),
    "set_alarm" to write(destructive = false),
    "set_timer" to write(destructive = false),
    "set_volume" to write(destructive = false),
    "set_do_not_disturb" to write(destructive = false),
    "now_playing" to write(destructive = false, source = Source.NOTIFICATIONS),
    "list_notifications" to read(Source.NOTIFICATIONS),
    "reply_notification" to write(destructive = true, source = Source.NOTIFICATIONS),
    "read_sms" to read(Source.SMS),
    "find_contact" to read(Source.CONTACTS),
    "get_location" to read(Source.LOCATION),
    "list_media" to read(Source.PHOTOS),
    "view_media" to read(Source.PHOTOS),
    "share_media" to read(Source.PHOTOS),
    "search_context" to readScoped(),
    "day_timeline" to readScoped(),
    "inbox_since" to read(Source.NOTIFICATIONS),
)
