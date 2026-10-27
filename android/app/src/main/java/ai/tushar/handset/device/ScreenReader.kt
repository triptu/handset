package ai.tushar.handset.device

import android.view.accessibility.AccessibilityNodeInfo
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The screen as the agent reads it: one line per element that has text or can be acted on, indented by nesting,
 * each with an id that the action tools take. A clickable row with no text of its own is labelled with the
 * text inside it, and that text is not listed again.
 */
class ScreenSnapshot(
    /** The tree the snapshot was read from, to look again at what is visible */
    val root: UiNode,
    val width: Int,
    val height: Int,
    val packageName: String,
    val appLabel: String,
    /** The listed elements, in the order of their ids */
    val nodes: List<UiNode>,
    /** What each element in [nodes] can be found by: its label, hint and view id, and whether it can be acted on */
    private val keys: List<ElementKey>,
    val text: String,
    /** A spinner is on screen, as while a page or a list loads */
    val loading: Boolean,
    /**
     * The window is still sliding into place, as a page that opens does: it reaches past the screen's edge. Android sends no
     * events while it moves and keeps the positions from when it started, so the positions are wrong until it stops.
     */
    val moving: Boolean = false,
) {
    class ElementKey(
        val text: String,
        val actionable: Boolean,
        val editable: Boolean,
        val onScreen: Boolean,
        val bounds: Box,
        val toggle: Boolean = false,
    )

    /** The listed elements as Android's nodes, to act on */
    val elements: List<AccessibilityNodeInfo> by lazy { nodes.mapNotNull { it.info } }

    fun element(id: Int): AccessibilityNodeInfo? = nodes.getOrNull(id - 1)?.info

    /**
     * The id of the element that [query] names, matched without case. The closest match wins: the element's whole text,
     * then one part of it (a row's label joins its texts with ·), then a part that starts with the query, then any part
     * that contains it. Among equal matches, one that can be acted on, then one on screen, then one that isn't a switch,
     * since a switch with its row's label would toggle where the row opens; then the smallest, as the button inside a
     * row rather than the row; then the first in reading order. With [avoidFields], as for a tap, a text
     * field, or a row that holds one, comes after every other match, since it shows the text just typed into it. Null when
     * nothing matches.
     */
    fun find(query: String, avoidFields: Boolean = false): Int? {
        val wanted = query.trim().lowercase()
        if (wanted.isEmpty()) return null
        return keys.indices
            .mapNotNull { index -> matchRank(keys[index].text.lowercase(), wanted)?.let { index to it } }
            .minWithOrNull(
                compareBy(
                    { avoidFields && keys[it.first].editable },
                    { it.second },
                    { !keys[it.first].actionable },
                    { !keys[it.first].onScreen },
                    { keys[it.first].toggle },
                    { keys[it.first].bounds.area },
                    { it.first },
                ),
            )
            ?.first?.plus(1)
    }

    /** 0 for the whole text, 1 for a whole part, 2 for a part that starts with [wanted], 3 for any containing; null for none */
    private fun matchRank(text: String, wanted: String): Int? {
        if (text == wanted) return 0
        if (wanted !in text) return null
        val parts = text.split(" · ", " (", ")").map { it.trim() }
        return when {
            parts.any { it == wanted } -> 1
            parts.any { it.startsWith(wanted) } -> 2
            else -> 3
        }
    }

    /** The id of the element a tap at [x], [y] lands on: the smallest one there that can be acted on, else the smallest one there */
    fun at(x: Int, y: Int): Int? = keys.indices
        .filter { keys[it].onScreen && keys[it].bounds.contains(x, y) }
        .minWithOrNull(compareBy({ !keys[it].actionable }, { keys[it].bounds.area }))
        ?.plus(1)

    /**
     * The ids of the lists to scroll in [direction], in the order to try them: the one with the most items first, as the
     * list of alarms inside a page that scrolls as a whole, then by size. A list that only scrolls the other way is left
     * out. A list marked disabled stays, since some still scroll.
     */
    fun scrollOrder(direction: String): List<Int> {
        val vertical = direction == "up" || direction == "down"
        return nodes.indices
            .filter { nodes[it].scrollable && nodes[it].visible }
            .filter { index -> nodes[index].axes.let { it.isEmpty() || it.contains(if (vertical) 'v' else 'h') } }
            .sortedWith(compareByDescending<Int> { nodes[it].children.size }.thenByDescending { keys[it].bounds.area })
            .map { it + 1 }
    }

    /** How many elements match [query], to warn when a text target was ambiguous */
    fun matchCount(query: String): Int = keys.count { it.text.contains(query.trim(), ignoreCase = true) }

    /**
     * Whether [text] shows on this screen in a node that did not hold it among [before], as [ScreenReader.marks] took them
     * before an action: a new node, or one whose text changed. Only what the agent sees counts, so text on a page that
     * the next one covers, or that showed before and is still there, does not.
     */
    fun showsNew(text: String, before: Set<String>): Boolean = (ScreenReader.visibleMarks(root, text, width, height) - before).isNotEmpty()
}

object ScreenReader {
    private const val MAX_ELEMENTS = 400
    private const val MAX_VISITED = 6_000
    private const val MAX_LABEL = 200
    private const val MAX_DEPTH = 60

    /**
     * A layer hides the ones under it only when it takes up [MIN_LAYER_SHARE] of the screen, and its text, at least
     * [MIN_LAYER_TEXTS] pieces of it, runs down [MIN_LAYER_FILL] of what it covers with no empty band over [MAX_LAYER_GAP]
     */
    private const val MIN_LAYER_SHARE = 0.3
    private const val MIN_LAYER_FILL = 0.5
    private const val MAX_LAYER_GAP = 0.3
    private const val MIN_LAYER_TEXTS = 4
    private const val MAX_LAYER_VISITS = 300

    /** How deep under an element a text field makes it count as one, for a tap that avoids fields */
    private const val FIELD_DEPTH = 8

    /** A card's own label must be this long to stand for the texts inside it, as a product card's summary does */
    private const val MIN_SUMMARY = 20

    /**
     * A label that is only a media file's address, or its name with a query, as "960px-Banana.jpg?utm_source=app". A plain
     * file name, as in a file picker, stays.
     */
    private val MEDIA_FILE = Regex("\\S*(/\\S*\\.$MEDIA_TYPES(\\?\\S*)?|\\.$MEDIA_TYPES\\?\\S*)", RegexOption.IGNORE_CASE)
    private const val MEDIA_TYPES = "(jpe?g|png|gif|webp|svg|avif|heic|bmp|mp4|webm|mov)"

    /** A description that is only a link with a query, or a long one */
    private val BARE_LINK = Regex("(https?://|www\\.)\\S*(\\?\\S*|\\S{40,})", RegexOption.IGNORE_CASE)

    /** Descriptions that name the kind of view and nothing about it */
    private val GENERIC_LABELS = setOf("image", "icon", "button", "picture", "photo", "view")

    /** Zero-width characters, and the private-use characters that icon fonts draw, which read as nothing */
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u2060\\uFEFF\\uE000-\\uF8FF]")

    private class Walk(val width: Int, val height: Int) {
        val nodes = ArrayList<UiNode>()
        val keys = ArrayList<ScreenSnapshot.ElementKey>()
        val lines = ArrayList<String>()

        /**
         * For each image label listed, its line and how many images carry it, so repeats show once with a count. Images that
         * can be tapped count only beside their siblings, as markers on one map, so each product card keeps its own.
         */
        val images = HashMap<Any, Pair<Int, Int>>()
        var visited = 0
        var loading = false
    }

    fun read(root: AccessibilityNodeInfo, appLabel: String, width: Int, height: Int, moving: Boolean = false): ScreenSnapshot =
        read(AndroidNode(root), appLabel, width, height, moving)

    fun read(root: UiNode, appLabel: String, width: Int, height: Int, moving: Boolean = false): ScreenSnapshot {
        val walk = Walk(width, height)
        val packageName = root.packageName.orEmpty()
        walk.lines.add("App: $appLabel ($packageName) · screen ${width}x$height")
        visit(root, null, depth = 0, indent = 0, parent = null, walk)
        if (walk.nodes.size >= MAX_ELEMENTS) walk.lines.add("… more elements not listed; scroll or act on what is shown")
        if (walk.nodes.isEmpty()) walk.lines.add(NO_ELEMENTS)
        walk.images.values.filter { it.second > 1 }.forEach { (line, count) -> walk.lines[line] = walk.lines[line] + " ×$count" }
        val text = walk.lines.joinToString("\n", postfix = "\n")
        return ScreenSnapshot(root, width, height, packageName, appLabel, walk.nodes, walk.keys, text, walk.loading, moving)
    }

    /** Each node under [root] whose own text contains [text], hidden ones included, as its key and its text */
    fun marks(root: AccessibilityNodeInfo, text: String): Set<String> = marks(AndroidNode(root), text)

    fun marks(root: UiNode, text: String): Set<String> {
        val found = HashSet<String>()
        fun walk(node: UiNode, depth: Int) {
            if (depth > MAX_DEPTH) return
            mark(node, text)?.let { found.add(it) }
            node.children.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        return found
    }

    /** As [marks], for the nodes the agent can see: visible, and not on a layer that a later one covers, as [read] leaves out */
    fun visibleMarks(root: UiNode, text: String, width: Int, height: Int): Set<String> {
        val found = HashSet<String>()
        val walk = Walk(width, height)
        fun visit(node: UiNode, depth: Int) {
            if (depth > MAX_DEPTH) return
            if (node.visible) mark(node, text)?.let { found.add(it) }
            val children = node.children
            val covered = coveredLayers(children, walk)
            children.forEach { if (it !in covered) visit(it, depth + 1) }
        }
        visit(root, 0)
        return found
    }

    private fun mark(node: UiNode, text: String): String? = ownLabel(node)?.takeIf { it.contains(text, ignoreCase = true) }?.let { "${node.key}|$it" }

    /** What a screen with nothing to list says, so a tool can tell that it can't see whether the screen changed */
    const val NO_ELEMENTS = "(no readable elements)"

    /** All visible text of the window, for context collection. Password fields are left out. */
    fun visibleText(root: AccessibilityNodeInfo): String = texts(AndroidNode(root), visibleOnly = true)

    /** The whole text of an element and what is inside it, without the length limit of the screen lines */
    fun fullText(node: AccessibilityNodeInfo): String = texts(AndroidNode(node), visibleOnly = false)

    private fun texts(root: UiNode, visibleOnly: Boolean): String {
        val parts = ArrayList<String>()
        fun walk(node: UiNode, depth: Int) {
            if (depth > MAX_DEPTH) return
            if ((!visibleOnly || node.visible) && !node.password) ownLabel(node)?.let { if (parts.lastOrNull() != it) parts.add(it) }
            node.children.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        return parts.joinToString("\n")
    }

    /**
     * The nearest listed element above a node. [summary] is set when it is a card whose own label sums it up, as a product
     * card's does: the texts inside it are added to its line instead of listed under it.
     */
    private class Parent(val node: UiNode, val label: String?, val actionable: Boolean, val summary: Summary?)

    private class Summary(val id: Int, val line: Int, val key: Int, val indent: Int) {
        val extras = ArrayList<String>()
    }

    private fun visit(node: UiNode, container: UiNode?, depth: Int, indent: Int, parent: Parent?, walk: Walk) {
        if (depth > MAX_DEPTH || walk.nodes.size >= MAX_ELEMENTS || walk.visited++ >= MAX_VISITED) return
        // A hidden node is left out, but its children are still read: after a scroll, Chrome gives a page's outer
        // containers no height, and the visible text sits inside them
        val visible = node.visible
        // A spinner has no value; a bar with one, as a usage bar in a widget, shows an amount and is done loading
        if (visible && node.className?.endsWith("ProgressBar") == true && node.range == null) walk.loading = true
        val actionable = visible && isActionable(node)
        val own = ownLabel(node)
        val absorbsChildren = actionable && own == null && !node.scrollable
        val label = own ?: if (absorbsChildren) descendantText(node) else null
        var listed = visible && (actionable || label != null) && !repeats(node, label, actionable, parent)
        // A text inside a card that sums itself up goes on the card's line
        val summary = parent?.summary
        if (listed && !actionable && label != null && summary != null) {
            if (label !in summary.extras && !saysNothingNew(label, "${parent.label} ${summary.extras.joinToString(" ")}")) {
                summary.extras.add(label)
                val card = parent.node
                val folded = "${parent.label} · ${summary.extras.joinToString(" · ")}"
                walk.lines[summary.line] = "  ".repeat(summary.indent) + describe(summary.id, card, folded, walk)
                walk.keys[summary.key] = keyOf(card, folded, actionable = true, walk)
            }
            listed = false
        }
        // An image with the label of one listed before, as each car on a map of rides, is counted there
        val image: Any? = when {
            !listed || label == null || role(node) != "image" -> null
            actionable -> container?.let { System.identityHashCode(it) to label }
            else -> label
        }
        val earlier = image?.let { walk.images[it] }
        if (image != null && earlier != null) {
            walk.images[image] = earlier.first to earlier.second + 1
            listed = false
        }
        var next = parent
        if (listed) {
            walk.nodes.add(node)
            walk.keys.add(keyOf(node, label, actionable, walk))
            walk.lines.add("  ".repeat(indent) + describe(walk.nodes.size, node, label, walk))
            if (image != null) walk.images[image] = walk.lines.lastIndex to 1
            val sumsUp = actionable && own != null && own.length >= MIN_SUMMARY && !node.scrollable && !node.editable
            next = Parent(node, label, actionable, if (sumsUp) Summary(walk.nodes.size, walk.lines.lastIndex, walk.keys.lastIndex, indent) else null)
        }
        val children = node.children
        val covered = coveredLayers(children, walk)
        // A labelled row stands for its text; its actionable children are still listed so they can be tapped
        for (child in children) {
            if (child in covered) continue
            if (absorbsChildren && !hasActionable(child)) continue
            visit(child, node, depth + 1, if (listed) indent + 1 else indent, next, walk)
        }
    }

    /** Whether every word of [text] is already in [summary], as "12:30 AM" is in "12:30 AM, Every day" */
    private fun saysNothingNew(text: String, summary: String): Boolean {
        val known = WORD.findAll(summary.lowercase()).map { it.value }.toSet()
        return WORD.findAll(text.lowercase()).all { it.value in known }
    }

    private val WORD = Regex("[\\p{L}\\p{N}₹$%.:/]+")

    /**
     * The children that a later one covers completely, as an app that keeps the page before under the page in front: the
     * search page under the results. A layer covers when it is drawn above, holds the whole child, takes up a good part of
     * the screen, and has text down the child's height with no wide empty band. Floating bars, a sheet over part of the
     * page, or a see-through layer with a search bar and a sheet over a map don't cover.
     */
    private fun coveredLayers(children: List<UiNode>, walk: Walk): Set<UiNode> {
        if (children.size < 2) return emptySet()
        val screenArea = walk.width.toLong() * walk.height
        // Drawn later means on top: by drawing order, then by position among the siblings
        val order = children.indices.sortedWith(compareBy({ children[it].drawingOrder }, { it }))
        // By identity: two recorded nodes can be equal, and only the one under the layer is covered
        val covered = Collections.newSetFromMap(IdentityHashMap<UiNode, Boolean>())
        for ((rank, below) in order.withIndex()) {
            val under = children[below].bounds
            if (under.isEmpty || !children[below].visible) continue
            val coveredBy = order.drop(rank + 1).any { above ->
                val over = children[above]
                over.visible && over.bounds.contains(under) && over.bounds.area >= screenArea * MIN_LAYER_SHARE && textFills(over, under)
            }
            if (coveredBy) covered.add(children[below])
        }
        return covered
    }

    /**
     * Whether the visible text inside [layer] runs down [area] the way a page's does. Only text counts: an empty view, a
     * dimmed backdrop that takes taps, or an image says nothing about whether the layer can be seen through.
     */
    private fun textFills(layer: UiNode, area: Box): Boolean {
        val spans = ArrayList<IntRange>()
        var visited = 0
        fun walk(node: UiNode, depth: Int) {
            if (depth > MAX_DEPTH || visited++ >= MAX_LAYER_VISITS) return
            if (node.visible && ownLabel(node) != null) {
                val top = maxOf(node.bounds.top, area.top)
                val bottom = minOf(node.bounds.bottom, area.bottom)
                if (bottom > top) spans.add(top until bottom)
            }
            node.children.forEach { walk(it, depth + 1) }
        }
        walk(layer, 0)
        if (spans.size < MIN_LAYER_TEXTS) return false
        var covered = 0
        var widestGap = 0
        var reached = area.top
        for (span in spans.sortedBy { it.first }) {
            if (span.first > reached) widestGap = maxOf(widestGap, span.first - reached)
            if (span.last + 1 > reached) {
                covered += span.last + 1 - maxOf(span.first, reached)
                reached = span.last + 1
            }
        }
        widestGap = maxOf(widestGap, area.bottom - reached)
        return covered >= area.height * MIN_LAYER_FILL && widestGap <= area.height * MAX_LAYER_GAP
    }

    /**
     * Whether a node only repeats the element it sits in: text that is part of its button's label, or a node with the
     * same label or the same bounds as its parent, as apps that wrap one button in two or three views draw it
     */
    private fun repeats(node: UiNode, label: String?, actionable: Boolean, parent: Parent?): Boolean {
        if (parent == null) return false
        // A control with no text and no view id inside one that can be tapped says nothing the agent can use
        if (label == null) return actionable && parent.actionable && viewId(node) == null && !node.editable && !node.scrollable
        // Within the parent's label, or only repeating its words, as "1:14 (1:14 AM)" in an alarm's row
        val inParentLabel = parent.label?.let { it.contains(label) || saysNothingNew(label, it) } == true
        if (!actionable) return inParentLabel
        // An actionable node stays when its parent can't be acted on, or when it does something the parent doesn't
        if (!parent.actionable || node.editable || node.checkable || (node.scrollable && !parent.node.scrollable)) return false
        return label == parent.label || (inParentLabel && node.bounds == parent.node.bounds)
    }

    private fun isActionable(node: UiNode): Boolean =
        node.clickable || node.longClickable || node.editable || node.scrollable || node.checkable || node.range != null

    private fun describe(id: Int, node: UiNode, label: String?, walk: Walk): String {
        val parts = ArrayList<String>()
        parts.add("[$id]")
        parts.add(role(node))
        when {
            node.password -> parts.add("(password field)")
            node.editable -> {
                val hint = node.hint?.takeIf { it.isNotBlank() }
                val value = node.text?.takeIf { it.isNotBlank() && it != hint }
                parts.add("\"${clip(value.orEmpty())}\"")
                hint?.let { parts.add("hint=\"${clip(it)}\"") }
            }
            label != null -> parts.add("\"${clip(label)}\"")
            // A control with no text: its view id is often the only clue to what it does
            else -> viewId(node)?.let { parts.add("id=$it") }
        }
        node.range?.let { range -> parts.add("value=${number(range.current)} (${number(range.min)}–${number(range.max)})") }
        val bounds = node.bounds
        val offscreen = bounds.centerX !in 0..walk.width || bounds.centerY !in 0..walk.height
        val flags = buildList {
            if (node.clickable) add("click")
            if (node.longClickable) add("long")
            if (node.scrollable) add("scroll")
            if (node.checkable) add(if (node.checked) "checked" else "unchecked")
            if (node.selected) add("selected")
            if (node.focused) add("focused")
            if (!node.enabled) add("disabled")
            if (offscreen) add("offscreen")
        }
        if (flags.isNotEmpty()) parts.add(flags.joinToString(","))
        parts.add("@${bounds.centerX},${bounds.centerY}")
        return parts.joinToString(" ")
    }

    private fun keyOf(node: UiNode, label: String?, actionable: Boolean, walk: Walk): ScreenSnapshot.ElementKey {
        val parts = listOfNotNull(label, node.hint?.takeIf { it.isNotBlank() }, viewId(node)?.replace('_', ' '))
        val bounds = node.bounds
        val onScreen = bounds.centerX in 0..walk.width && bounds.centerY in 0..walk.height
        // A row that holds a text field, as Uber's destination with its clear button, shows what was typed as the field does
        return ScreenSnapshot.ElementKey(parts.joinToString(" · "), actionable, node.editable || holdsField(node), onScreen, bounds, node.checkable)
    }

    fun number(value: Float): String = if (value == value.toLong().toFloat()) value.toLong().toString() else "%.2f".format(value)

    private fun viewId(node: UiNode): String? = node.viewId?.substringAfter(":id/")?.takeIf { it.isNotBlank() }

    private fun role(node: UiNode): String {
        val name = node.className?.substringAfterLast('.').orEmpty()
        return when {
            node.editable -> "input"
            node.range != null || name.contains("SeekBar") || name.contains("Slider") -> "slider"
            name.contains("Button") -> "button"
            name.contains("CheckBox") -> "checkbox"
            name.contains("Switch") -> "switch"
            name.contains("Image") -> "image"
            name.contains("Recycler") || name.contains("ListView") || name.contains("ScrollView") || name.contains("Pager") -> "list"
            name == "TextView" -> "text"
            node.clickable -> "item"
            else -> name.ifEmpty { "view" }.lowercase()
        }
    }

    /** A node's own text and description, without the text of what is inside it */
    fun ownLabel(node: AccessibilityNodeInfo): String? = ownLabel(AndroidNode(node))

    private fun ownLabel(node: UiNode): String? {
        // A label that is a media file's address says nothing to the agent. Visible text is dropped only with a query, as
        // tracking links have, since a path in a file manager is worth keeping.
        val text = clean(node.text)?.takeIf { !(MEDIA_FILE.matches(it) && '?' in it) }
        val description = clean(node.description)?.takeIf { it.lowercase() !in GENERIC_LABELS && !BARE_LINK.matches(it) && !MEDIA_FILE.matches(it) }
        return when {
            text != null && description != null && description != text -> "$text ($description)"
            else -> text ?: description
        }
    }

    private fun clean(value: String?): String? = value?.replace(INVISIBLE, "")?.trim()?.takeIf { it.isNotEmpty() }

    private fun descendantText(node: UiNode): String? {
        val parts = LinkedHashSet<String>()
        fun walk(current: UiNode, depth: Int) {
            if (depth > MAX_DEPTH || parts.size >= 6) return
            for (child in current.children) {
                // A text that only repeats the ones before it, as "12:30 AM" after "12:30 AM, Every day", adds nothing
                if (child.visible && !child.password) ownLabel(child)?.let { if (!saysNothingNew(it, parts.joinToString(" "))) parts.add(it) }
                walk(child, depth + 1)
            }
        }
        walk(node, 0)
        return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
    }

    private fun holdsField(node: UiNode, depth: Int = 0): Boolean =
        depth <= FIELD_DEPTH && node.children.any { it.editable || holdsField(it, depth + 1) }

    private fun hasActionable(node: UiNode, depth: Int = 0): Boolean {
        if (depth > MAX_DEPTH) return false
        if (isActionable(node)) return true
        return node.children.any { hasActionable(it, depth + 1) }
    }

    fun clip(value: String): String {
        val flat = value.replace('\n', ' ')
        return if (flat.length > MAX_LABEL) flat.take(MAX_LABEL) + "…" else flat
    }

    fun labelOf(node: AccessibilityNodeInfo): String = AndroidNode(node).let { ownLabel(it) ?: descendantText(it) ?: role(it) }
}
