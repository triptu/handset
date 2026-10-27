package ai.tushar.handset.ui.settings

import ai.tushar.handset.BuildConfig
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.offset
import ai.tushar.handset.ui.TextAction
import ai.tushar.handset.transport.relay.RelayState
import ai.tushar.handset.transport.relay.RelayClient
import androidx.compose.runtime.produceState
import android.content.ClipboardManager
import android.content.ClipData
import ai.tushar.handset.ui.days.DayRepository
import ai.tushar.handset.ui.PhIcon
import ai.tushar.handset.ui.Chevron
import ai.tushar.handset.transport.mcp.McpServer
import ai.tushar.handset.R
import ai.tushar.handset.Handset
import ai.tushar.handset.context.Keep
import ai.tushar.handset.context.Source
import ai.tushar.handset.device.Access
import ai.tushar.handset.ui.AgentGlyph
import ai.tushar.handset.ui.ChoiceDialog
import ai.tushar.handset.ui.ConfirmDialog
import ai.tushar.handset.ui.InputField
import ai.tushar.handset.ui.ListRow
import ai.tushar.handset.ui.PageColumn
import ai.tushar.handset.ui.PageHeader
import ai.tushar.handset.ui.SectionLabel
import ai.tushar.handset.ui.SetupStatus
import ai.tushar.handset.ui.SwitchRow
import ai.tushar.handset.ui.days.LocalFormat
import ai.tushar.handset.ui.theme.AppTheme
import ai.tushar.handset.ui.theme.AppType
import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Everything the user controls, in one list of plain rows: the agents first, then what Handset records, the rules every
 * agent follows, how agents reach the phone, the phone events sent to a routine, Android's permissions, and about.
 */
@Composable
fun SettingsTab(
    handset: Handset,
    agents: AgentDirectory,
    days: DayRepository,
    setup: SetupStatus,
    controlEnabled: Boolean,
    onAgent: (String) -> Unit,
    onAddAgent: () -> Unit,
    onExcludedApps: () -> Unit,
    onSetup: () -> Unit,
    onAbout: () -> Unit,
    onLicences: () -> Unit,
) {
    val colors = AppTheme.colors
    val format = LocalFormat.current
    val connected by agents.agents.collectAsState()
    val lastUsed by produceState(emptyMap<String, Instant>(), connected) {
        value = connected.mapNotNull { agent -> days.agentRuns(agent.id, agent.name, 1).firstOrNull()?.let { agent.id to it.at } }.toMap()
    }
    var askBeforeSending by remember { mutableStateOf(handset.askBeforeSending) }
    var showStatusPill by remember { mutableStateOf(handset.showStatusPill) }

    Column(Modifier.fillMaxSize()) {
        PageHeader("Settings", onBack = null)
        PageColumn(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionLabel("Connected agents", top = 4.dp)
            if (connected.isEmpty()) {
                Text("Add an agent to let Claude Code, Codex or another MCP app use this phone.", style = AppType.rowText, color = colors.text2, modifier = Modifier.padding(vertical = 8.dp))
            }
            connected.forEach { agent ->
                ListRow(
                    agent.name,
                    text = agent.via?.let { "via $it" } ?: agent.app?.let { listOfNotNull("On this phone", lastUsed[agent.id]?.let { "last used ${format.moment(it)}" }).joinToString(" · ") } ?: lastUsed[agent.id]?.let { "Last used ${format.moment(it)}" } ?: "Not used yet",
                    lead = { AgentGlyph(agent.name) },
                    end = { Chevron() },
                    onClick = { onAgent(agent.id) },
                )
            }
            ListRow("Add agent", titleColor = colors.accent, lead = { PhIcon(R.drawable.ph_plus, tint = colors.accent) }, onClick = onAddAgent)

            SectionLabel("What Handset records")
            RecordSwitches(handset, onExcludedApps)

            SectionLabel("Rules for every agent")
            SwitchRow("Let agents use this phone", "Off ends the step that is running and refuses new ones.", controlEnabled) { handset.switchControl(it) }
            SwitchRow("Ask me before tapping Send or Pay", "A tap on Send, Pay, Buy, Order or Post waits for you to tap Allow.", askBeforeSending) {
                askBeforeSending = it
                handset.askBeforeSending = it
            }
            SwitchRow("Show what the agent is doing", "A pill at the top of the screen while an agent works, with Stop.", showStatusPill) {
                showStatusPill = it
                handset.showStatusPill = it
            }

            SectionLabel("Connections")
            ConnectionRows(handset.relay, handset.mcp)

            SectionLabel("Phone events")
            PhoneEventRows(handset.events, handset.store)

            SectionLabel("Android permissions")
            ListRow("Accessibility", text = "Reads the screen and acts on it.", textLines = 2, trailing = if (setup.accessibility) "On" else "Off", onClick = onSetup)
            ListRow("Notification access", text = "Reads notifications for agents and the record.", textLines = 2, trailing = if (setup.notifications) "On" else "Off", onClick = onSetup)
            ListRow("Battery", text = "Unrestricted, so Android doesn't stop Handset in the background.", textLines = 2, trailing = if (setup.battery) "On" else "Off", onClick = onSetup)
            AccessSwitches(handset)

            SectionLabel("About")
            ListRow("How it works", onClick = onAbout)
            ListRow("Licences", text = "Google Sans Flex and Phosphor Icons", onClick = onLicences)
            Text("Handset ${BuildConfig.VERSION_NAME}", style = AppType.meta, color = colors.text3, modifier = Modifier.padding(top = 16.dp, bottom = 32.dp))
        }
    }
}

/**
 * How agents reach the phone. The relay comes first: it gives the phone a public address that claude.ai and ChatGPT can
 * use. The direct connection under it reaches the phone over Tailscale or on the phone itself, with nothing in between.
 */
@Composable
private fun ConnectionRows(relay: RelayClient, mcp: McpServer) {
    RelayRows(relay, mcp)
    DirectRows(mcp)
}

@Composable
private fun RelayRows(relay: RelayClient, mcp: McpServer) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val state by relay.state.collectAsState()
    val url by relay.publicMcpUrl.collectAsState()
    var enabled by remember(url) { mutableStateOf(relay.enabled) }
    var address by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    val publicUrl = url
    if (publicUrl == null) {
        ListRow("Relay", text = "A public address for claude.ai, ChatGPT and other apps on the web. Paste your relay's address to connect.", textLines = 3, onClick = null)
        InputField(address, { address = it.trim(); error = null }, label = "Relay address", placeholder = "https://your-relay.workers.dev", imeAction = ImeAction.Go)
        error?.let { Text(it, style = AppType.rowText, color = colors.danger, modifier = Modifier.padding(top = 6.dp)) }
        TextAction("Connect", Modifier.offset(x = (-14).dp), color = if (address.isBlank()) colors.text3 else colors.accent) {
            if (address.isBlank()) return@TextAction
            runCatching { relay.pair(address) }.onFailure { error = "That isn't a relay address. It starts with https://." }
        }
        return
    }
    val status = when (val now = state) {
        RelayState.Off -> "Off"
        RelayState.Connecting -> "Connecting"
        RelayState.Connected -> "Connected"
        is RelayState.Offline -> "Offline. ${now.reason}"
    }
    SwitchRow("Relay", "$status. claude.ai and ChatGPT reach the phone through it.", enabled) {
        enabled = it
        relay.setEnabled(it)
        mcp.resume()
    }
    ListRow("Public MCP address", text = publicUrl, textLines = 2, end = { PhIcon(R.drawable.ph_copy, tint = colors.text2, size = 20.dp, description = "Copy") }) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Public MCP address", publicUrl))
    }
    ListRow("Disconnect the relay", text = "Removes every agent that signed in through it.", titleColor = colors.danger, onClick = { confirmDisconnect = true })
    if (confirmDisconnect) {
        ConfirmDialog(
            "Disconnect the relay?",
            "Every claude.ai, ChatGPT and other agent that signed in through it is removed and loses access at once. You can connect again later.",
            confirm = "Disconnect",
            onDismiss = { confirmDisconnect = false },
        ) {
            confirmDisconnect = false
            relay.unpair()
        }
    }
}

/** The direct connection: its switch, where agents reach it, and whether plain Wi-Fi may */
@Composable
private fun DirectRows(mcp: McpServer) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val on by mcp.on.collectAsState()
    val wifi by mcp.wifi.collectAsState()
    val addresses by mcp.addresses.collectAsState()
    val problem by mcp.problem.collectAsState()
    SwitchRow(
        "Direct connection",
        "For agents on your own computers, over Tailscale or on the phone itself. More private: nothing passes through the relay or Cloudflare.",
        on,
    ) { mcp.switch(it) }
    if (!on) return
    problem?.let { Text(it, style = AppType.rowText, color = colors.danger, modifier = Modifier.padding(vertical = 6.dp)) }
    if (addresses.isEmpty() && problem == null) {
        Text(if (wifi) "No Tailscale or Wi-Fi address yet." else "No Tailscale address yet. Agents on this phone or over adb can connect.", style = AppType.rowText, color = colors.text2, modifier = Modifier.padding(vertical = 8.dp))
    }
    addresses.forEach { address ->
        val value = "${address.host}:${McpServer.PORT}"
        ListRow(address.label, text = value, end = { PhIcon(R.drawable.ph_copy, tint = colors.text2, size = 20.dp, description = "Copy") }) {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(address.label, value))
        }
    }
    SwitchRow("Also on Wi-Fi", "Anyone on the same Wi-Fi could see an agent's traffic. Use it on a network you trust.", wifi) { mcp.allowWifi(it) }
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

/** A switch per source of the day record, how long text is kept, Pause for an hour, Delete today and the apps never read */
@Composable
private fun RecordSwitches(handset: Handset, onExcludedApps: () -> Unit) {
    val recording = handset.recording
    val colors = AppTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val format = LocalFormat.current
    var version by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    val wanted = remember(version) { Source.entries.associateWith { recording.isOn(it) } }
    val granted = remember(version) { Source.entries.associateWith { it.granted(context) } }
    val pausedUntil by recording.pausedUntil.collectAsState()
    var keep by remember { mutableStateOf(recording.keep) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickKeep by remember { mutableStateOf(false) }

    // Android asks for background location on its own page, after the app may use location while open
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        handset.places.sync()
        version++
    }
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Access.LOCATION.granted(context)) background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        version++
    }

    fun ask(source: Source) {
        when (source) {
            Source.SCREEN -> Unit
            Source.NOTIFICATIONS -> context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Source.APP_USE -> context.startActivity(Access.SCREEN_TIME.settingsIntent(context))
            Source.PLACES -> if (Access.LOCATION.granted(context)) {
                background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                foreground.launch(Access.LOCATION.permissions.toTypedArray())
            }
        }
    }

    Source.entries.forEach { source ->
        val missing = wanted[source] == true && granted[source] != true
        SwitchRow(source.title, if (missing) "${source.detail} ${source.needs}" else source.detail, wanted[source] == true && granted[source] == true) { want ->
            recording.setOn(source, want)
            if (want && granted[source] != true) ask(source)
            if (source == Source.PLACES) handset.places.sync()
            if (source == Source.SCREEN && !want) handset.collector.stop()
            version++
        }
    }
    ListRow("Keep screen text and notifications", text = "App use and places are kept a year.", trailing = keep.label, onClick = { pickKeep = true })
    val paused = pausedUntil > System.currentTimeMillis()
    ListRow(
        if (paused) "Paused until ${format.time(Instant.ofEpochMilli(pausedUntil))}" else "Pause for an hour",
        text = if (paused) "Tap to start keeping again now." else "Nothing is kept until the hour is up.",
        onClick = {
            if (paused) {
                recording.resume()
            } else {
                recording.pauseForAnHour()
                handset.collector.stop()
            }
        },
    )
    ListRow("Delete today", text = "Everything kept since midnight.", titleColor = colors.danger, onClick = { confirmDelete = true })
    ListRow("Apps never read", text = "Password managers, payments, keyboards and the apps you add.", textLines = 2, onClick = onExcludedApps)

    if (pickKeep) {
        ChoiceDialog("Keep screen text and notifications", Keep.entries.map { it.label }, keep.ordinal, onDismiss = { pickKeep = false }) {
            keep = Keep.entries[it]
            recording.keep = keep
            pickKeep = false
            scope.launch { handset.store.clean(keep.days) }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Delete today?",
            "Deletes everything kept since midnight: screen text, notifications, app use, places and agent steps. Named places stay.",
            confirm = "Delete",
            onDismiss = { confirmDelete = false },
        ) {
            confirmDelete = false
            scope.launch { handset.store.deleteToday() }
        }
    }
}
