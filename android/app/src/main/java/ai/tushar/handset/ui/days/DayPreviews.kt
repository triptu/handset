package ai.tushar.handset.ui.days

import ai.tushar.handset.ui.theme.AppTheme
import ai.tushar.handset.ui.theme.HandsetTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId

@Composable
private fun PreviewDay(dark: Boolean, daysAgo: Long) {
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val sections = runBlocking { arrange(FakeDayRepository(today, zone).timeline(today.minusDays(daysAgo)), zone) }
    HandsetTheme(dark = dark) {
        CompositionLocalProvider(LocalFormat provides Format(zone, today, use24Hour = false)) {
            Column(Modifier.background(AppTheme.colors.background)) {
                DaysHeader(today.minusDays(daysAgo), onPickDay = {}, onSearch = {})
                DayList(sections, selected = null, onOpen = {})
            }
        }
    }
}

@Preview(name = "Today", widthDp = 412, heightDp = 900)
@Composable
private fun TodayLight() = PreviewDay(dark = false, daysAgo = 0)

@Preview(name = "Today, dark", widthDp = 412, heightDp = 900)
@Composable
private fun TodayDark() = PreviewDay(dark = true, daysAgo = 0)

@Preview(name = "A quiet Sunday", widthDp = 412, heightDp = 900)
@Composable
private fun QuietDay() = PreviewDay(dark = false, daysAgo = 2)
