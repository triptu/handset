package ai.tushar.handset.tools

import ai.tushar.handset.device.NotificationWatcher
import ai.tushar.handset.device.PhoneAccessibilityService
import ai.tushar.handset.device.ScreenReader
import ai.tushar.handset.device.ScreenSnapshot
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The tools of the `phone` toolkit. Every action returns the screen once it settles, so the agent needs no extra read. */
class PhoneTools(private val context: Context) {
    private val phone get() = PhoneAccessibilityService.require()
    val actions = PhoneActions(context)

    val all: List<PhoneTool> = listOf(
        PhoneTool(
            slug = "get_screen",
            description = "Read what is on the phone's screen: the app in front and its elements, each with an [id] for the action tools " +
                "and its centre as @x,y. A screen with almost no elements comes with a screenshot. Set include_screenshot when the " +
                "elements do not explain the screen, as in a game, map or image. Use query to list only matching lines.",
            inputSchema = objectSchema(
                "include_screenshot" to prop("boolean", "Also return a screenshot of the screen"),
                "query" to prop("string", "Only list the elements whose line contains this text, such as a price or a name"),
                "max_elements" to prop("integer", "List at most this many elements"),
                "expect" to prop("string", "Wait until this text is on screen first, up to timeout_ms"),
                "timeout_ms" to prop("integer", "How long to wait for expect; 5000 by default"),
            ),
        ) { args ->
            val expect = args.string("expect")
            if (expect != null) actions.settle(SystemClock.uptimeMillis(), null, expect = expect, limitMs = timeout(args, EXPECT_DEFAULT_MS))
            val screen = phone.readScreen()
            var text = args.string("query")?.let { filterLines(screen.text, it) } ?: screen.text
            args.int("max_elements")?.let { max -> text = limitLines(text, max) }
            val wantsScreenshot = args.boolean("include_screenshot") == true || screen.elements.size < FEW_ELEMENTS
            val screenshot = if (wantsScreenshot) attempt { phone.screenshot() }.getOrNull() else null
            ToolOutput(
                text + screenshot?.let { "\n${it.note}" }.orEmpty(),
                listOfNotNull(screenshot?.let { ToolOutput.Image(it.base64, "image/jpeg") }),
            )
        },
        action(
            slug = "tap",
            description = "Tap an element, by its id from the latest screen or by its text as target, or a point by x and y. x and y " +
                "are screen pixels, as in the @x,y of get_screen, not screenshot pixels. Set long for a long press.",
            "element_id" to prop("integer", "The [id] of the element in the latest screen"),
            "target" to prop("string", "The element's text, hint or view id, when there is no id, such as Save"),
            "x" to prop("integer", "Horizontal position in screen pixels, when there is no element"),
            "y" to prop("integer", "Vertical position in screen pixels, when there is no element"),
            "long" to prop("boolean", "Press and hold instead of a tap"),
            "scroll_to_find" to prop("boolean", "With target: scroll the main list down until the target shows, up to 6 pages"),
            consentPrompt = { args -> sendingPrompt(args) },
        ) { args ->
            val target = Target.from(args) ?: throw ToolFailure("Give element_id, target, or x and y")
            actions.tap(target, args.boolean("long") == true, args.boolean("scroll_to_find") == true)
        },
        action(
            slug = "type_text",
            description = "Replace the text of an input field, named by element_id or target, or the focused field. A target that " +
                "isn't a field, as a search bar or a row such as \"Where to?\" that opens one, is tapped first and the field it opens is " +
                "typed into; with no target and no field on screen, a search bar at the top is. Set submit to press " +
                "the keyboard's enter or search key afterwards. Fails when the field does not take the text, as in a calculator or a " +
                "time picker; tap their keys instead.",
            "text" to prop("string", "The whole text the field should hold"),
            "element_id" to prop("integer", "The [id] of the input in the latest screen"),
            "target" to prop("string", "Which field: its hint, label or view id, such as Search. Not the text to type."),
            "submit" to prop("boolean", "Press enter, search or go after typing"),
            required = listOf("text"),
        ) { args ->
            actions.type(Target.from(args), args.requireString("text"), args.boolean("submit") == true)
        },
        action(
            slug = "scroll",
            description = "Scroll a list, named by element_id or target, or the whole screen. Direction is where the content moves " +
                "into view: down shows what is below. No change to elements usually means the end of the list.",
            "direction" to prop("string", "Which way to scroll", enum = listOf("up", "down", "left", "right")),
            "element_id" to prop("integer", "The [id] of a scrollable element"),
            "target" to prop("string", "The list's text or view id"),
            required = listOf("direction"),
            unchangedHint = SCROLL_UNCHANGED,
        ) { args ->
            actions.scroll(Target.from(args), args.requireString("direction"))
        },
        action(
            slug = "swipe",
            description = "Swipe from one point to another, for gestures that scroll cannot do. Points are screen pixels, as in " +
                "get_screen's @x,y.",
            "from_x" to prop("integer", "Start x"),
            "from_y" to prop("integer", "Start y"),
            "to_x" to prop("integer", "End x"),
            "to_y" to prop("integer", "End y"),
            "duration_ms" to prop("integer", "How long the swipe takes; 300 by default"),
            required = listOf("from_x", "from_y", "to_x", "to_y"),
        ) { args ->
            phone.swipe(
                args.requireInt("from_x").toFloat(),
                args.requireInt("from_y").toFloat(),
                args.requireInt("to_x").toFloat(),
                args.requireInt("to_y").toFloat(),
                (args.int("duration_ms") ?: PhoneActions.SWIPE_MS.toInt()).toLong(),
            )
            "Swiped"
        },
        action(
            slug = "set_value",
            description = "Move a slider, such as brightness or volume in Settings, to a value in the range get_screen shows for it.",
            "element_id" to prop("integer", "The [id] of the slider in the latest screen"),
            "target" to prop("string", "The slider's text or view id"),
            "value" to prop("number", "The new value, between the slider's minimum and maximum"),
            required = listOf("value"),
        ) { args ->
            val element = actions.element(Target.from(args) ?: throw ToolFailure("Give element_id or target"))
            val range = element.node.rangeInfo ?: throw ToolFailure("Element [${element.id}] is not a slider")
            val value = args.requireNumber("value").coerceIn(range.min, range.max)
            phone.setProgress(element.node, value)
            "Set [${element.id}] to ${ScreenReader.number(value)}"
        },
        PhoneTool(
            slug = "read_text",
            description = "The whole text of an element and everything inside it. get_screen cuts long text short with …",
            inputSchema = objectSchema(
                "element_id" to prop("integer", "The [id] of the element in the latest screen"),
                "target" to prop("string", "The element's text or view id"),
            ),
        ) { args ->
            actions.requireSameApp()
            val element = actions.element(Target.from(args) ?: throw ToolFailure("Give element_id or target"))
            ToolOutput(ScreenReader.fullText(element.node).ifEmpty { "(no text)" })
        },
        action(
            slug = "press",
            description = "Press a system button: back, home, recents, open the notification shade or quick settings, or lock the " +
                "screen. To read notifications, use list_notifications instead.",
            "key" to prop("string", "The button", enum = listOf("back", "home", "recents", "notifications", "quick_settings", "lock")),
            required = listOf("key"),
            sameApp = false,
        ) { args ->
            actions.press(args.requireString("key"))
        },
        action(
            slug = "open_app",
            description = "Open an installed app by its name, such as WhatsApp or Maps, or by its package name. An app opens where it was " +
                "last left; set fresh to start it at its home screen. Returns the screen once the app is in front and settled.",
            "app" to prop("string", "The app's name or package name"),
            "fresh" to prop("boolean", "Start the app at its home screen, not where it was last left"),
            required = listOf("app"),
            sameApp = false,
            limitMs = PhoneActions.LAUNCH_LIMIT_MS,
        ) { args ->
            val (done, packageName) = actions.openApp(args.requireString("app"), args.boolean("fresh") == true)
            Launched(done, packageName)
        },
        PhoneTool(
            slug = "list_apps",
            concurrent = true,
            description = "List the installed apps that can be opened, as name and package",
            inputSchema = objectSchema(),
        ) {
            ToolOutput(actions.launchableApps().joinToString("\n") { (label, packageName) -> "$label ($packageName)" })
        },
        action(
            slug = "open_url",
            description = "Open a link in the app that handles it, and return the screen once it has loaded. Links that work well: " +
                "https pages; Google Maps directions (https://www.google.com/maps/dir/?api=1&destination=...&travelmode=driving); " +
                "wa.me/<number>?text=... for a WhatsApp chat with prefilled text; tel:, sms:, mailto:; spotify:search:...; " +
                "YouTube results (https://www.youtube.com/results?search_query=...); and Android intent: links " +
                "(intent:#Intent;action=...;end). Web pages reuse one Chrome tab. Set package to open in that app and skip the " +
                "\"Open with\" chooser.",
            "url" to prop("string", "The link"),
            "package" to prop("string", "The package of the app that should open it, such as com.whatsapp"),
            required = listOf("url"),
            sameApp = false,
            limitMs = PhoneActions.LAUNCH_LIMIT_MS,
        ) { args ->
            val (done, handler) = actions.openUrl(args.requireString("url"), args.string("package"))
            if (handler != null) Launched(done, handler) else done
        },
        action(
            slug = "web_search",
            description = "Search Google in the phone's browser, in the same tab as open_url. Returns the results page.",
            "query" to prop("string", "What to search for"),
            required = listOf("query"),
            sameApp = false,
            limitMs = PhoneActions.LAUNCH_LIMIT_MS,
        ) { args ->
            val query = args.requireString("query")
            // A search link opens in the default browser. ACTION_WEB_SEARCH shows an app chooser on phones with no default for it.
            phone.launch(actions.linkIntent("https://www.google.com/search?q=" + Uri.encode(query)))
            "Searched for \"$query\""
        },
        PhoneTool(
            slug = "wait_for",
            description = "Wait until some text is on screen or an app is in front, as for a page or an order status to load. " +
                "Returns the screen when it appears, or after the timeout.",
            inputSchema = objectSchema(
                "text" to prop("string", "Text to wait for, matched without case"),
                "app" to prop("string", "An app name or package to wait for"),
                "timeout_ms" to prop("integer", "How long to wait at most; 10000 by default, 60000 at most"),
            ),
        ) { args -> waitFor(args) },
        PhoneTool(
            slug = "screenshot",
            description = "Take a screenshot of the phone's screen, without the element list; get_screen with include_screenshot " +
                "gives both. Its size differs from the screen's; the result says how to turn a point in it into screen pixels.",
            inputSchema = objectSchema(),
        ) {
            val screenshot = phone.screenshot()
            ToolOutput("Screenshot of ${currentApp()}. ${screenshot.note}", listOf(ToolOutput.Image(screenshot.base64, "image/jpeg")))
        },
        PhoneTool(
            slug = "list_notifications",
            concurrent = true,
            description = "List the notifications on the phone, newest first: the app and its package, the title, the text, and whether " +
                "it can be replied to. Ongoing ones, such as an order, a ride, a timer or music, are included and marked ongoing. " +
                "Each has a key for reply_notification.",
            inputSchema = objectSchema(),
        ) {
            val messages = NotificationWatcher.require().messages()
            val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
            val text = messages.joinToString("\n\n") { message ->
                val flags = listOfNotNull(
                    time.format(Instant.ofEpochMilli(message.postedAt)),
                    "ongoing".takeIf { message.ongoing },
                    "can reply: ${if (message.canReply) "yes" else "no"}",
                )
                "key=${message.key}\n${message.app} (${message.packageName}) · ${message.title} · ${flags.joinToString(" · ")}\n${message.text}"
            }
            ToolOutput(text.ifEmpty { "No notifications" })
        },
        PhoneTool(
            slug = "reply_notification",
            description = "Reply to a message notification with its inline Reply action, without opening the app",
            inputSchema = objectSchema(
                "key" to prop("string", "The key from list_notifications"),
                "text" to prop("string", "The reply to send"),
                required = listOf("key", "text"),
            ),
        ) { args ->
            val message = NotificationWatcher.require().reply(args.requireString("key"), args.requireString("text"))
            ToolOutput("Replied to ${message.app} · ${message.title}")
        },
    )

    /** What an action that opened an app says, and the package to wait for */
    class Launched(val done: String, val packageName: String)

    /**
     * An action tool: it acts, then returns the [report] of the settled screen. Every action takes `expect`, text to wait
     * for after it, and `timeout_ms`. [sameApp] refuses ids and points from a screen another app has since replaced.
     */
    private fun action(
        slug: String,
        description: String,
        vararg properties: Pair<String, JsonObject>,
        required: List<String> = emptyList(),
        sameApp: Boolean = true,
        limitMs: Long = PhoneActions.SETTLE_LIMIT_MS,
        unchangedHint: String = TAP_UNCHANGED,
        consentPrompt: suspend (ToolArgs) -> String? = { null },
        act: suspend (ToolArgs) -> Any,
    ) = PhoneTool(
        slug = slug,
        description = "$description Returns a status line and the settled screen.",
        inputSchema = objectSchema(
            *properties,
            "expect" to prop("string", "Text that should appear after the action, such as the next page's title. Text already on screen counts once the screen has changed."),
            "timeout_ms" to prop("integer", "How long to wait for the screen to settle or for expect; 5000 by default with expect, 60000 at most"),
            required = required,
        ),
        consentPrompt = consentPrompt,
    ) { args ->
        // Ids and points belong to the screen last read; a target text is found on the screen as it is now
        val positional = args.int("element_id") != null || args.int("x") != null || args.int("from_x") != null
        if (sameApp && positional) actions.requireSameApp()
        val started = SystemClock.uptimeMillis()
        val before = actions.beforeText()
        val expect = args.string("expect")
        val beforeMarks = expect?.let { actions.marks(it) }
        val result = act(args)
        val acted = SystemClock.uptimeMillis()
        val (done, waitFor) = if (result is Launched) result.done to result.packageName else result.toString() to null
        // An app that opens may already be in front with the expected text, which then counts at once
        val timeoutMs = timeout(args, if (expect != null) maxOf(limitMs, EXPECT_DEFAULT_MS) else limitMs)
        val settled = actions.settle(acted, before, waitFor, expect, timeoutMs, expectNew = result !is Launched, beforeMarks = beforeMarks)
        ToolOutput(
            settled.report(done, expect, unchangedHint),
            timings = mapOf("action_ms" to acted - started, "settle_ms" to settled.ms),
        )
    }

    /** A permission prompt when a tap's element looks like it sends or pays */
    suspend fun sendingPrompt(args: ToolArgs): String? {
        val target = Target.from(args) ?: return null
        // A tap at a point is checked by the element under it, so x and y can't skip the approval
        if (target is Target.Point) return actions.elementAt(target)?.let { actions.approvalPrompt(it) }
        // A missing id fails the call here, with the screen as it is now: the failure reads that screen, which renumbers the ids,
        // so going on would tap the old id on the new screen. A target text that isn't on screen yet, as one scroll_to_find
        // scrolls to, asks nothing here; the tap checks the element it finds.
        return when (target) {
            is Target.Id -> actions.approvalPrompt(actions.element(target))
            is Target.Text -> actions.elementNow(target)?.let { actions.approvalPrompt(it) }
            is Target.Point -> null
        }
    }

    private suspend fun waitFor(args: ToolArgs): ToolOutput {
        val text = args.string("text")
        val app = args.string("app")
        if (text == null && app == null) throw ToolFailure("Give text or app")
        val timeout = (args.int("timeout_ms") ?: WAIT_DEFAULT_MS).coerceIn(0, WAIT_MAX_MS).toLong()
        val deadline = SystemClock.uptimeMillis() + timeout
        var screen: ScreenSnapshot? = null
        while (true) {
            // Read again only when the screen has changed, so a match shows up within a frame of appearing
            screen = attempt { phone.currentScreen() }.getOrNull() ?: screen
            if (screen != null && matches(screen, text, app)) {
                val waited = SystemClock.uptimeMillis() - (deadline - timeout)
                return ToolOutput("Found ${text?.let { "\"$it\"" } ?: app} in ${screen.appLabel} after $waited ms.\n\n${screen.text}")
            }
            if (SystemClock.uptimeMillis() >= deadline) break
            val seen = phone.lastChangeAt
            while (phone.lastChangeAt == seen && SystemClock.uptimeMillis() < deadline) delay(PhoneActions.POLL_MS)
        }
        return ToolOutput("${text?.let { "\"$it\"" } ?: app} did not appear in ${timeout / 1000} s. The screen now:\n\n${screen?.text.orEmpty()}")
    }

    private fun matches(screen: ScreenSnapshot, text: String?, app: String?): Boolean =
        (text == null || screen.text.contains(text, ignoreCase = true)) &&
            (app == null || screen.packageName.equals(app, ignoreCase = true) || screen.appLabel.equals(app, ignoreCase = true))

    private fun timeout(args: ToolArgs, default: Long): Long = (args.int("timeout_ms")?.toLong() ?: default).coerceIn(0, WAIT_MAX_MS.toLong())

    private fun filterLines(text: String, query: String): String {
        val lines = text.lines()
        val matches = lines.drop(1).filter { it.contains(query, ignoreCase = true) }.map { it.trimStart() }
        return (listOf(lines.first()) + matches.ifEmpty { listOf("(nothing on screen matches \"$query\")") }).joinToString("\n")
    }

    private fun limitLines(text: String, max: Int): String {
        val lines = text.lines().filter { it.isNotEmpty() }
        if (lines.size - 1 <= max) return text
        return (lines.take(max + 1) + "… ${lines.size - 1 - max} more elements not listed").joinToString("\n")
    }

    /** Read without replacing the last screen, so the ids the agent holds stay valid */
    private fun currentApp(): String = PhoneAccessibilityService.instance?.currentAppLabel() ?: "the current app"

    private companion object {
        const val EXPECT_DEFAULT_MS = 5_000L
        const val WAIT_DEFAULT_MS = 10_000
        const val WAIT_MAX_MS = 60_000
        const val FEW_ELEMENTS = 3
    }
}
