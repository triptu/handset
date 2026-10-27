package ai.tushar.handset.tools

import ai.tushar.handset.Handset
import ai.tushar.handset.device.PhoneAccessibilityService
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `run_steps`: several actions in one call, in order, with no model in between. Each step settles before the next, and
 * the run stops at the first step that fails, whose `expect` isn't met, or that would need the user's Allow. Later
 * steps can't know element ids, so they name elements by text.
 */
class RunSteps(private val actions: PhoneActions, private val needsApproval: suspend (ToolArgs) -> String?) {
    val tool = PhoneTool(
        slug = "run_steps",
        description = "Run several phone actions in order in one call, such as open an app, tap a tab, type a search and submit. " +
            "Each step settles before the next. The run stops at the first step that fails, whose expect text doesn't appear, or " +
            "whose tap changes nothing (unless a wait_for follows), or that taps Send or Pay while the user asks to approve those; run that step on its own. Name elements by target text, " +
            "since ids change between screens; element_id refers to the screen before the first step. Returns each step's status " +
            "and the final screen.",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "steps" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("array"),
                                "description" to JsonPrimitive("The steps, in order, at most $MAX_STEPS"),
                                "items" to objectSchema(
                                    "action" to prop("string", "What to do", enum = ACTIONS),
                                    "target" to prop("string", "tap, scroll: the element's text or view id; type_text: the field's hint or label"),
                                    "element_id" to prop("integer", "tap, type_text, scroll: an id, for the first step only"),
                                    "x" to prop("integer", "tap: a point's x in screen pixels; after the first step prefer target, as the screen has moved on"),
                                    "y" to prop("integer", "tap: a point's y in screen pixels"),
                                    "long" to prop("boolean", "tap: press and hold"),
                                    "scroll_to_find" to prop("boolean", "tap: scroll the main list down until target shows, up to 6 pages"),
                                    "text" to prop("string", "type_text: the whole text the field should hold, not the field's label (that is target)"),
                                    "query" to prop("string", "web_search: what to search for"),
                                    "submit" to prop("boolean", "type_text: press enter after typing"),
                                    "key" to prop("string", "press: back, home, recents, notifications, quick_settings or lock"),
                                    "direction" to prop("string", "scroll: up, down, left or right"),
                                    "app" to prop("string", "open_app: the app's name or package; wait_for: an app to wait for instead of text"),
                                    "fresh" to prop("boolean", "open_app: start at the app's home screen"),
                                    "url" to prop("string", "open_url: the link"),
                                    "package" to prop("string", "open_url: the app that should open it"),
                                    "expect" to prop("string", "Text that must appear after this step; text already on screen counts once the screen has changed. The run stops if it doesn't appear. For wait_for, what to wait for."),
                                    "timeout_ms" to prop("integer", "How long this step may take to settle or show expect"),
                                    required = listOf("action"),
                                ),
                            ),
                        ),
                    ),
                ),
                "required" to JsonArray(listOf(JsonPrimitive("steps"))),
            ),
        ),
    ) { args -> run(args) }

    private suspend fun run(args: ToolArgs): ToolOutput {
        val steps = args.objects("steps")
        if (steps.isEmpty()) throw ToolFailure("Give at least one step")
        if (steps.size > MAX_STEPS) throw ToolFailure("Give at most $MAX_STEPS steps")
        val lines = ArrayList<String>()
        var screen = ""
        var stopped = false
        var screenShown = false
        val started = SystemClock.uptimeMillis()
        val pill = PhoneAccessibilityService.instance?.pill?.takeIf { Handset.instance.showStatusPill }
        for ((index, step) in steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            val number = index + 1
            pill?.show("Handset · $number of ${steps.size}: ${CallLabels.describe(step.string("action").orEmpty(), step)}")
            val outcome = attempt { runStep(step, first = index == 0) }
            val result = outcome.getOrElse { error ->
                lines.add("$number. [failed] ${step.string("action")}: ${error.message}")
                stopped = true
                // A failure that names a missing element already carries the screen, which is newer than the last step's
                if (error.message.orEmpty().contains(SCREEN_NOW)) {
                    screenShown = true
                    screen = ""
                }
                null
            }
            if (result == null) break
            if (result.blocked != null) {
                lines.add("$number. [stopped] ${result.blocked}")
                stopped = true
                break
            }
            val settled = result.settled!!
            screen = settled.screen
            val change = when {
                settled.blind -> BLIND
                settled.changed -> "elements changed"
                else -> "elements unchanged"
            }
            // Later steps would act in whatever app is in front instead
            if (settled.appInFront == false) {
                lines.add("$number. [app did not come to the front] ${result.done} · in ${settled.app} · ${settled.ms} ms")
                stopped = true
                break
            }
            if (settled.expectMet == false) {
                lines.add("$number. [expect not met: \"${step.string("expect") ?: step.string("text")}\"] ${result.done} · $change · ${settled.ms} ms")
                stopped = true
                break
            }
            // The next steps were written for the screen this tap should have opened, so they would only fail confusingly
            val waitsNext = steps.getOrNull(index + 1)?.string("action") == "wait_for"
            val unchanged = !settled.changed && !settled.blind
            if (step.string("action") == "tap" && unchanged && settled.expectMet == null && index < steps.lastIndex && !waitsNext) {
                lines.add("$number. [tap changed nothing] ${result.done} · ${settled.ms} ms. Stopped, since the next steps expect a new screen.")
                stopped = true
                break
            }
            lines.add("$number. [ok] ${result.done} · $change · ${settled.ms} ms")
        }
        if (screen.isEmpty() && !screenShown) screen = actions.readText()
        val total = SystemClock.uptimeMillis() - started
        val stoppedAt = lines.size
        val summary = when {
            !stopped -> "Ran all ${steps.size} steps in $total ms."
            stoppedAt == steps.size - 1 -> "Stopped at step $stoppedAt of ${steps.size} after $total ms; step ${steps.size} not run."
            stoppedAt < steps.size -> "Stopped at step $stoppedAt of ${steps.size} after $total ms; steps ${stoppedAt + 1}–${steps.size} not run."
            else -> "Stopped at the last step, ${steps.size}, after $total ms."
        }
        return ToolOutput("$summary\n${lines.joinToString("\n")}\n\n$screen", timings = mapOf("steps_ms" to total))
    }

    private class StepResult(val done: String, val settled: Settled?, val blocked: String? = null)

    private suspend fun runStep(step: ToolArgs, first: Boolean): StepResult {
        val action = step.requireString("action")
        if (!first && step.int("element_id") != null) throw ToolFailure("element_id works only in the first step; use target text")
        if (first && (step.int("element_id") != null || step.int("x") != null)) actions.requireSameApp()
        if (action == "tap" && Handset.instance.askBeforeSending) {
            needsApproval(step)?.let { prompt ->
                return StepResult("", null, blocked = "$prompt needs the user's approval. Call tap for this step on its own, then run_steps for the rest.")
            }
        }
        val before = actions.beforeText()
        // wait_for, and an app or page that opens, are met by text already there; any other step's expect must appear after it
        val expectNew = action !in PRESENCE_ACTIONS
        val beforeMarks = step.string("expect")?.takeIf { expectNew }?.let { actions.marks(it) }
        var waitFor: String? = null
        var limit = PhoneActions.SETTLE_LIMIT_MS
        val done = when (action) {
            "tap" -> actions.tap(
                Target.from(step) ?: throw ToolFailure("tap needs target, element_id, or x and y"),
                step.boolean("long") == true,
                step.boolean("scroll_to_find") == true,
            )
            "type_text" -> actions.type(Target.from(step), step.requireString("text"), step.boolean("submit") == true)
            "scroll" -> actions.scroll(Target.from(step), step.requireString("direction"))
            "press" -> actions.press(step.requireString("key"))
            "open_app" -> {
                val (said, packageName) = actions.openApp(step.requireString("app"), step.boolean("fresh") == true)
                waitFor = packageName
                limit = PhoneActions.LAUNCH_LIMIT_MS
                said
            }
            "open_url" -> {
                limit = PhoneActions.LAUNCH_LIMIT_MS
                val (said, handler) = actions.openUrl(step.requireString("url"), step.string("package"))
                waitFor = handler
                said
            }
            "web_search" -> {
                limit = PhoneActions.LAUNCH_LIMIT_MS
                val query = step.requireString("query")
                waitFor = actions.openUrl("https://www.google.com/search?q=" + Uri.encode(query), null).second
                "Searched for \"$query\""
            }
            "wait_for" -> {
                limit = WAIT_DEFAULT_MS
                "Waited"
            }
            else -> throw ToolFailure("Unknown action $action. Use one of ${ACTIONS.joinToString()}.")
        }
        val acted = SystemClock.uptimeMillis()
        val expect = step.string("expect") ?: step.string("text").takeIf { action == "wait_for" }
        if (action == "wait_for" && expect == null && step.string("app") == null) throw ToolFailure("wait_for needs expect text or app")
        val appToWait = waitFor ?: step.string("app").takeIf { action == "wait_for" }
        val timeout = step.int("timeout_ms")?.toLong() ?: if (expect != null) maxOf(limit, EXPECT_DEFAULT_MS) else limit
        val settled = actions.settle(acted, before, appToWait?.let { resolvePackage(it) }, expect, timeout, expectNew, beforeMarks)
        return StepResult(done, settled)
    }

    /** wait_for's app may be a name; settle waits on a package */
    private fun resolvePackage(app: String): String =
        actions.launchableApps().firstOrNull { it.first.equals(app, ignoreCase = true) || it.second.equals(app, ignoreCase = true) }?.second ?: app

    private companion object {
        const val MAX_STEPS = 20
        const val SCREEN_NOW = "The screen now:"
        const val EXPECT_DEFAULT_MS = 5_000L
        const val WAIT_DEFAULT_MS = 10_000L
        val ACTIONS = listOf("tap", "type_text", "scroll", "press", "open_app", "open_url", "web_search", "wait_for")
        val PRESENCE_ACTIONS = setOf("wait_for", "open_app", "open_url", "web_search")
    }
}
