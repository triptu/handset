package ai.tushar.handset.ui.days

import ai.tushar.handset.ui.FilledButton
import ai.tushar.handset.ui.Sheet
import ai.tushar.handset.ui.TextAction
import ai.tushar.handset.ui.PhIcon
import ai.tushar.handset.ui.placeGlyph
import ai.tushar.handset.ui.pressable
import ai.tushar.handset.ui.theme.AppSize
import ai.tushar.handset.ui.theme.AppTheme
import ai.tushar.handset.ui.theme.AppType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * Names the place of [visit], or renames or forgets it when it has a name. A suggestion names it in one tap. Later
 * visits within 150 m take the name, and so do the phone events sent from there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceSheet(visit: Visit, wide: Boolean, onName: (String) -> Unit, onForget: () -> Unit, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    val format = LocalFormat.current
    val current = visit.label.takeIf { visit.named }.orEmpty()
    var name by remember { mutableStateOf(TextFieldValue(current, TextRange(current.length))) }
    fun save() {
        val trimmed = name.text.trim()
        if (trimmed.isNotEmpty() && trimmed != current) onName(trimmed) else onDismiss()
    }
    Sheet(wide, onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = if (wide) 24.dp else 0.dp, bottom = 16.dp)) {
            Text(if (visit.named) "Rename this place" else "Name this place", style = AppType.sheetTitle, color = colors.text, modifier = Modifier.semantics { heading() })
            Text(
                "You were here ${format.span(visit.start, visit.end)}. Later visits within 150\u00A0m take the name too.",
                style = AppType.rowText,
                color = colors.text2,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            BasicTextField(
                value = name,
                onValueChange = { name = it.copy(text = it.text.take(MAX_NAME)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                textStyle = AppType.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Place name" },
                decorationBox = { field ->
                    Box(Modifier.fillMaxWidth().heightIn(min = AppSize.field).background(colors.surface, RoundedCornerShape(AppSize.rowRadius)).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                        if (name.text.isEmpty()) Text("Name", style = AppType.body, color = colors.text3)
                        field()
                    }
                },
            )
            val suggestions = SUGGESTIONS.filter { !it.equals(current, ignoreCase = true) }
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { label -> Suggestion(label) { onName(label) } }
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (visit.named) TextAction("Forget this place", Modifier.offset(x = (-14).dp), color = colors.danger, onClick = onForget)
                Spacer(Modifier.weight(1f))
                FilledButton("Save", enabled = name.text.isNotBlank() && name.text.trim() != current, onClick = ::save)
            }
        }
    }
}

/** A name to use in one tap, with the glyph Days shows for it */
@Composable
private fun Suggestion(label: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier.heightIn(min = 40.dp)
            .background(colors.surface, CircleShape)
            .pressable(CircleShape, onClickLabel = "Name it $label", onClick = onClick)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhIcon(placeGlyph(label), tint = colors.text2, size = AppSize.iconPlace)
        Text(label, style = AppType.label, color = colors.text)
    }
}

private const val MAX_NAME = 40
private val SUGGESTIONS = listOf("Home", "Office", "Gym")
