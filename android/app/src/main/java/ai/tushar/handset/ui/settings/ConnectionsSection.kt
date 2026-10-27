package ai.tushar.handset.ui.settings

import ai.tushar.handset.Handset
import ai.tushar.handset.R
import ai.tushar.handset.transport.mcp.McpServer
import ai.tushar.handset.transport.relay.RelayClient
import ai.tushar.handset.transport.relay.RelayState
import ai.tushar.handset.ui.ConfirmDialog
import ai.tushar.handset.ui.InputField
import ai.tushar.handset.ui.ListRow
import ai.tushar.handset.ui.PhIcon
import ai.tushar.handset.ui.SwitchRow
import ai.tushar.handset.ui.TextAction
import ai.tushar.handset.ui.theme.AppSize
import ai.tushar.handset.ui.theme.AppTheme
import ai.tushar.handset.ui.theme.AppType
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * How agents reach the phone. The relay comes first: it gives the phone a public address that claude.ai and ChatGPT can
 * use. The direct connection under it reaches the phone over Tailscale or on the phone itself, with nothing in between.
 */
@Composable
fun ConnectionsSection(handset: Handset, onBack: (() -> Unit)?) {
    SectionFrame(SettingsSection.CONNECTIONS, onBack) {
        RelayRows(handset.relay, handset.mcp)
        Spacer(Modifier.height(AppSize.sectionTop))
        DirectRows(handset.mcp)
    }
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
        ListRow("Relay", text = "For claude.ai, ChatGPT and other web apps. Paste your relay's address.", textLines = 2, onClick = null)
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
    SwitchRow("Relay", "$status. For claude.ai and ChatGPT.", enabled) {
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
    SwitchRow("Direct connection", "For agents on your computers over Tailscale, or on this phone. Nothing passes through Cloudflare.", on) { mcp.switch(it) }
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
    SwitchRow("Also on Wi-Fi", "Anyone on the same Wi-Fi could see the traffic. Use a network you trust.", wifi) { mcp.allowWifi(it) }
}
