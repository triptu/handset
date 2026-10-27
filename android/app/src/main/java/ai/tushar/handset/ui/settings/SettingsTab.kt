package ai.tushar.handset.ui.settings

import ai.tushar.handset.BuildConfig
import ai.tushar.handset.Handset
import ai.tushar.handset.R
import ai.tushar.handset.context.Source
import ai.tushar.handset.device.Access
import ai.tushar.handset.events.Trigger
import ai.tushar.handset.ui.AgentGlyph
import ai.tushar.handset.ui.Chevron
import ai.tushar.handset.ui.ListRow
import ai.tushar.handset.ui.PageColumn
import ai.tushar.handset.ui.PageHeader
import ai.tushar.handset.ui.PhIcon
import ai.tushar.handset.ui.SectionLabel
import ai.tushar.handset.ui.SetupStatus
import ai.tushar.handset.ui.SwitchRow
import ai.tushar.handset.ui.days.DayRepository
import ai.tushar.handset.ui.days.LocalFormat
import ai.tushar.handset.ui.theme.AppTheme
import ai.tushar.handset.ui.theme.AppType
import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * Settings' top level: the switch that lets agents use the phone, then a row per [SettingsSection] with its state in a
 * few words. [selected] is the section open beside the list on a wide screen.
 */
@Composable
fun SettingsTab(
    handset: Handset,
    agents: AgentDirectory,
    setup: SetupStatus,
    controlEnabled: Boolean,
    selected: SettingsSection?,
    onSection: (SettingsSection) -> Unit,
) {
    val colors = AppTheme.colors
    val format = LocalFormat.current
    val state = rememberSettingsState(handset, agents, setup)

    Column(Modifier.fillMaxSize()) {
        PageHeader("Settings", onBack = null)
        PageColumn(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SwitchRow(
                "Let agents use this phone",
                if (controlEnabled) "Off ends the step that is running and refuses new ones." else "Agents can't act until you turn this on.",
                controlEnabled,
                lead = { PhIcon(R.drawable.ph_hand_tap) },
            ) { handset.switchControl(it) }
            Spacer(Modifier.height(12.dp))
            SettingsSection.entries.forEach { section ->
                val summary = state.summary(section, format::time)
                ListRow(
                    section.title,
                    text = summary.text,
                    textColor = if (summary.attention) colors.danger else colors.text2,
                    selected = section == selected,
                    lead = { PhIcon(section.icon) },
                    end = { Chevron() },
                    onClick = { onSection(section) },
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** Read again when a setting changes in any of these, when the app comes back, and as the flows below change */
private val PREFERENCE_FILES = listOf("handset", "events", "relay")

@Composable
private fun rememberSettingsState(handset: Handset, agents: AgentDirectory, setup: SetupStatus): SettingsState {
    val context = LocalContext.current
    val connected by agents.agents.collectAsState()
    val relay by handset.relay.state.collectAsState()
    val relayUrl by handset.relay.publicMcpUrl.collectAsState()
    val direct by handset.mcp.on.collectAsState()
    val pausedUntil by handset.recording.pausedUntil.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    DisposableEffect(context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }
        val files = PREFERENCE_FILES.map { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        files.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        onDispose { files.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) } }
    }
    // A pause ends on the clock, not with a change to read, so the summary is read again when it does
    LaunchedEffect(pausedUntil) {
        val left = pausedUntil - System.currentTimeMillis()
        if (left > 0) {
            delay(left)
            version++
        }
    }
    return remember(version, connected, relay, relayUrl, direct, pausedUntil, setup) {
        val recording = handset.recording
        SettingsState(
            agents = connected.map { it.name },
            recording = Source.entries.filter { recording.isOn(it) && it.granted(context) },
            keep = recording.keep,
            pausedUntil = Instant.ofEpochMilli(pausedUntil).takeIf { pausedUntil > System.currentTimeMillis() },
            relay = relay.takeIf { relayUrl != null },
            direct = direct,
            events = handset.events.destinations.load()?.let { Trigger.entries.filter(handset.events::isOn) },
            askBeforeSending = handset.askBeforeSending,
            needAttention = SetupStatus.STEPS - setup.doneCount,
            version = BuildConfig.VERSION_NAME,
        )
    }
}

/** A section's page: its title, with back on a phone and none when it sits beside the list, over its rows */
@Composable
fun SectionFrame(section: SettingsSection, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(section.title, onBack = onBack)
        PageColumn(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            content()
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** The pages a section opens, which the shell pushes on Settings' stack */
class SettingsLinks(
    val onAgent: (String) -> Unit,
    val onAddAgent: () -> Unit,
    val onExcludedApps: () -> Unit,
    val onSetup: () -> Unit,
    val onAbout: () -> Unit,
    val onLicences: () -> Unit,
)

@Composable
fun SettingsSectionScreen(section: SettingsSection, handset: Handset, agents: AgentDirectory, days: DayRepository, setup: SetupStatus, links: SettingsLinks, onBack: (() -> Unit)?) {
    when (section) {
        SettingsSection.AGENTS -> AgentsSection(agents, days, onBack, links)
        SettingsSection.CONNECTIONS -> ConnectionsSection(handset, onBack)
        SettingsSection.SAFETY -> SafetySection(handset, onBack)
        SettingsSection.RECORDING -> RecordingSection(handset, onBack, links.onExcludedApps)
        SettingsSection.PHONE_EVENTS -> SectionFrame(section, onBack) { PhoneEventRows(handset.events, handset.store) }
        SettingsSection.PERMISSIONS -> PermissionsSection(handset, setup, onBack, links.onSetup)
        SettingsSection.ABOUT -> AboutSection(onBack, links)
    }
}

@Composable
private fun AgentsSection(agents: AgentDirectory, days: DayRepository, onBack: (() -> Unit)?, links: SettingsLinks) {
    val colors = AppTheme.colors
    val format = LocalFormat.current
    val connected by agents.agents.collectAsState()
    val lastUsed by produceState(emptyMap<String, Instant>(), connected) {
        value = connected.mapNotNull { agent -> days.agentRuns(agent.id, agent.name, 1).firstOrNull()?.let { agent.id to it.at } }.toMap()
    }
    SectionFrame(SettingsSection.AGENTS, onBack) {
        if (connected.isEmpty()) {
            Text("Add an agent to let Claude Code, Codex or another MCP app use this phone.", style = AppType.rowText, color = colors.text2, modifier = Modifier.padding(vertical = 8.dp))
        }
        connected.forEach { agent ->
            ListRow(
                agent.name,
                text = agent.via?.let { "via $it" } ?: agent.app?.let { listOfNotNull("On this phone", lastUsed[agent.id]?.let { "last used ${format.moment(it)}" }).joinToString(" · ") } ?: lastUsed[agent.id]?.let { "Last used ${format.moment(it)}" } ?: "Not used yet",
                lead = { AgentGlyph(agent.name) },
                end = { Chevron() },
                onClick = { links.onAgent(agent.id) },
            )
        }
        ListRow("Add agent", titleColor = colors.accent, lead = { PhIcon(R.drawable.ph_plus, tint = colors.accent) }, onClick = links.onAddAgent)
    }
}

@Composable
private fun SafetySection(handset: Handset, onBack: (() -> Unit)?) {
    var askBeforeSending by remember { mutableStateOf(handset.askBeforeSending) }
    var showStatusPill by remember { mutableStateOf(handset.showStatusPill) }
    SectionFrame(SettingsSection.SAFETY, onBack) {
        SwitchRow("Ask me before tapping Send or Pay", "A tap on Send, Pay, Buy, Order or Post waits for your Allow.", askBeforeSending) {
            askBeforeSending = it
            handset.askBeforeSending = it
        }
        SwitchRow("Show what the agent is doing", "A pill at the top of the screen while an agent works, with Stop.", showStatusPill) {
            showStatusPill = it
            handset.showStatusPill = it
        }
    }
}

@Composable
private fun PermissionsSection(handset: Handset, setup: SetupStatus, onBack: (() -> Unit)?, onSetup: () -> Unit) {
    SectionFrame(SettingsSection.PERMISSIONS, onBack) {
        SectionLabel("Needed", top = 4.dp)
        ListRow("Accessibility", text = "Reads the screen and acts on it.", trailing = if (setup.accessibility) "On" else "Off", onClick = onSetup)
        ListRow("Notification access", text = "Reads notifications as they arrive.", trailing = if (setup.notifications) "On" else "Off", onClick = onSetup)
        ListRow("Battery", text = "Unrestricted, so Android keeps Handset running.", textLines = 2, trailing = if (setup.battery) "On" else "Off", onClick = onSetup)
        SectionLabel("Optional")
        AccessSwitches(handset)
    }
}

@Composable
private fun AboutSection(onBack: (() -> Unit)?, links: SettingsLinks) {
    val colors = AppTheme.colors
    SectionFrame(SettingsSection.ABOUT, onBack) {
        ListRow("How it works", end = { Chevron() }, onClick = links.onAbout)
        ListRow("Licences", text = "Google Sans Flex and Phosphor Icons", end = { Chevron() }, onClick = links.onLicences)
        Text("Handset ${BuildConfig.VERSION_NAME}", style = AppType.meta, color = colors.text3, modifier = Modifier.padding(top = 16.dp))
    }
}

/**
 * A switch for each [Access], on when the user turned it on and Android has given it. Turning one on asks for its
 * permission, or opens its settings page.
 */
@Composable
fun AccessSwitches(handset: Handset) {
    val context = LocalContext.current
    // Grants change on system pages, so they are read again each time the app comes back, and after each prompt
    var version by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    val on = remember(version) { Access.entries.associateWith { handset.isAccessOn(it) && it.granted(context) } }
    var asked by remember { mutableStateOf<List<Access>>(emptyList()) }
    var openScreenTimeAfter by remember { mutableStateOf(false) }
    var single by remember { mutableStateOf(false) }

    val prompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // The switch stays wanted when Android says no, so granting it later in the system's settings turns it on
        asked.forEach { access -> handset.setAccessOn(access, true) }
        // Android stops showing a prompt after the user said no twice, so a single switch leads to the app's settings
        asked.singleOrNull()?.takeIf { single && !it.granted(context) }?.let { context.startActivity(it.settingsIntent(context)) }
        if (openScreenTimeAfter) context.startActivity(Access.SCREEN_TIME.settingsIntent(context))
        openScreenTimeAfter = false
        version++
    }

    fun turnOn(accesses: List<Access>, alone: Boolean) {
        single = alone
        accesses.forEach { handset.setAccessOn(it, true) }
        val screenTime = Access.SCREEN_TIME in accesses && !Access.SCREEN_TIME.granted(context)
        // Screen time is granted on a settings page; the switch shows on once the user comes back with it allowed
        val needPrompt = accesses.filter { it.permissions.isNotEmpty() && !it.granted(context) }
        if (needPrompt.isNotEmpty()) {
            asked = needPrompt
            openScreenTimeAfter = screenTime
            prompt.launch(needPrompt.flatMap { it.permissions }.toTypedArray())
        } else if (screenTime) {
            context.startActivity(Access.SCREEN_TIME.settingsIntent(context))
        }
        version++
    }

    Access.entries.forEach { access ->
        SwitchRow(access.title, access.detail, on[access] == true) { want ->
            if (want) {
                turnOn(listOf(access), alone = true)
            } else {
                handset.setAccessOn(access, false)
                version++
            }
        }
    }
}

/** Turns on every [Access] that is off, asking Android for all of them in one go, for setup */
@Composable
fun rememberTurnAllOn(handset: Handset, onDone: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val prompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!Access.SCREEN_TIME.granted(context)) context.startActivity(Access.SCREEN_TIME.settingsIntent(context))
        onDone()
    }
    return {
        Access.entries.forEach { handset.setAccessOn(it, true) }
        val needPrompt = Access.entries.filter { it.permissions.isNotEmpty() && !it.granted(context) }
        if (needPrompt.isNotEmpty()) {
            prompt.launch(needPrompt.flatMap { it.permissions }.toTypedArray())
        } else {
            if (!Access.SCREEN_TIME.granted(context)) context.startActivity(Access.SCREEN_TIME.settingsIntent(context))
            onDone()
        }
    }
}
