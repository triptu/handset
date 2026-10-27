package ai.tushar.handset.tools

import ai.tushar.handset.Handset
import ai.tushar.handset.device.PhoneAccessibilityService
import ai.tushar.handset.device.ScreenReader
import ai.tushar.handset.device.ScreenSnapshot
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.Browser
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.net.URISyntaxException

/** Where an action lands: an element id from the last screen read, an element found by its text, or a point */
sealed interface Target {
    data class Id(val id: Int) : Target
    data class Text(val text: String) : Target
    data class Point(val x: Int, val y: Int) : Target

    companion object {
        /** From `element_id`, then [textKey] (an element's text, hint or view id), then `x` and `y`; null when none is given */
        fun from(args: ToolArgs, textKey: String = "target"): Target? =
            args.int("element_id")?.let(::Id)
                ?: args.string(textKey)?.let(::Text)
                ?: args.int("x")?.let { Point(it, args.requireInt("y")) }
    }
}

/**
 * The screen after an action, once it settled, and how long that took. [appInFront] is whether the app an action
 * opened came to the front, or null when it opened none. [blind] is set when the screen, before or after, had no
 * readable elements, so whether it changed can't be told from them.
 */
class Settled(
    val screen: String,
    val app: String?,
    val changed: Boolean,
    val ms: Long,
    val expectMet: Boolean?,
    val appInFront: Boolean? = null,
    val blind: Boolean = false,
)

/**
 * The actions of the phone tools, shared by the single tools and run_steps: each acts, then [settle] returns the screen
 * once it has stopped changing. The phone is fast, so every wait follows the screen's own events instead of a clock.
 */
class PhoneActions(private val context: Context) {
    private val phone get() = PhoneAccessibilityService.require()

    /** The element a target names, with the id and label it is reported by */
    /** An element of the screen. [pages] is how many pages were scrolled to find it. */
    class Element(val id: Int, val node: AccessibilityNodeInfo, val label: String, val pages: Int = 0)

    /** The element a target text names on the screen as it is now, without waiting for it, or null */
    suspend fun elementNow(target: Target.Text): Element? =
        attempt { phone.find(target.text, avoidFields = true) }.getOrNull()?.let { (id, node) -> Element(id, node, ScreenReader.labelOf(node)) }

    /**
     * The element, or a failure that carries the screen as it is now, so the agent needs no extra read. A target text
     * gets [TARGET_WAIT_MS] to appear, as when the last step settled just before the next screen came up. A tap passes
     * [avoidFields], so a field that shows the text just typed loses to the suggestions that match it.
     */
    suspend fun element(target: Target, avoidFields: Boolean = false): Element = try {
        when (target) {
            is Target.Id -> phone.element(target.id).let { Element(target.id, it, ScreenReader.labelOf(it)) }
            is Target.Text -> waitFor { attempt { phone.find(target.text, avoidFields) }.getOrNull() }
                ?.let { (id, node) -> Element(id, node, ScreenReader.labelOf(node)) }
                ?: phone.find(target.text, avoidFields).let { (id, node) -> Element(id, node, ScreenReader.labelOf(node)) }
            is Target.Point -> throw ToolFailure("This needs an element, not a point")
        }
    } catch (failure: ToolFailure) {
        if (target is Target.Point) throw failure
        throw ToolFailure("${failure.message?.substringBefore(" Call get_screen")} The screen now:\n\n${readText()}")
    }

    /** The element under a point on the screen as it is now, or null when no element covers it */
    suspend fun elementAt(point: Target.Point): Element? {
        val screen = phone.peekScreen()
        val id = screen.at(point.x, point.y) ?: return null
        val node = screen.element(id) ?: return null
        return Element(id, node, ScreenReader.labelOf(node))
    }

    /**
     * The element a target text names, scrolling down a page at a time until it shows, up to [MAX_FIND_SCROLLS] pages, as
     * [scrollPage] picks the list. When nothing moves, the search ends.
     */
    suspend fun findScrolling(target: Target, avoidFields: Boolean = false): Element {
        if (target !is Target.Text) return element(target, avoidFields)
        for (page in 0..MAX_FIND_SCROLLS) {
            attempt { phone.find(target.text, avoidFields) }.getOrNull()?.let { (id, node) -> return Element(id, node, ScreenReader.labelOf(node), page) }
            if (page == MAX_FIND_SCROLLS || !scrollPage("down")) break
            // The list may still be moving; its elements are read once it stops, so the tap lands where they end up
            settle(SystemClock.uptimeMillis(), null, limitMs = FIND_SCROLL_LIMIT_MS)
        }
        return element(target, avoidFields)
    }

    /**
     * Scrolls a page in [direction]. It tries the lists in [ScreenSnapshot.scrollOrder], the one with the most items first,
     * and a list counts as moved only when its own items change, so a header that collapses above it doesn't count. When no
     * list takes the scroll action, it swipes across the first one, as some lists only follow a finger. False when nothing
     * moved, as at the end of every list.
     */
    suspend fun scrollPage(direction: String): Boolean {
        val screen = phone.currentScreen()
        val lists = screen.scrollOrder(direction).mapNotNull { screen.element(it) }
        for (list in lists) {
            live()
            val before = itemsOf(list)
            if (phone.scroll(list, direction) && moved(list, before)) return true
        }
        val first = lists.firstOrNull()
        live()
        if (first == null) {
            val before = beforeText()
            val started = SystemClock.uptimeMillis()
            swipePage(direction, null)
            return settle(started, before, limitMs = FIND_SCROLL_LIMIT_MS).changed
        }
        val before = itemsOf(first)
        swipePage(direction, Rect().also { first.getBoundsInScreen(it) })
        return moved(first, before)
    }

    /** What a list shows, as its items and where they are, to tell whether it moved */
    private fun itemsOf(list: AccessibilityNodeInfo): String {
        list.refresh()
        val bounds = Rect()
        return (0 until list.childCount).joinToString("\n") { index ->
            val item = list.getChild(index) ?: return@joinToString ""
            item.refresh()
            item.getBoundsInScreen(bounds)
            "${item.viewIdResourceName} $bounds ${ScreenReader.labelOf(item)}"
        }
    }

    /** Whether [list]'s items change within [LIST_MOVE_MS] of a scroll, as they do once it starts to move */
    private suspend fun moved(list: AccessibilityNodeInfo, before: String): Boolean {
        val deadline = SystemClock.uptimeMillis() + LIST_MOVE_MS
        while (SystemClock.uptimeMillis() < deadline) {
            delay(LIST_POLL_MS)
            if (itemsOf(list) != before) return true
        }
        return false
    }

    /** [look]'s answer, looked for again at each change of the screen for up to [TARGET_WAIT_MS]; null if it never comes */
    private suspend fun <T : Any> waitFor(look: suspend () -> T?): T? {
        val deadline = SystemClock.uptimeMillis() + TARGET_WAIT_MS
        while (true) {
            look()?.let { return it }
            val seen = phone.lastChangeAt
            while (phone.lastChangeAt == seen && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
            if (SystemClock.uptimeMillis() >= deadline) return null
        }
    }

    /** Stops here when the call was cancelled, as by Stop, so nothing on the phone changes after it */
    private suspend fun live() = currentCoroutineContext().ensureActive()

    /** Taps, and returns what was tapped as the result's first words. With [scrollToFind], a target text is scrolled to first. */
    suspend fun tap(target: Target, long: Boolean, scrollToFind: Boolean = false): String {
        live()
        val verb = if (long) "Long-pressed" else "Tapped"
        return when (target) {
            is Target.Point -> {
                elementAt(target)?.let { requireApproval(it, "is at that point") }
                phone.tapAt(target.x.toFloat(), target.y.toFloat(), long)
                "$verb at ${target.x},${target.y}"
            }
            else -> {
                val element = if (scrollToFind) findScrolling(target, avoidFields = true) else element(target, avoidFields = true)
                // The approval check ran before the call, when the element may not have been on screen yet
                requireApproval(element, if (scrollToFind) "was found by scrolling" else "came on screen as the tap ran")
                // A row whose label joins several texts may do nothing when tapped in its middle, so the text is tapped where it is drawn
                val inner = (target as? Target.Text)?.let { innerText(element, it.text) }
                live()
                if (inner != null) {
                    val bounds = Rect().also { inner.getBoundsInScreen(it) }
                    phone.tapAt(bounds.exactCenterX(), bounds.exactCenterY(), long)
                } else {
                    phone.tap(element.node, long)
                }
                // When several elements match a target text, the agent should know which one it got
                val others = (target as? Target.Text)?.let { phone.currentScreen().matchCount(it.text) - 1 }?.takeIf { it > 0 }
                val note = others?.let { " (${it + 1} elements matched \"${(target as Target.Text).text}\"; tapped the best match)" }.orEmpty()
                val what = inner?.let { "\"${ScreenReader.clip(ScreenReader.ownLabel(it).orEmpty())}\" in " }.orEmpty()
                val scrolled = when (element.pages) {
                    0 -> ""
                    1 -> " after scrolling 1 page"
                    else -> " after scrolling ${element.pages} pages"
                }
                "$verb $what[${element.id}] \"${ScreenReader.clip(element.label)}\"$scrolled$note"
            }
        }
    }

    /**
     * The text inside [element] that [query] names, when the element's label joins several texts and the query is only one
     * of them. Null when the label is the query itself, or nothing inside matches.
     */
    private fun innerText(element: Element, query: String): AccessibilityNodeInfo? {
        val wanted = query.trim()
        if (element.label.equals(wanted, ignoreCase = true) || element.node.childCount == 0) return null
        var best: AccessibilityNodeInfo? = null
        var bestRank = Int.MAX_VALUE
        val bounds = Rect()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > INNER_DEPTH) return
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                if (!child.isVisibleToUser) continue
                val text = ScreenReader.ownLabel(child)
                val rank = when {
                    text == null -> null
                    text.equals(wanted, ignoreCase = true) -> 0
                    text.startsWith(wanted, ignoreCase = true) -> 1
                    text.contains(wanted, ignoreCase = true) -> 2
                    else -> null
                }
                child.getBoundsInScreen(bounds)
                if (rank != null && rank < bestRank && !bounds.isEmpty) {
                    best = child
                    bestRank = rank
                }
                visit(child, depth + 1)
            }
        }
        visit(element.node, 0)
        return best
    }

    /** What the user is asked to allow before [element] is tapped: when its label sends or pays, and the user asks to approve those */
    fun approvalPrompt(element: Element): String? {
        if (!Handset.instance.askBeforeSending || !SENDING_WORDS.containsMatchIn(element.label)) return null
        return "Tap \"${ScreenReader.clip(element.label)}\" in ${phone.currentAppLabel() ?: "the current app"}"
    }

    /** Refuses to tap an element that needs approval the user didn't give for it in this call */
    private suspend fun requireApproval(element: Element, how: String) {
        val prompt = approvalPrompt(element) ?: return
        if (currentCoroutineContext()[Approval]?.prompt == prompt) return
        throw ToolFailure(
            "\"${ScreenReader.clip(element.label)}\" $how, and tapping it needs the user's approval. Call tap for it again, " +
                "without scroll_to_find, and the user will be asked.",
        )
    }

    /** Sets a field's text, checks the field took it, and presses enter when [submit] is set */
    suspend fun type(target: Target?, text: String, submit: Boolean): String {
        // Without a target: the focused field, or else the screen's first field, as a search page's box before it takes focus,
        // or else a search button that opens one; or a field that shows up in a moment
        suspend fun firstField() = phone.currentScreen().elements.firstOrNull { it.isEditable && it.isVisibleToUser }
        val named = target?.let { element(it) }
        val node = named?.node
            ?: phone.focusedInput()
            ?: firstField()
            ?: searchButton()
            ?: waitFor { firstField() }
            ?: throw ToolFailure("No text field on screen. Give the field's element_id or its text as target.")
        // A search bar or a row that opens a field, as "Where to?" in Uber, is tapped as tap would, Send and Pay approval included
        val field = when {
            node.isEditable -> node
            target != null -> openField { tap(target, long = false) }
            else -> openField { phone.tap(node, long = false) }
        }
        val before = fieldValue(field)
        live()
        // A field that has just appeared, as on a search page sliding in, can refuse text for a moment
        var tries = 0
        while (true) {
            val taken = attempt { phone.setText(field, text) }
            if (taken.isSuccess) break
            if (++tries >= SET_TEXT_TRIES) throw taken.exceptionOrNull()!!
            delay(SET_TEXT_RETRY_MS)
            field.refresh()
        }
        val actual = readBack(field, text)
        if (!sameText(actual, text) && actual == before) {
            throw ToolFailure("The field did not take the text; it still shows \"${ScreenReader.clip(actual)}\". Tap the keys on screen instead.")
        }
        val typed = if (sameText(actual, text)) "Typed \"${ScreenReader.clip(text)}\"" else "Typed, but the field shows \"${ScreenReader.clip(actual)}\""
        return if (submit && phone.pressEnter(field)) "$typed and submitted" else typed
    }

    /** Most apps' search bars are buttons that open the real field: this taps one and returns the field that takes focus */
    private suspend fun openField(tapIt: suspend () -> Unit): AccessibilityNodeInfo {
        live()
        val tapped = SystemClock.uptimeMillis()
        tapIt()
        while (phone.lastChangeAt <= tapped && SystemClock.uptimeMillis() - tapped < FIELD_OPEN_MS) delay(POLL_MS)
        return waitFor { phone.focusedInput()?.takeIf { it.isEditable } ?: phone.currentScreen().elements.firstOrNull { it.isEditable && it.isVisibleToUser } }
            ?: throw ToolFailure("That element is not a text field, and tapping it opened none. The screen now:\n\n${readText()}")
    }

    /**
     * A search bar that opens a search field, for type_text with no field on screen. It must sit in the top part of the
     * screen, as search bars do, so a Search tab at the bottom is not taken for one.
     */
    private suspend fun searchButton(): AccessibilityNodeInfo? {
        val screen = phone.currentScreen()
        val bounds = Rect()
        val top = context.resources.displayMetrics.heightPixels * SEARCH_BAR_TOP
        return screen.elements.firstOrNull { node ->
            node.getBoundsInScreen(bounds)
            node.isClickable && node.isVisibleToUser && bounds.centerY() < top &&
                (SEARCH.containsMatchIn(ScreenReader.labelOf(node)) || viewIdOf(node).contains("search"))
        }
    }

    private fun viewIdOf(node: AccessibilityNodeInfo): String = node.viewIdResourceName?.substringAfter(":id/")?.lowercase().orEmpty()

    /** A field answers a set text at once in most apps, so it is read back as soon as it shows the text, or after [FIELD_READ_MS] */
    private suspend fun readBack(node: AccessibilityNodeInfo, text: String): String {
        val deadline = SystemClock.uptimeMillis() + FIELD_READ_MS
        while (true) {
            node.refresh()
            val actual = fieldValue(node)
            if (sameText(actual, text) || SystemClock.uptimeMillis() >= deadline) return actual
            delay(POLL_MS)
        }
    }

    /** Scrolls the named list, or the one [scrollPage] picks; says when nothing moved, as at the end of the list */
    suspend fun scroll(target: Target?, direction: String): String {
        if (target == null) return if (scrollPage(direction)) "Scrolled $direction" else "Scrolled $direction, but no list moved"
        val list = element(target).node
        live()
        val before = itemsOf(list)
        if (phone.scroll(list, direction) && moved(list, before)) return "Scrolled $direction"
        live()
        swipePage(direction, Rect().also { list.getBoundsInScreen(it) })
        return if (moved(list, before)) "Scrolled $direction" else "Scrolled $direction, but the list didn't move"
    }

    /** Moves a list by a swipe across the middle of [area], or of the screen, for a list that takes no scroll action */
    private suspend fun swipePage(direction: String, area: Rect?) {
        val metrics = context.resources.displayMetrics
        val box = area?.takeIf { !it.isEmpty } ?: Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        val centerX = box.exactCenterX()
        val centerY = box.exactCenterY()
        val dy = box.height() * SWIPE_FRACTION
        val dx = box.width() * SWIPE_FRACTION
        when (direction) {
            "down" -> phone.swipe(centerX, centerY + dy / 2, centerX, centerY - dy / 2, SWIPE_MS)
            "up" -> phone.swipe(centerX, centerY - dy / 2, centerX, centerY + dy / 2, SWIPE_MS)
            "right" -> phone.swipe(centerX + dx / 2, centerY, centerX - dx / 2, centerY, SWIPE_MS)
            else -> phone.swipe(centerX - dx / 2, centerY, centerX + dx / 2, centerY, SWIPE_MS)
        }
    }

    suspend fun press(key: String): String {
        live()
        phone.globalAction(key)
        return "Pressed $key"
    }

    /** Opens an app, and returns what to say and the package to wait for */
    suspend fun openApp(wanted: String, fresh: Boolean): Pair<String, String> {
        val apps = launchableApps()
        val match = apps.firstOrNull { it.second.equals(wanted, ignoreCase = true) }
            ?: apps.firstOrNull { it.first.equals(wanted, ignoreCase = true) }
            ?: apps.firstOrNull { it.first.contains(wanted, ignoreCase = true) }
            ?: throw ToolFailure("No app named $wanted. Call list_apps to see the installed apps.")
        val intent = context.packageManager.getLaunchIntentForPackage(match.second) ?: throw ToolFailure("${match.first} cannot be opened")
        if (fresh) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        live()
        phone.launch(intent)
        return "Opened ${match.first}" to match.second
    }

    /**
     * Opens a link, and returns what to say and the package of the app that opens it, to wait for. A link several apps
     * can open shows Android's chooser, so there is no app to wait for.
     */
    suspend fun openUrl(url: String, packageName: String?): Pair<String, String?> {
        val intent = linkIntent(url)
        packageName?.let { intent.setPackage(it) }
        // MATCH_DEFAULT_ONLY resolves the way startActivity does
        val handler = packageName ?: context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeIf { it != CHOOSER }
        live()
        phone.launch(intent)
        return "Opened $url" to handler
    }

    /** An intent for a link. Web links carry this app's id, so Chrome opens them all in one tab instead of a new tab each. */
    fun linkIntent(url: String): Intent {
        val intent = if (url.startsWith("intent:")) {
            try {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } catch (_: URISyntaxException) {
                throw ToolFailure("That intent: link could not be read")
            }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }
        if (url.startsWith("http://") || url.startsWith("https://")) intent.putExtra(Browser.EXTRA_APPLICATION_ID, context.packageName)
        return intent
    }

    /** The apps with a launcher icon, as label and package, read once and again only after an app is installed or removed */
    fun launchableApps(): List<Pair<String, String>> = apps ?: phone.launchableApps().also { apps = it }

    fun forgetApps() {
        apps = null
    }

    @Volatile private var apps: List<Pair<String, String>>? = null

    /**
     * Element ids and points belong to the last screen read. When another app has come to the front since, as an incoming
     * call does, acting on them would hit the wrong thing, so this fails with the screen as it is now.
     */
    suspend fun requireSameApp() {
        val read = phone.snapshotPackage ?: return
        val now = phone.currentPackage() ?: return
        if (now != read) {
            throw ToolFailure("${phone.appLabel(now)} ($now) came to the front since the last screen was read. The screen now:\n\n${readText()}")
        }
    }

    /**
     * Returns the screen once it is done changing. After [startedAt], the action has [FIRST_CHANGE_MS] to change anything,
     * and a screen that still reads as it did [before] gets until [UNCHANGED_WAIT_MS], as a page a tap opens can be slow to
     * show; a tap that changes nothing returns then. Otherwise the screen must go [QUIET_MS] without a change, with no
     * spinner and no window still sliding into place, or read the same twice in a row while something keeps animating. An opened app first has to come to the
     * front. With [expect], it waits until that text is on screen; with [expectNew], text that was already on screen
     * [before] counts only once the screen has changed, so it waits for the next screen. Everything stops at [limitMs].
     */
    suspend fun settle(
        startedAt: Long,
        before: String?,
        waitForPackage: String? = null,
        expect: String? = null,
        limitMs: Long = SETTLE_LIMIT_MS,
        expectNew: Boolean = true,
        beforeMarks: Set<String>? = null,
    ): Settled {
        val deadline = startedAt + limitMs
        fun now() = SystemClock.uptimeMillis()
        // A launch takes longer to show anything, and the app may already be in front, so it also waits for a first change
        val firstChange = if (waitForPackage != null || limitMs >= LAUNCH_LIMIT_MS) LAUNCH_FIRST_CHANGE_MS else FIRST_CHANGE_MS
        if (waitForPackage != null) {
            while (phone.currentPackage() != waitForPackage && now() < deadline) delay(POLL_MS)
        }
        while (phone.lastChangeAt <= startedAt && now() < minOf(startedAt + firstChange, deadline)) delay(POLL_MS)
        var previous: String? = null
        var churn: Set<Int>? = null
        var last: ScreenSnapshot? = null
        var lastText = ""
        val shownBefore = expectNew && expect != null && before?.contains(expect, ignoreCase = true) == true
        // With the marks taken before the action, the text must show in a node that didn't hold it then, as a page that
        // comes up does; a page under it that shows through while it loads doesn't count
        fun expectMet(screen: ScreenSnapshot?, text: String) = when {
            expect == null -> true
            expectNew && beforeMarks != null -> screen?.showsNew(expect, beforeMarks) == true
            else -> text.contains(expect, ignoreCase = true) && (!shownBefore || text != before)
        }
        while (true) {
            val readFrom = now()
            while (now() - phone.lastChangeAt < QUIET_MS && now() - readFrom < STABLE_CHECK_MS && now() < deadline) delay(POLL_MS)
            val read = attempt { phone.readScreen() }
            last = read.getOrNull() ?: last
            lastText = read.map { it.text }.getOrElse { it.message.orEmpty() }
            val quiet = now() - phone.lastChangeAt >= QUIET_MS
            // Something that changes on every read, as a clock or a counter in a widget, doesn't keep the rest from settling
            val changedLines = changedLines(previous, lastText)
            val churning = churn != null && changedLines != null && changedLines.size <= MAX_CHURN_LINES && churn!!.containsAll(changedLines)
            val stable = previous == lastText || churning
            val expectMet = expectMet(read.getOrNull(), lastText)
            // A window that is still drawing, as between two pages, has no elements yet; a game or a video may never have any
            val drawing = now() - phone.lastWindowChangeAt < PhoneAccessibilityService.WINDOW_SETTLE_MS
            val loading = read.getOrNull()?.let { it.loading || it.moving || (it.elements.isEmpty() && drawing) } == true
            // A tap that opens a page can take a moment to show it, so an unchanged screen gets a little longer
            val unchanged = before != null && lastText == before && now() < startedAt + UNCHANGED_WAIT_MS
            if (now() >= deadline || (expectMet && !loading && !unchanged && (quiet || stable))) break
            previous = lastText
            churn = changedLines
            // Quiet but not done, as while a page waits on the network: read again at the next change, or soon
            if (quiet) {
                val seen = phone.lastChangeAt
                val since = now()
                while (phone.lastChangeAt == seen && now() - since < STABLE_CHECK_MS && now() < deadline) delay(POLL_MS)
            }
        }
        val expectMet = expect?.let { expectMet(last, lastText) }
        val appInFront = waitForPackage?.let { last?.packageName == it }
        val blind = last == null || last.nodes.isEmpty() || before?.contains(ScreenReader.NO_ELEMENTS) == true
        return Settled(lastText, last?.appLabel, before != null && lastText != before, now() - startedAt, expectMet, appInFront, blind)
    }

    /** Where [text] shows now, hidden layers included, for an action's expect to need something new: see [settle] */
    suspend fun marks(text: String): Set<String>? = attempt { phone.marks(text) }.getOrNull()

    /** Which lines differ between two reads of the same screen, or null when they aren't the same screen */
    private fun changedLines(previous: String?, current: String): Set<Int>? {
        val before = previous?.lines() ?: return null
        val after = current.lines()
        if (before.size != after.size) return null
        return before.indices.filterTo(HashSet()) { before[it] != after[it] }
    }

    /** The screen as text, or why it can't be read */
    suspend fun readText(): String = attempt { phone.readScreen().text }.getOrElse { it.message.orEmpty() }

    /**
     * The screen as it is before an action, to tell whether the action changed it. It leaves the agent's element ids as
     * they were: a fresh read would number the elements again, and an id could then name another button.
     */
    suspend fun beforeText(): String? = attempt { phone.peekText() }.getOrNull()

    /** What a field holds; a hint shown in an empty field is not its text */
    private fun fieldValue(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString().orEmpty()
        return if (node.hintText?.toString() == text) "" else text
    }

    /** Fields format what they take, as a phone number gets spaces, so only letters and digits are compared */
    private fun sameText(a: String, b: String): Boolean = a.filter { it.isLetterOrDigit() }.equals(b.filter { it.isLetterOrDigit() }, ignoreCase = true)

    companion object {
        /** How long an action has to start changing the screen before it counts as having changed nothing */
        const val FIRST_CHANGE_MS = 120L
        const val LAUNCH_FIRST_CHANGE_MS = 600L
        const val UNCHANGED_WAIT_MS = 600L

        /** How long the screen must go without a change to count as settled */
        const val QUIET_MS = 180L

        /** How often the screen is read while something keeps animating, to see whether its elements still change */
        const val STABLE_CHECK_MS = 250L

        /** How many lines may keep changing on every read while the screen still counts as settled */
        const val MAX_CHURN_LINES = 2
        const val POLL_MS = 16L
        const val SETTLE_LIMIT_MS = 3_000L

        /** Opening an app or a page can wait on the network, so it gets longer */
        const val LAUNCH_LIMIT_MS = 6_000L
        const val FIELD_READ_MS = 150L

        /** How long a tapped search bar has to start opening its field */
        const val FIELD_OPEN_MS = 600L

        /** The word in a search bar's label */
        val SEARCH = Regex("\\bsearch\\b", RegexOption.IGNORE_CASE)

        /** The share of the screen from the top that search bars sit in */
        const val SEARCH_BAR_TOP = 0.35f
        const val TARGET_WAIT_MS = 1_000L
        const val SET_TEXT_TRIES = 5
        const val SET_TEXT_RETRY_MS = 100L

        /** The package of Android's "Open with" chooser */
        const val CHOOSER = "android"

        /** Labels of buttons that send or pay, which wait for the user's Allow when they ask for that */
        val SENDING_WORDS = Regex("\\b(send|pay|buy|order|transfer|post|publish)\\b", RegexOption.IGNORE_CASE)
        const val MAX_FIND_SCROLLS = 6
        const val INNER_DEPTH = 8
        const val FIND_SCROLL_LIMIT_MS = 1_500L

        /** How long a scrolled list has to start moving, and how often it is checked */
        const val LIST_MOVE_MS = 600L
        const val LIST_POLL_MS = 40L
        const val SWIPE_MS = 300L
        const val SWIPE_FRACTION = 0.6f
    }
}

/**
 * The first line of an action's result: whether it worked, whether the screen's elements changed, the app in front, and
 * how long the screen took to settle. The screen follows.
 */
fun Settled.report(done: String, expect: String? = null, unchangedHint: String = TAP_UNCHANGED): String {
    val status = when {
        appInFront == false -> "[app did not come to the front]"
        expectMet == false -> "[expect not met: \"$expect\"]"
        else -> "[ok]"
    }
    // The elements are compared, not the pixels: a Quick Settings tile lights up without updating its element
    val change = when {
        blind -> BLIND
        changed -> "elements changed"
        else -> "elements unchanged ($unchangedHint)"
    }
    return "$status $done · $change · in ${app ?: "an unknown app"} · $ms ms\n\n$screen"
}

/** What a status line says when the screen has no readable elements to compare */
const val BLIND = "can't tell whether the screen changed: it has no readable elements; take a screenshot to see"

const val TAP_UNCHANGED = "a toggle or tile may have changed look only; don't retry blindly"
const val SCROLL_UNCHANGED = "probably the end of the list"
