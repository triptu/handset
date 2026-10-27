package ai.tushar.handset.ui.settings

import ai.tushar.handset.R
import ai.tushar.handset.transport.mcp.Address
import ai.tushar.handset.transport.mcp.McpServer
import ai.tushar.handset.transport.mcp.clientSetups
import ai.tushar.handset.transport.mcp.serverUrl
import ai.tushar.handset.ui.FilledButton
import ai.tushar.handset.ui.PageColumn
import ai.tushar.handset.ui.PageHeader
import ai.tushar.handset.ui.TextAction
import ai.tushar.handset.ui.pressable
import ai.tushar.handset.ui.theme.AppSize
import ai.tushar.handset.ui.theme.AppTheme
import ai.tushar.handset.ui.theme.AppType
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/**
 * Adding an agent: its name, then its token and the setup for the app it runs in. Adding one turns the direct
 * connection on, since an agent with a token can't connect without it. claude.ai and ChatGPT sign in through the relay
 * instead.
 */
@Composable
fun AddAgentScreen(agents: AgentDirectory, mcp: McpServer, onBack: () -> Unit, onDone: (String) -> Unit) {
    var added by remember { mutableStateOf<Pair<String, NewToken>?>(null) }
    val shown = added
    if (shown != null) {
        TokenScreen(shown.first, shown.second.token, mcp) { onDone(shown.second.agentId) }
        return
    }
    val colors = AppTheme.colors
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun add() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (!mcp.on.value) mcp.switch(true)
        added = trimmed to agents.add(trimmed)
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Add agent", onBack = onBack)
        PageColumn(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("Name it after the app it runs in, so you can tell agents apart in your days.", style = AppType.rowText, color = colors.text2, modifier = Modifier.padding(bottom = 12.dp))
            BasicTextField(
                value = name,
                onValueChange = { name = it.take(MAX_NAME) },
                singleLine = true,
                // A name, not prose, so the keyboard mustn't turn Codex into Codec
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                textStyle = AppType.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Agent name" },
                decorationBox = { field ->
                    Box(Modifier.fillMaxWidth().heightIn(min = AppSize.field).background(colors.surface, RoundedCornerShape(AppSize.rowRadius)).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                        if (name.isEmpty()) Text("Claude Code", style = AppType.body, color = colors.text3)
                        field()
                    }
                },
            )
            Text(
                "It can read the screen and use your apps while it works. You choose what else it reads on its page.",
                style = AppType.rowText,
                color = colors.text2,
                modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
            )
            FilledButton("Add", enabled = name.isNotBlank(), onClick = ::add)
        }
    }
}

/**
 * The token, shown once, inside the setup for each kind of app. The window is kept out of screenshots, recordings and
 * the recents thumbnail while it shows.
 */
@Composable
fun TokenScreen(name: String, token: String, mcp: McpServer, onDone: () -> Unit) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val addresses by mcp.addresses.collectAsState()
    val window = (LocalView.current.context as? Activity)?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    var address by remember { mutableIntStateOf(0) }
    val host = addresses.getOrNull(address)?.host ?: LOCALHOST
    val setups = clientSetups(serverUrl(host), token)
    var client by remember { mutableIntStateOf(0) }
    val setup = setups[client]

    Column(Modifier.fillMaxSize()) {
        PageHeader("Connect $name", backIcon = R.drawable.ph_x, backLabel = "Done", onBack = onDone)
        PageColumn(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                "Paste this where the agent runs. The token shows only now. Handset keeps a fingerprint of it, not the token itself.",
                style = AppType.rowText,
                color = colors.text2,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Choices(setups.map { it.client }, client) { client = it }
            if (addresses.size > 1) Choices(addresses.map(Address::label), address, Modifier.padding(top = 8.dp)) { address = it }
            Text(
                setup.text,
                style = AppType.meta.copy(fontFamily = FontFamily.Monospace, color = colors.text),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).background(colors.surface, RoundedCornerShape(AppSize.rowRadius)).padding(14.dp),
            )
            TextAction("Copy", Modifier.offset(x = (-14).dp).padding(top = 4.dp), color = colors.accent, icon = R.drawable.ph_copy) { copy(context, setup.text) }
            if (addresses.isEmpty()) {
                Text(
                    "No Tailscale address yet, so this uses the phone itself. From a computer on USB, run adb forward tcp:${McpServer.PORT} tcp:${McpServer.PORT} first, or connect Tailscale and come back.",
                    style = AppType.rowText,
                    color = colors.text2,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            FilledButton("Done", onClick = onDone)
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** A row of small choices, one selected */
@Composable
private fun Choices(labels: List<String>, selected: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    val colors = AppTheme.colors
    Row(modifier.horizontalScroll(rememberScrollState()).offset(x = (-4).dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            Box(
                Modifier.heightIn(min = 40.dp)
                    .background(if (on) colors.surface else colors.background, CircleShape)
                    .pressable(CircleShape, selected = on, role = Role.RadioButton) { onPick(index) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = AppType.label, color = if (on) colors.text else colors.text2)
            }
        }
    }
}

/** Marked sensitive, so Android 13 and later don't show the token in the clipboard preview */
private fun copy(context: Context, text: String) {
    val clip = ClipData.newPlainText("Handset", text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

private const val MAX_NAME = 40
private const val LOCALHOST = "127.0.0.1"
