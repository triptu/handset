package ai.tushar.handset.ui.settings

import ai.tushar.handset.R
import ai.tushar.handset.transport.mcp.McpServer
import ai.tushar.handset.transport.mcp.Source
import ai.tushar.handset.ui.ConfirmDialog
import ai.tushar.handset.ui.AgentGlyph
import ai.tushar.handset.ui.Chevron
import ai.tushar.handset.ui.ListRow
import ai.tushar.handset.ui.PageColumn
import ai.tushar.handset.ui.PageHeader
import ai.tushar.handset.ui.SectionLabel
import ai.tushar.handset.ui.SwitchRow
import ai.tushar.handset.ui.TextAction
import ai.tushar.handset.ui.days.DayRepository
import ai.tushar.handset.ui.days.LocalFormat
import ai.tushar.handset.ui.days.Moment
import ai.tushar.handset.ui.days.MomentId
import ai.tushar.handset.ui.theme.AppTheme
import ai.tushar.handset.ui.theme.AppType
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/** One agent: what it may read, how it reaches the phone, its last runs, and Remove */
@Composable
fun AgentScreen(id: String, agents: AgentDirectory, days: DayRepository, mcp: McpServer, onBack: () -> Unit, onConnections: () -> Unit, onMoment: (MomentId) -> Unit) {
    val colors = AppTheme.colors
    val format = LocalFormat.current
    val all by agents.agents.collectAsState()
    val agent = all.firstOrNull { it.id == id }
    val on by mcp.on.collectAsState()
    val addresses by mcp.addresses.collectAsState()
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmRenew by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf<String?>(null) }
    // Removing it leaves nothing to show here
    LaunchedEffect(agent == null) { if (agent == null) onBack() }
    agent ?: return
    val recent by produceState(emptyList<Moment.AgentRun>(), agent.id, agent.name) { value = days.agentRuns(agent.id, agent.name, RECENT) }

    token?.let { shown ->
        TokenScreen(agent.name, shown, mcp) { token = null }
        return
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(agent.name, onBack = onBack)
        PageColumn(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (agent.added != null) {
                val day = format.longDay(agent.added).let { if (agent.added >= LocalDate.now().minusDays(1)) it.lowercase() else it }
                Text("Added $day.", style = AppType.rowText, color = colors.text2)
            }

            SectionLabel("What it can read")
            Text(
                "It always reads the screen, the phone's status and apps, and what Handset records apart from notifications and places. Turn on more for ${agent.name} alone.",
                style = AppType.rowText,
                color = colors.text2,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Source.entries.forEach { source ->
                SwitchRow(source.title, source.detail, source in agent.sources) { agents.setSource(agent.id, source, it) }
            }

            SectionLabel("How it connects")
            if (agent.via != null) {
                ListRow("Through the relay", text = "Signed in from ${agent.via}. Remove it here to cut it off; it has no token to renew.", textLines = 3, end = { Chevron() }, onClick = onConnections)
            }
            if (agent.canRenew) {
                val where = when {
                    !on -> "Direct connection is off, so ${agent.name} can't reach the phone."
                    addresses.isEmpty() -> "On this phone only, until Tailscale connects."
                    else -> addresses.joinToString(", ") { "${it.label}, ${it.host}:${McpServer.PORT}" }
                }
                ListRow("Direct connection", text = where, textLines = 3, end = { Chevron() }, onClick = onConnections)
                ListRow("New token", text = "For a new computer, or if the token leaked. The old one stops working.", textLines = 2, onClick = { confirmRenew = true })
            }

            if (recent.isNotEmpty()) {
                SectionLabel("Recent")
                // A day's row, without the name the page already shows
                recent.forEach { run ->
                    ListRow(run.line, trailing = format.moment(run.at), titleStyle = AppType.rowText.copy(color = colors.text), lead = { AgentGlyph(agent.name) }) { onMoment(run.id) }
                }
            }

            Spacer(Modifier.height(20.dp))
            TextAction("Remove ${agent.name}", Modifier.offset(x = (-14).dp), color = colors.danger, icon = R.drawable.ph_trash) { confirmRemove = true }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmRenew) {
        ConfirmDialog(
            "Make a new token?",
            "${agent.name} stops working until you paste the new setup where it runs.",
            confirm = "Make token",
            onDismiss = { confirmRenew = false },
        ) {
            confirmRenew = false
            token = agents.renew(agent.id)?.token
        }
    }
    if (confirmRemove) {
        ConfirmDialog(
            "Remove ${agent.name}?",
            "Its token stops working at once. To use it again, add it again.",
            confirm = "Remove",
            onDismiss = { confirmRemove = false },
        ) {
            confirmRemove = false
            agents.remove(agent.id)
        }
    }
}

private const val RECENT = 3
