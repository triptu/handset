package ai.tushar.handset.ui.days

import ai.tushar.handset.R
import ai.tushar.handset.ui.AgentGlyph
import ai.tushar.handset.ui.AppGlyph
import ai.tushar.handset.ui.GroupAvatar
import ai.tushar.handset.ui.IconButton
import ai.tushar.handset.ui.Initial
import ai.tushar.handset.ui.ListRow
import ai.tushar.handset.ui.PhIcon
import ai.tushar.handset.ui.SectionLabel
import ai.tushar.handset.ui.TextAction
import ai.tushar.handset.ui.bleed
import ai.tushar.handset.ui.placeGlyph
import ai.tushar.handset.ui.pressable
import ai.tushar.handset.ui.theme.AppSize
import ai.tushar.handset.ui.theme.AppTheme
import ai.tushar.handset.ui.theme.AppType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId

val LocalFormat = staticCompositionLocalOf<Format> { error("No Format provided") }

/** The page index of a day: today is page 0 and each page to the left is a day earlier */
fun dayOfPage(today: LocalDate, page: Int): LocalDate = today.minusDays(page.toLong())

/** The Days header: the day, which opens the month sheet, and search. Nothing else. */
@Composable
fun DaysHeader(date: LocalDate, onPickDay: () -> Unit, onSearch: () -> Unit) {
    val colors = AppTheme.colors
    val format = LocalFormat.current
    val label = format.shortDay(date)
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = AppSize.headerHeight).padding(start = AppSize.pageX - 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.heightIn(min = AppSize.touch).pressable(CircleShape, onClickLabel = "Change day", onClick = onPickDay).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = AppType.heading, color = colors.text, modifier = Modifier.semantics { heading() })
            PhIcon(R.drawable.ph_caret_down, tint = colors.text2, size = 16.dp)
        }
        Spacer(Modifier.weight(1f))
        IconButton(R.drawable.ph_magnifying_glass, "Search", onClick = onSearch)
    }
}

/** Days side by side, newest on the right, so a swipe to the right goes back a day */
@Composable
fun DayPager(
    state: PagerState,
    today: LocalDate,
    repository: DayRepository,
    version: Int,
    selected: MomentId?,
    recording: Boolean,
    onOpen: (Moment) -> Unit,
    onPlace: (Visit) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    selectedPlace: Long? = null,
) {
    HorizontalPager(state, modifier, reverseLayout = true, key = { it }, beyondViewportPageCount = 1) { page ->
        DayPage(dayOfPage(today, page), repository, version, selected, selectedPlace, recording, onOpen, onPlace, onSettings)
    }
}

@Composable
private fun DayPage(
    date: LocalDate,
    repository: DayRepository,
    version: Int,
    selected: MomentId?,
    selectedPlace: Long?,
    recording: Boolean,
    onOpen: (Moment) -> Unit,
    onPlace: (Visit) -> Unit,
    onSettings: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val sections by produceState<List<DaySection>?>(null, date, version) { value = arrange(repository.timeline(date), zone) }
    val loaded = sections ?: return
    if (loaded.isEmpty()) {
        EmptyDay(if (recording) "Nothing was recorded on this day." else "Nothing is being recorded. Choose what to keep in Settings.", if (recording) null else onSettings)
        return
    }
    DayList(loaded, selected, onOpen, onPlace, selectedPlace)
}

/** A day's sections: a place or part of the day, then its moments. Tapping a place names it. */
@Composable
fun DayList(sections: List<DaySection>, selected: MomentId?, onOpen: (Moment) -> Unit, onPlace: (Visit) -> Unit = {}, selectedPlace: Long? = null) {
    val format = LocalFormat.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = AppSize.pageX, end = AppSize.pageX, bottom = 32.dp)) {
        sections.forEachIndexed { index, section ->
            item(key = "s$index") {
                when (section) {
                    is DaySection.Place -> PlaceHeading(section.visit, format, first = index == 0, selected = section.visit.id != null && section.visit.id == selectedPlace) { onPlace(section.visit) }
                    is DaySection.Loose -> SectionLabel(section.label, top = if (index == 0) 8.dp else AppSize.sectionTop)
                }
            }
            items(section.moments, key = { it.id.value }) { moment -> MomentRow(moment, format.time(moment.at), selected = moment.id == selected) { onOpen(moment) } }
        }
    }
}

@Composable
private fun PlaceHeading(visit: Visit, format: Format, first: Boolean, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .padding(top = if (first) 4.dp else AppSize.placeTop)
            .bleed()
            .then(if (visit.id != null) Modifier.pressable(RoundedCornerShape(AppSize.rowRadius), selected, onClickLabel = if (visit.named) "Rename this place" else "Name this place", onClick = onClick) else Modifier)
            .padding(horizontal = AppSize.rowPadX)
            .defaultMinSize(minHeight = AppSize.placeHeight)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSize.rowGap),
    ) {
        Box(Modifier.width(AppSize.lead), contentAlignment = Alignment.Center) { PhIcon(placeGlyph(visit.label), tint = colors.text3, size = AppSize.iconPlace) }
        Text(visit.label, style = AppType.place, color = colors.text2, modifier = Modifier.weight(1f))
        Text(format.span(visit.start, visit.end), style = AppType.place, color = colors.text3)
    }
}

/** A moment as a row: who or what, one line, and when */
@Composable
fun MomentRow(moment: Moment, time: String, selected: Boolean = false, text: String? = null, matches: List<IntRange> = emptyList(), onClick: () -> Unit) {
    val (title, line) = when (moment) {
        is Moment.AppSession -> moment.app.label to moment.line
        is Moment.Message -> moment.sender to moment.text
        is Moment.AgentRun -> moment.agent to moment.line
        is Moment.EventSent -> moment.title to moment.line
    }
    val shown = text ?: line
    val rich = if (matches.isEmpty()) AnnotatedString(shown) else buildAnnotatedString {
        append(shown)
        matches.filter { it.first >= 0 && it.last < shown.length }.forEach { addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = AppTheme.colors.text), it.first, it.last + 1) }
    }
    ListRow(title, richText = rich, trailing = time, selected = selected, lead = { MomentLead(moment) }, onClick = onClick)
}

@Composable
fun MomentLead(moment: Moment) {
    when (moment) {
        is Moment.AppSession -> AppGlyph(moment.app.packageName)
        is Moment.Message -> if (moment.group) GroupAvatar() else Initial(moment.sender)
        is Moment.AgentRun -> AgentGlyph(moment.agent)
        is Moment.EventSent -> PhIcon(R.drawable.ph_paper_plane_tilt, tint = AppTheme.colors.text2)
    }
}

@Composable
fun EmptyDay(text: String, onSettings: (() -> Unit)?) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp).padding(top = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = AppType.rowText, color = colors.text3, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 260.dp))
        if (onSettings != null) TextAction("Open Settings", Modifier.padding(top = 8.dp), color = colors.accent, onClick = onSettings)
    }
}
