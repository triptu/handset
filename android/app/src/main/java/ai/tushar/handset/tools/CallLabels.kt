package ai.tushar.handset.tools

/** What the status pill says while a call runs, in a few words, such as Tapping “Save” */
object CallLabels {
    fun describe(slug: String, args: ToolArgs): String {
        val target = args.string("target")?.let { "“${short(it)}”" }
        return when (slug) {
            "tap" -> "Tapping ${target ?: if (args.int("x") != null) "the screen" else "an item"}"
            "type_text" -> "Typing${target?.let { " in $it" }.orEmpty()}"
            "scroll" -> "Scrolling ${args.string("direction").orEmpty()}".trim()
            "swipe" -> "Swiping"
            "set_value" -> "Moving a slider"
            "press" -> "Pressing ${args.string("key")?.replace('_', ' ').orEmpty()}".trim()
            "open_app" -> "Opening ${args.string("app")?.let(::short) ?: "an app"}"
            "open_url" -> "Opening a link"
            "web_search" -> "Searching “${short(args.string("query").orEmpty())}”"
            "wait_for" -> "Waiting for the screen"
            "get_screen", "read_text" -> "Reading the screen"
            "screenshot" -> "Looking at the screen"
            "run_steps" -> "Running ${args.objects("steps").size} steps"
            "list_notifications" -> "Reading notifications"
            "reply_notification" -> "Replying to a message"
            "read_sms" -> "Reading text messages"
            "find_contact" -> "Looking up contacts"
            "get_location" -> "Checking where you are"
            "list_media", "view_media" -> "Looking at photos"
            "share_media" -> "Getting photos"
            "screen_time" -> "Checking screen time"
            "now_playing" -> "Checking what's playing"
            "set_alarm" -> "Setting an alarm"
            "set_timer" -> "Starting a timer"
            "device_status" -> "Checking the phone's status"
            "set_volume" -> "Setting the volume"
            "set_do_not_disturb" -> "Switching Do not disturb"
            "list_apps" -> "Checking apps"
            "search_context" -> "Searching what you saw"
            "inbox_since" -> "Catching up on messages"
            "day_timeline" -> "Reading your day"
            else -> "Working"
        }
    }

    private fun short(text: String) = if (text.length > MAX) text.take(MAX) + "…" else text

    private const val MAX = 28
}
