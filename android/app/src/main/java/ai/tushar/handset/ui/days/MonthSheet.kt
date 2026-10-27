package ai.tushar.handset.ui.days

import ai.tushar.handset.R
import ai.tushar.handset.ui.IconButton
import ai.tushar.handset.ui.Sheet
import ai.tushar.handset.ui.pressable
import ai.tushar.handset.ui.theme.AppSize
import ai.tushar.handset.ui.theme.AppTheme
import ai.tushar.handset.ui.theme.AppType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The month, to jump to a day. Days before recording started, and days after today, can't be picked. A sheet from the
 * bottom on a phone, a dialog in the middle on a wide screen.
 */
@Composable
fun MonthSheet(selected: LocalDate, today: LocalDate, firstDay: LocalDate?, wide: Boolean, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    Sheet(wide, onDismiss) {
        Box(Modifier.padding(start = 20.dp, end = 20.dp, top = if (wide) 16.dp else 0.dp, bottom = if (wide) 24.dp else 20.dp)) { Month(selected, today, firstDay, onPick) }
    }
}

@Composable
private fun Month(selected: LocalDate, today: LocalDate, firstDay: LocalDate?, onPick: (LocalDate) -> Unit) {
    val colors = AppTheme.colors
    val locale = Locale.getDefault()
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    val firstMonth = firstDay?.let(YearMonth::from) ?: YearMonth.from(today)
    val weekStart = WeekFields.of(locale).firstDayOfWeek
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${month.month.getDisplayName(TextStyle.FULL, locale)} ${month.year}",
                style = AppType.sheetTitle,
                color = colors.text,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (month > firstMonth) IconButton(R.drawable.ph_caret_left, "Previous month") { month = month.minusMonths(1) } else Spacer(Modifier.width(AppSize.touch))
            if (month < YearMonth.from(today)) IconButton(R.drawable.ph_caret_right, "Next month") { month = month.plusMonths(1) } else Spacer(Modifier.width(AppSize.touch))
        }
        Row(Modifier.fillMaxWidth()) {
            (0 until 7).forEach { i ->
                val day = weekStart.plus(i.toLong())
                Text(
                    day.getDisplayName(TextStyle.NARROW, locale),
                    style = AppType.meta.copy(fontWeight = FontWeight.Medium),
                    color = colors.text3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                )
            }
        }
        val first = month.atDay(1)
        val lead = (first.dayOfWeek.value - weekStart.value + 7) % 7
        val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                week.forEach { date ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (date != null) DayCell(date, date == selected, date == today, enabled = !date.isAfter(today) && (firstDay == null || !date.isBefore(firstDay)), onPick)
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, selected: Boolean, today: Boolean, enabled: Boolean, onPick: (LocalDate) -> Unit) {
    val colors = AppTheme.colors
    val label = "${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}, ${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.FULL, Locale.getDefault())}${if (today) ", today" else ""}"
    Box(
        Modifier
            .width(AppSize.calendarCell)
            .height(AppSize.calendarCell)
            .then(if (selected) Modifier.background(colors.accent, CircleShape) else Modifier)
            .pressable(CircleShape, enabled = enabled) { onPick(date) }
            .alpha(if (enabled) 1f else 0.55f)
            .semantics {
                contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = AppType.calendarDay.copy(
                fontWeight = if (selected || today) FontWeight.SemiBold else FontWeight.Normal,
                textDecoration = if (today && !selected) TextDecoration.Underline else null,
            ),
            color = when {
                selected -> colors.onAccent
                enabled -> colors.text
                else -> colors.text3
            },
        )
    }
}
