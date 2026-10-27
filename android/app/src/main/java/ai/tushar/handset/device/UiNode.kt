package ai.tushar.handset.device

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * What [ScreenReader] reads of a node of the screen. On the phone it is Android's accessibility node, through
 * [AndroidNode]; in tests it is a screen recorded from a phone, as [RecordedNode], so the reader's rules are checked
 * against real apps.
 */
interface UiNode {
    val text: String?
    val description: String?
    val hint: String?
    val className: String?
    val viewId: String?
    val packageName: String?
    val bounds: Box
    val visible: Boolean
    val clickable: Boolean
    val longClickable: Boolean
    val editable: Boolean
    val scrollable: Boolean
    val checkable: Boolean
    val checked: Boolean
    val selected: Boolean
    val focused: Boolean
    val enabled: Boolean
    val password: Boolean
    val range: Range?
    val drawingOrder: Int
    val children: List<UiNode>

    /** Which node this is, the same on every read while the app keeps the view, as Android tells nodes apart */
    val key: Int

    /** Which ways a list scrolls: `v` for up and down, `h` for sideways, both, or empty when it doesn't say */
    val axes: String

    /** The node Android acts on, or null for a recorded one */
    val info: AccessibilityNodeInfo?
}

/** A node's bounds on the screen, in pixels */
@Serializable
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong() * height
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun contains(x: Int, y: Int): Boolean = !isEmpty && x >= left && x < right && y >= top && y < bottom

    fun contains(other: Box): Boolean = !isEmpty && left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
}

/** A slider's value and its range */
@Serializable
data class Range(val current: Float, val min: Float, val max: Float)

class AndroidNode(override val info: AccessibilityNodeInfo) : UiNode {
    override val text: String? get() = info.text?.toString()
    override val description: String? get() = info.contentDescription?.toString()
    override val hint: String? get() = info.hintText?.toString()
    override val className: String? get() = info.className?.toString()
    override val viewId: String? get() = info.viewIdResourceName
    override val packageName: String? get() = info.packageName?.toString()
    override val bounds: Box by lazy { Rect().also { info.getBoundsInScreen(it) }.let { Box(it.left, it.top, it.right, it.bottom) } }
    override val visible: Boolean get() = info.isVisibleToUser
    override val clickable: Boolean get() = info.isClickable
    override val longClickable: Boolean get() = info.isLongClickable
    override val editable: Boolean get() = info.isEditable
    override val scrollable: Boolean get() = info.isScrollable
    override val checkable: Boolean get() = info.isCheckable
    override val checked: Boolean get() = info.isChecked
    override val selected: Boolean get() = info.isSelected
    override val focused: Boolean get() = info.isFocused
    override val enabled: Boolean get() = info.isEnabled
    override val password: Boolean get() = info.isPassword
    override val range: Range? get() = info.rangeInfo?.let { Range(it.current, it.min, it.max) }
    override val drawingOrder: Int get() = info.drawingOrder
    override val children: List<UiNode> by lazy { (0 until info.childCount).mapNotNull { index -> info.getChild(index)?.let(::AndroidNode) } }
    override val key: Int get() = info.hashCode()
    override val axes: String get() {
        val actions = info.actionList
        val grid = info.collectionInfo
        val vertical = AccessibilityAction.ACTION_SCROLL_DOWN in actions || AccessibilityAction.ACTION_SCROLL_UP in actions ||
            (grid != null && grid.rowCount > 1)
        val horizontal = AccessibilityAction.ACTION_SCROLL_LEFT in actions || AccessibilityAction.ACTION_SCROLL_RIGHT in actions ||
            (grid != null && grid.columnCount > 1 && grid.rowCount <= 1)
        return (if (vertical) "v" else "") + (if (horizontal) "h" else "")
    }
}

/** A screen as recorded from a phone by the adb `dump` command: the app, the screen's size, and its whole tree */
@Serializable
data class RecordedScreen(val app: String, val width: Int, val height: Int, val root: RecordedNode)

/** A node as recorded from a phone by the adb `dump` command, with the short names the file uses */
@Serializable
data class RecordedNode(
    @SerialName("t") override val text: String? = null,
    @SerialName("d") override val description: String? = null,
    @SerialName("h") override val hint: String? = null,
    @SerialName("c") override val className: String? = null,
    @SerialName("id") override val viewId: String? = null,
    @SerialName("p") override val packageName: String? = null,
    @SerialName("b") override val bounds: Box = Box(0, 0, 0, 0),
    /** The node's flags, one letter each, as written by [record] */
    @SerialName("f") val flags: String = "",
    @SerialName("r") override val range: Range? = null,
    @SerialName("o") override val drawingOrder: Int = 0,
    @SerialName("k") override val children: List<RecordedNode> = emptyList(),
    @SerialName("n") override val key: Int = 0,
    @SerialName("a") override val axes: String = "",
) : UiNode {
    @Transient override val info: AccessibilityNodeInfo? = null
    override val visible: Boolean get() = 'v' in flags
    override val clickable: Boolean get() = 'c' in flags
    override val longClickable: Boolean get() = 'l' in flags
    override val editable: Boolean get() = 'e' in flags
    override val scrollable: Boolean get() = 's' in flags
    override val checkable: Boolean get() = 'k' in flags
    override val checked: Boolean get() = 'x' in flags
    override val selected: Boolean get() = 'S' in flags
    override val focused: Boolean get() = 'F' in flags
    override val enabled: Boolean get() = 'n' in flags
    override val password: Boolean get() = 'P' in flags

    companion object {
        /** Records [node] and everything under it, hidden nodes included, as the reader visits them too */
        fun record(node: UiNode, depth: Int = 0): RecordedNode = RecordedNode(
            text = node.text,
            description = node.description,
            hint = node.hint,
            className = node.className,
            viewId = node.viewId,
            packageName = node.packageName.takeIf { depth == 0 },
            bounds = node.bounds,
            flags = buildString {
                if (node.visible) append('v')
                if (node.clickable) append('c')
                if (node.longClickable) append('l')
                if (node.editable) append('e')
                if (node.scrollable) append('s')
                if (node.checkable) append('k')
                if (node.checked) append('x')
                if (node.selected) append('S')
                if (node.focused) append('F')
                if (node.enabled) append('n')
                if (node.password) append('P')
            },
            range = node.range,
            drawingOrder = node.drawingOrder,
            children = if (depth >= MAX_RECORD_DEPTH) emptyList() else node.children.map { record(it, depth + 1) },
            key = node.key,
            axes = if (node.scrollable) node.axes else "",
        )

        private const val MAX_RECORD_DEPTH = 80
    }
}
