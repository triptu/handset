package ai.tushar.handset.ui

import ai.tushar.handset.R
import ai.tushar.handset.ui.theme.AppMotion
import ai.tushar.handset.ui.theme.AppSize
import ai.tushar.handset.ui.theme.AppTheme
import ai.tushar.handset.ui.theme.AppType
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The pieces every screen is built from

@Composable
fun PhIcon(@DrawableRes icon: Int, modifier: Modifier = Modifier, tint: Color = AppTheme.colors.text2, size: Dp = AppSize.icon, description: String? = null) {
    Icon(painterResource(icon), contentDescription = description, tint = tint, modifier = modifier.size(size))
}

/** A press, and being the selected one, show as a tint in the element's own shape. Focus from a keyboard or d-pad adds a ring. */
private fun Modifier.touchState(interaction: MutableInteractionSource, shape: Shape, selected: Boolean): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val colors = AppTheme.colors
    this
        .clip(shape)
        .background(if (pressed || selected) colors.press else Color.Transparent, shape)
        .then(if (focused) Modifier.border(2.dp, colors.accent, shape) else Modifier)
}

/** A tappable element. As a [Role.RadioButton] or [Role.Tab] it is one choice of a group, and [selected] is announced as chosen. */
fun Modifier.pressable(shape: Shape, selected: Boolean = false, role: Role = Role.Button, enabled: Boolean = true, onClickLabel: String? = null, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    this
        // A selected tab shows by its colour, not a tint
        .touchState(interaction, shape, selected && role != Role.Tab)
        .then(
            if (role == Role.RadioButton || role == Role.Tab) {
                Modifier.selectable(selected, interactionSource = interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
            } else {
                Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = onClick)
            },
        )
}

/** A 48dp round icon button */
@Composable
fun IconButton(@DrawableRes icon: Int, description: String, modifier: Modifier = Modifier, tint: Color = AppTheme.colors.text2, onClick: () -> Unit) {
    Box(modifier.size(AppSize.touch).pressable(CircleShape, onClick = onClick), contentAlignment = Alignment.Center) {
        PhIcon(icon, tint = tint, size = 22.dp, description = description)
    }
}

/** A page's top bar: back or close, then the title, then [actions] */
@Composable
fun PageHeader(title: String? = null, @DrawableRes backIcon: Int = R.drawable.ph_arrow_left, backLabel: String = "Back", onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    // Kept to the page column's width, so the title lines up with the rows under it on a wide screen
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    Row(
        Modifier.widthIn(max = AppSize.pageMaxWidth).fillMaxWidth().defaultMinSize(minHeight = AppSize.headerHeight).padding(start = if (onBack != null) 4.dp else AppSize.pageX, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconButton(backIcon, backLabel, onClick = onBack)
        if (title != null) {
            Text(title, style = AppType.title, color = AppTheme.colors.text, modifier = Modifier.weight(1f).padding(start = if (onBack != null) 4.dp else 0.dp).semantics { heading() })
        } else {
            Spacer(Modifier.weight(1f))
        }
        actions()
    }
    }
}

/** A small grey heading over a group of rows */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, top: Dp = AppSize.sectionTop, trailing: String? = null) {
    val colors = AppTheme.colors
    Row(modifier.fillMaxWidth().padding(top = top, bottom = 2.dp).semantics(mergeDescendants = true) { heading() }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, style = AppType.label, color = colors.text3, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = AppType.time.copy(lineHeight = AppType.label.lineHeight), color = colors.text3)
    }
}

/**
 * The one row: a leading glyph, a title with a line under it, and a time or value on the right. It reaches 12dp past
 * the page's margin on both sides, so its pressed tint has room while its text lines up with the page.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    text: String? = null,
    richText: AnnotatedString? = null,
    trailing: String? = null,
    selected: Boolean = false,
    role: Role = Role.Button,
    textLines: Int = 1,
    titleStyle: TextStyle = AppType.rowTitle,
    titleColor: Color = AppTheme.colors.text,
    lead: (@Composable () -> Unit)? = null,
    end: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)?,
) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(AppSize.rowRadius)
    Row(
        modifier
            .bleed()
            .defaultMinSize(minHeight = if (text == null && richText == null) AppSize.rowSingleMinHeight else AppSize.rowMinHeight)
            .then(if (onClick != null) Modifier.pressable(shape, selected, role = role, onClick = onClick) else Modifier)
            .padding(horizontal = AppSize.rowPadX, vertical = AppSize.rowPadY),
        horizontalArrangement = Arrangement.spacedBy(AppSize.rowGap),
        verticalAlignment = if ((text == null && richText == null) || end != null) Alignment.CenterVertically else Alignment.Top,
    ) {
        if (lead != null) {
            Box(Modifier.width(AppSize.lead).height(if (text == null && richText == null) AppSize.lead else 40.dp), contentAlignment = Alignment.Center) { lead() }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = titleStyle, color = titleColor)
            if (richText != null) {
                Text(richText, style = AppType.rowText, color = colors.text2, maxLines = textLines, overflow = TextOverflow.Ellipsis)
            } else if (text != null) {
                Text(text, style = AppType.rowText, color = colors.text2, maxLines = textLines, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) Text(trailing, style = AppType.time, color = colors.text3)
        if (end != null) end()
    }
}

/** Widens a full-width row by the row padding on each side, so its text lines up with the page's margin */
fun Modifier.bleed(): Modifier = this.layoutBleed(AppSize.rowPadX)

private fun Modifier.layoutBleed(by: Dp): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val extra = by.roundToPx() * 2
        val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
        layout(constraints.maxWidth, placeable.height) { placeable.place(-by.roundToPx(), 0) }
    },
)

/** A setting with a switch. The whole row toggles it. */
@Composable
fun SwitchRow(title: String, text: String?, checked: Boolean, enabled: Boolean = true, lead: (@Composable () -> Unit)? = null, onChange: (Boolean) -> Unit) {
    val colors = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .bleed()
            .defaultMinSize(minHeight = if (text == null) AppSize.rowSingleMinHeight else AppSize.rowMinHeight)
            .touchState(interaction, RoundedCornerShape(AppSize.rowRadius), selected = false)
            .toggleable(checked, interactionSource = interaction, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = AppSize.rowPadX, vertical = AppSize.rowPadY),
        horizontalArrangement = Arrangement.spacedBy(AppSize.rowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lead != null) Box(Modifier.width(AppSize.lead), contentAlignment = Alignment.Center) { lead() }
        Column(Modifier.weight(1f)) {
            Text(title, style = AppType.rowTitle, color = if (enabled) colors.text else colors.text3)
            if (text != null) Text(text, style = AppType.rowText, color = colors.text2)
        }
        SwitchTrack(checked)
    }
}

/** One of several choices that can each be on, with a check while it is. The whole row toggles it. */
@Composable
fun CheckRow(title: String, text: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .bleed()
            .defaultMinSize(minHeight = if (text == null) AppSize.rowSingleMinHeight else AppSize.rowMinHeight)
            .touchState(interaction, RoundedCornerShape(AppSize.rowRadius), selected = false)
            .toggleable(checked, interactionSource = interaction, indication = null, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = AppSize.rowPadX, vertical = AppSize.rowPadY),
        horizontalArrangement = Arrangement.spacedBy(AppSize.rowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AppType.rowTitle, color = colors.text)
            if (text != null) Text(text, style = AppType.rowText, color = colors.text2)
        }
        Box(Modifier.size(AppSize.iconSmall)) { if (checked) PhIcon(R.drawable.ph_check, tint = colors.accent, size = AppSize.iconSmall) }
    }
}

/**
 * A one-line text field on the surface colour, with [placeholder] while it is empty. [label] names it for screen
 * readers. A [secret] field shows dots.
 */
@Composable
fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Uri,
    imeAction: ImeAction = ImeAction.Done,
    secret: Boolean = false,
) {
    val colors = AppTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType, autoCorrectEnabled = false, imeAction = imeAction),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = AppType.rowText.copy(color = colors.text),
        cursorBrush = SolidColor(colors.accent),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
        decorationBox = { field ->
            Box(Modifier.fillMaxWidth().heightIn(min = AppSize.field).background(colors.surface, RoundedCornerShape(AppSize.rowRadius)).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = AppType.rowText, color = colors.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                field()
            }
        },
    )
}

/** The look of a switch; the row around it takes the touch */
@Composable
fun SwitchTrack(checked: Boolean) {
    val colors = AppTheme.colors
    val track by animateColorAsState(if (checked) colors.switchOn else colors.surface, tween(AppMotionMs), label = "track")
    val thumb by animateColorAsState(if (checked) colors.switchOnThumb else colors.text3, tween(AppMotionMs), label = "thumb")
    val thumbSize by animateDpAsState(if (checked) AppSize.switchThumb else AppSize.switchThumbOff, tween(AppMotionMs), label = "thumbSize")
    val inset = (AppSize.switchHeight - thumbSize) / 2
    val x by animateDpAsState(if (checked) AppSize.switchWidth - thumbSize - (AppSize.switchHeight - AppSize.switchThumb) / 2 else inset, tween(AppMotionMs), label = "x")
    val outline by animateColorAsState(if (checked) colors.switchOn else colors.text3, tween(AppMotionMs), label = "outline")
    Box(Modifier.size(AppSize.switchWidth, AppSize.switchHeight).background(track, CircleShape).border(2.dp, outline, CircleShape)) {
        Box(Modifier.offset(x = x, y = inset).size(thumbSize).background(thumb, CircleShape))
    }
}

private const val AppMotionMs = AppMotion.fast

/** A button that is only text. Its label sits 14dp in, so a caller lining it up with the page offsets it by that. */
@Composable
fun TextAction(text: String, modifier: Modifier = Modifier, color: Color = AppTheme.colors.text, @DrawableRes icon: Int? = null, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = AppSize.touch).pressable(CircleShape, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) PhIcon(icon, tint = color, size = 18.dp)
        Text(text, style = AppType.button, color = color)
    }
}

/** A filled button, for the one main action of a screen */
@Composable
fun FilledButton(text: String, modifier: Modifier = Modifier, container: Color = AppTheme.colors.accent, content: Color = AppTheme.colors.onAccent, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = AppSize.touch)
            .clip(CircleShape)
            .background(if (enabled) container else container.copy(alpha = 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = AppType.button, color = content)
    }
}

/** A page's scrolling column, kept to a readable width on a wide screen */
@Composable
fun PageColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = AppSize.pageMaxWidth).fillMaxWidth().padding(horizontal = AppSize.pageX), content = content)
    }
}

/** The end of a row that opens a page */
@Composable
fun Chevron() {
    PhIcon(R.drawable.ph_caret_right, tint = AppTheme.colors.text3, size = 18.dp)
}
