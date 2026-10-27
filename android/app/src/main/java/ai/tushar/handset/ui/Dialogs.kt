package ai.tushar.handset.ui

import ai.tushar.handset.R
import ai.tushar.handset.ui.theme.AppSize
import ai.tushar.handset.ui.theme.AppTheme
import ai.tushar.handset.ui.theme.AppType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** A sheet from the bottom on a phone, a dialog in the middle on a wide screen. [content] brings its own padding. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(wide: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val colors = AppTheme.colors
    if (wide) {
        Dialog(onDismissRequest = onDismiss) {
            Box(Modifier.width(AppSize.dialogWidth).background(colors.raised, RoundedCornerShape(AppSize.sheetRadius))) { content() }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.raised,
            scrimColor = colors.scrim,
            shape = RoundedCornerShape(topStart = AppSize.sheetRadius, topEnd = AppSize.sheetRadius),
        ) {
            Box(Modifier.navigationBarsPadding().imePadding()) { content() }
        }
    }
}

/** A dialog's card: its title, a line under it, and [content] */
@Composable
fun DialogCard(title: String, text: String?, content: @Composable () -> Unit) {
    val colors = AppTheme.colors
    Column(
        Modifier.widthIn(max = AppSize.dialogWidth).fillMaxWidth().background(colors.raised, RoundedCornerShape(AppSize.sheetRadius)).padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
    ) {
        Text(title, style = AppType.sheetTitle, color = colors.text, modifier = Modifier.semantics { heading() })
        if (text != null) Text(text, style = AppType.rowText, color = colors.text2, modifier = Modifier.padding(top = 8.dp))
        content()
    }
}

/** Asks before something that can't be undone. [confirm] names the action, in the danger colour. */
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = AppTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        DialogCard(title, text) {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                TextAction("Cancel", onClick = onDismiss)
                FilledButton(confirm, container = colors.danger, content = colors.onDanger, onClick = onConfirm)
            }
        }
    }
}

/** One of a few choices, with a check on the current one */
@Composable
fun ChoiceDialog(title: String, choices: List<String>, selected: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val colors = AppTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        DialogCard(title, null) {
            Column(Modifier.padding(top = 8.dp).selectableGroup()) {
                choices.forEachIndexed { index, choice ->
                    ListRow(choice, end = {
                        Box(Modifier.size(AppSize.iconSmall)) { if (index == selected) PhIcon(R.drawable.ph_check, tint = colors.accent, size = AppSize.iconSmall) }
                    }, selected = index == selected, role = Role.RadioButton, onClick = { onPick(index) })
                }
            }
        }
    }
}
