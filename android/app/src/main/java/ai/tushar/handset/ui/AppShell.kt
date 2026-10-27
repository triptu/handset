package ai.tushar.handset.ui

import ai.tushar.handset.Handset
import ai.tushar.handset.R
import ai.tushar.handset.ui.days.DayPager
import ai.tushar.handset.ui.days.DayRepository
import ai.tushar.handset.ui.days.DaysHeader
import ai.tushar.handset.ui.days.EmptyPane
import ai.tushar.handset.ui.days.MomentId
import ai.tushar.handset.ui.days.MomentScreen
import ai.tushar.handset.ui.days.MonthSheet
import ai.tushar.handset.ui.days.PlaceSheet
import ai.tushar.handset.ui.days.Visit
import ai.tushar.handset.ui.days.SearchScreen
import ai.tushar.handset.ui.days.dayOfPage
import ai.tushar.handset.ui.settings.AddAgentScreen
import ai.tushar.handset.ui.settings.AgentDirectory
import ai.tushar.handset.ui.settings.AgentScreen
import ai.tushar.handset.ui.settings.ExcludedAppsScreen
import ai.tushar.handset.ui.settings.SettingsTab
import ai.tushar.handset.ui.theme.AppMotion
import ai.tushar.handset.ui.theme.AppSize
import ai.tushar.handset.ui.theme.AppTheme
import ai.tushar.handset.ui.theme.AppType
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class Tab(val title: String, @DrawableRes val icon: Int) {
    DAYS("Days", R.drawable.ph_calendar_blank),
    SETTINGS("Settings", R.drawable.ph_gear_six),
}

/** A page opened over a tab's first page. Saved as a string, so the stacks survive the activity being recreated. */
sealed interface Page {
    data object Search : Page
    data class Moment(val id: MomentId) : Page
    data class Agent(val id: String) : Page
    data object AddAgent : Page
    data object ExcludedApps : Page
    data object Setup : Page
    data object About : Page
    data object Licences : Page

    fun encode(): String = when (this) {
        is Moment -> "moment:${id.value}"
        is Agent -> "agent:$id"
        else -> this::class.simpleName!!
    }

    companion object {
        fun decode(text: String): Page = when {
            text.startsWith("moment:") -> Moment(MomentId(text.removePrefix("moment:")))
            text.startsWith("agent:") -> Agent(text.removePrefix("agent:"))
            else -> listOf(Search, AddAgent, ExcludedApps, Setup, About, Licences).first { it::class.simpleName == text }
        }
    }
}

private val stackSaver = listSaver<SnapshotStateList<Page>, String>(save = { it.map(Page::encode) }, restore = { it.map(Page::decode).toMutableStateList() })

/** What the screens read and change, handed in so debug builds and previews can use made-up data */
class AppData(val days: DayRepository, val agents: AgentDirectory)

/**
 * The app after setup: Days and Settings, as a bar at the bottom on a phone and a rail on a wide screen. On a wide
 * screen a moment opens in a pane beside the day. While an agent acts, one line under the status bar says so, with Stop.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppShell(handset: Handset, data: AppData, setup: SetupStatus, version: Int, setupScreen: @Composable (onBack: () -> Unit) -> Unit) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(Tab.DAYS) }
    val days = rememberSaveable(saver = stackSaver) { mutableStateListOf() }
    val settings = rememberSaveable(saver = stackSaver) { mutableStateListOf() }
    var query by rememberSaveable { mutableStateOf("") }
    var monthOpen by rememberSaveable { mutableStateOf(false) }
    var placeOpen by remember { mutableStateOf<Visit?>(null) }
    // Bumped when a place is named, so the days show the name
    var edits by remember { mutableIntStateOf(0) }
    val acting by handset.acting.collectAsState()
    val controlEnabled by handset.control.collectAsState()
    val holder = rememberSaveableStateHolder()

    val today = remember(version) { LocalDate.now() }
    val firstDay by produceState<LocalDate?>(null, version) { value = data.days.firstDay() }
    val pageCount = firstDay?.let { ChronoUnit.DAYS.between(it, today).toInt() + 1 }?.coerceAtLeast(1) ?: 1
    val pager = rememberPagerState { pageCount }
    val date = dayOfPage(today, pager.currentPage)

    val stack = if (tab == Tab.DAYS) days else settings
    BackHandler(enabled = stack.isNotEmpty() || tab != Tab.DAYS) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else tab = Tab.DAYS
    }

    fun openMoment(id: MomentId, wide: Boolean) {
        // On a wide screen the pane holds one moment, so opening another replaces it
        if (wide && days.lastOrNull() is Page.Moment) days[days.lastIndex] = Page.Moment(id) else days.add(Page.Moment(id))
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val wide = maxWidth >= AppSize.wideFrom
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            ActingLine(acting) { handset.switchControl(false) }
            Row(Modifier.weight(1f)) {
                if (wide) Rail(tab) { tab = it }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val selectedMoment = (days.lastOrNull() as? Page.Moment)?.id
                    when (tab) {
                        Tab.DAYS -> {
                            val daysRoot: @Composable () -> Unit = {
                                if (Page.Search in days) {
                                    SearchScreen(query, { query = it }, data.days, selectedMoment.takeIf { wide }, onBack = {
                                        days.removeAll { it is Page.Search || (wide && it is Page.Moment) }
                                        query = ""
                                    }) { openMoment(it.id, wide) }
                                } else {
                                    Column {
                                        DaysHeader(date, onPickDay = { monthOpen = true }, onSearch = { days.removeAll { it is Page.Moment }; days.add(Page.Search) })
                                        DayPager(pager, today, data.days, version + edits, selectedMoment.takeIf { wide }, handset.recording.anyOn, { openMoment(it.id, wide) }, { placeOpen = it }, { tab = Tab.SETTINGS }, selectedPlace = placeOpen?.id)
                                    }
                                }
                            }
                            if (wide) {
                                Row(Modifier.fillMaxSize()) {
                                    Box(Modifier.width(AppSize.listWidth).fillMaxHeight()) { holder.SaveableStateProvider("days") { daysRoot() } }
                                    // Tinted only while it holds a moment, so an empty pane stays quiet
                                    Box(
                                        Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp, end = 12.dp)
                                            .clip(RoundedCornerShape(topStart = AppSize.paneRadius, topEnd = AppSize.paneRadius))
                                            .background(if (selectedMoment != null) colors.pane else colors.background),
                                    ) {
                                        Fade(selectedMoment) { id ->
                                            if (id == null) EmptyPane("Tap a moment to read it here.") else MomentScreen(id, data.days, inPane = true) { days.removeAll { it is Page.Moment } }
                                        }
                                    }
                                }
                            } else {
                                Fade(days.lastOrNull { it is Page.Moment }) { top ->
                                    if (top is Page.Moment) {
                                        MomentScreen(top.id, data.days, inPane = false) { days.removeAt(days.lastIndex) }
                                    } else {
                                        holder.SaveableStateProvider("days") { daysRoot() }
                                    }
                                }
                            }
                        }
                        Tab.SETTINGS -> Fade(settings.lastOrNull()) { top ->
                            val back: () -> Unit = { settings.removeAt(settings.lastIndex) }
                            when (top) {
                                null -> holder.SaveableStateProvider("settings") {
                                    SettingsTab(
                                        handset, data.agents, data.days, setup, controlEnabled,
                                        onAgent = { settings.add(Page.Agent(it)) },
                                        onAddAgent = { settings.add(Page.AddAgent) },
                                        onExcludedApps = { settings.add(Page.ExcludedApps) },
                                        onSetup = { settings.add(Page.Setup) },
                                        onAbout = { settings.add(Page.About) },
                                        onLicences = { settings.add(Page.Licences) },
                                    )
                                }
                                Page.AddAgent -> AddAgentScreen(data.agents, handset.mcp, onBack = back) { id ->
                                    settings[settings.lastIndex] = Page.Agent(id)
                                }
                                is Page.Agent -> AgentScreen(top.id, data.agents, data.days, handset.mcp, onBack = back, onConnections = { settings.clear() }) { id ->
                                    tab = Tab.DAYS
                                    days.removeAll { it is Page.Moment }
                                    days.add(Page.Moment(id))
                                }
                                Page.ExcludedApps -> ExcludedAppsScreen(onBack = back)
                                Page.Setup -> setupScreen(back)
                                Page.About -> AboutScreen(onBack = back)
                                Page.Licences -> LicencesScreen(onBack = back)
                                else -> Unit
                            }
                        }
                    }
                }
            }
            // The keyboard covers the bar anyway, so it steps aside rather than ride above the keys
            if (!wide && !WindowInsets.isImeVisible) BottomBar(tab) { tab = it }
            Box(Modifier.navigationBarsPadding())
        }
    }

    if (monthOpen) {
        BoxWithConstraints {
            MonthSheet(date, today, firstDay, wide = maxWidth >= AppSize.wideFrom, onPick = { picked ->
                monthOpen = false
                scope.launch { pager.scrollToPage(ChronoUnit.DAYS.between(picked, today).toInt()) }
            }, onDismiss = { monthOpen = false })
        }
    }

    placeOpen?.let { visit ->
        val id = visit.id ?: return@let
        fun change(action: suspend () -> Unit) {
            placeOpen = null
            scope.launch {
                action()
                edits++
            }
        }
        BoxWithConstraints {
            PlaceSheet(visit, wide = maxWidth >= AppSize.wideFrom, onName = { change { data.days.namePlace(id, it) } }, onForget = { change { data.days.forgetPlace(id) } }, onDismiss = { placeOpen = null })
        }
    }
}

/** Fades between pages, fast in and a little slower out */
@Composable
private fun <T> Fade(target: T, content: @Composable (T) -> Unit) {
    AnimatedContent(
        targetState = target,
        transitionSpec = { fadeIn(tween(AppMotion.fast)) togetherWith fadeOut(tween(AppMotion.fast)) },
        label = "page",
    ) { content(it) }
}

/** One quiet line while an agent acts, with Stop, which turns Handset off as Stop in the pill does */
@Composable
private fun ActingLine(acting: String?, onStop: () -> Unit) {
    val colors = AppTheme.colors
    var shown by remember { mutableStateOf("") }
    if (acting != null) shown = acting
    AnimatedVisibility(acting != null, enter = expandVertically(tween(AppMotion.fast)), exit = shrinkVertically(tween(AppMotion.slow))) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = AppSize.actingHeight).padding(start = AppSize.pageX, end = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(6.dp).background(colors.live, CircleShape))
            Text(shown, style = AppType.rowText, color = colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextAction("Stop", onClick = onStop)
        }
    }
}

@Composable
private fun BottomBar(tab: Tab, onTab: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
        Tab.entries.forEach { item -> NavItem(item, item == tab, rail = false, Modifier.weight(1f).height(AppSize.navHeight)) { onTab(item) } }
    }
}

@Composable
private fun Rail(tab: Tab, onTab: (Tab) -> Unit) {
    Column(Modifier.width(AppSize.railWidth).fillMaxHeight().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Tab.entries.forEach { item ->
            NavItem(item, item == tab, rail = true, Modifier.width(AppSize.railItem).defaultMinSize(minHeight = 56.dp)) { onTab(item) }
        }
    }
}

/** A tab: an outline glyph and its name, in dark text when selected and grey otherwise */
@Composable
private fun NavItem(item: Tab, selected: Boolean, rail: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val color = if (selected) colors.text else colors.text3
    Column(
        modifier.pressable(RoundedCornerShape(16.dp), selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        PhIcon(item.icon, tint = color, size = if (rail) AppSize.iconSmall else AppSize.icon)
        val style = if (rail) (if (selected) AppType.railTabSelected else AppType.railTab) else if (selected) AppType.tabSelected else AppType.tab
        Text(item.title, style = style, color = color)
    }
}
