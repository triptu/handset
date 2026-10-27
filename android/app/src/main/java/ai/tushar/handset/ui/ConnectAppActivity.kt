package ai.tushar.handset.ui

import ai.tushar.handset.Handset
import ai.tushar.handset.transport.mcp.McpServer
import ai.tushar.handset.transport.mcp.PATH
import ai.tushar.handset.transport.mcp.PhoneApp
import ai.tushar.handset.transport.mcp.Source
import ai.tushar.handset.transport.mcp.certificate
import ai.tushar.handset.ui.theme.AppSize
import ai.tushar.handset.ui.theme.AppTheme
import ai.tushar.handset.ui.theme.AppType
import ai.tushar.handset.ui.theme.HandsetTheme
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Lets another app on this phone connect as an agent with one tap. The app starts [ACTION] for a result, so Android
 * names the caller, and gets back the local MCP address and its own token. README.md has the contract.
 */
class ConnectAppActivity : ComponentActivity() {
    /** Waiting for Handset to hold the loopback port, so Allow can't be tapped twice */
    private var connecting by mutableStateOf(false)

    /** Why the last Allow did nothing, as a tap under another app's window or a port another app holds */
    private var notice by mutableStateOf<String?>(null)

    /** Where Allow is in the window */
    private var allowBounds = Rect.Zero
    private val guard = ObscuredTouchGuard { x, y -> allowBounds.contains(Offset(x, y)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Another app's window drawn over Allow could trick a tap onto it. Android 12 hides most; dispatchTouchEvent drops the rest.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
        val handset = Handset.instance
        val caller = callingPackage?.takeIf { it != packageName }
        val app = caller?.let { packageManager.certificate(it) }?.let { PhoneApp(caller, it) }
        when {
            app == null -> return refuse(NOT_FOR_RESULT)
            !handset.controlEnabled -> return refuse(HANDSET_OFF)
        }
        setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_REASON, DENIED))
        val info = packageManager.getApplicationInfo(app.packageName, 0)
        val label = packageManager.getApplicationLabel(info).toString()
        val icon = packageManager.getApplicationIcon(info).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
        val known = handset.mcp.agents.forApp(app)

        enableEdgeToEdge()
        setContent {
            HandsetTheme {
                ConnectAppScreen(
                    label, app.packageName, icon, known?.sources.orEmpty(), reconnect = known != null, asksFirst = handset.askBeforeSending,
                    connecting = connecting, notice = notice, onAllowBounds = { allowBounds = it }, onDeny = { refuse(DENIED) },
                ) { sources -> allow(label, app, sources) }
            }
        }
    }

    /** Gives the token only once Handset's own socket holds 127.0.0.1's port, so no other app listening there receives it */
    private fun allow(label: String, app: PhoneApp, sources: Set<Source>) {
        if (connecting) return
        connecting = true
        notice = null
        lifecycleScope.launch {
            val mcp = Handset.instance.mcp
            if (!mcp.awaitLocal(BIND_TIMEOUT_MS)) {
                setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_REASON, PORT_IN_USE))
                notice = "Handset couldn't open port ${McpServer.PORT} on this phone, so it gave no token. Another app may be using the port. Close it and tap Allow again."
                connecting = false
                return@launch
            }
            val (agent, token) = mcp.agents.connectApp(label, app, sources)
            setResult(
                RESULT_OK,
                Intent().putExtra(EXTRA_MCP_URL, "http://127.0.0.1:${McpServer.PORT}$PATH").putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_AGENT_ID, agent.id),
            )
            finish()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val pointer = event.actionIndex
        return when (guard.check(event.actionMasked, event.flags, event.getX(pointer), event.getY(pointer))) {
            ObscuredTouchGuard.Verdict.PASS -> super.dispatchTouchEvent(event)
            ObscuredTouchGuard.Verdict.CANCEL -> {
                notice = "Another app was drawing over Handset, so that tap didn't count. Close that app's window and tap Allow again."
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                true
            }
            ObscuredTouchGuard.Verdict.DROP -> true
        }
    }

    override fun onResume() {
        super.onResume()
        Handset.instance.inFront(true)
    }

    override fun onPause() {
        super.onPause()
        Handset.instance.inFront(false)
    }

    private fun refuse(reason: String) {
        setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_REASON, reason))
        finish()
    }

    companion object {
        const val ACTION = "ai.tushar.handset.action.CONNECT_APP"
        const val EXTRA_MCP_URL = "mcp_url"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_AGENT_ID = "agent_id"
        const val EXTRA_REASON = "reason"
        const val DENIED = "denied"
        const val NOT_FOR_RESULT = "not_for_result"
        const val HANDSET_OFF = "handset_off"
        const val PORT_IN_USE = "port_in_use"
        private const val BIND_TIMEOUT_MS = 3_000L
        private const val ICON_PX = 192
    }
}

private val SourcesSaver = listSaver<Set<Source>, String>({ it.map(Source::name) }, { it.map(Source::valueOf).toSet() })

/** "<App> wants to use this phone": what that means, the same switches as an agent's page, and Allow */
@Composable
private fun ConnectAppScreen(label: String, packageName: String, icon: ImageBitmap, initial: Set<Source>, reconnect: Boolean, asksFirst: Boolean, connecting: Boolean, notice: String?, onAllowBounds: (Rect) -> Unit, onDeny: () -> Unit, onAllow: (Set<Source>) -> Unit) {
    val colors = AppTheme.colors
    var sources by rememberSaveable(stateSaver = SourcesSaver) { mutableStateOf(initial) }
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PageColumn(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Image(icon, contentDescription = null, modifier = Modifier.padding(top = 40.dp).size(56.dp))
            Text("$label wants to use this phone", style = AppType.pageTitle, color = colors.text, modifier = Modifier.padding(top = 16.dp).semantics { heading() })
            Text(packageName, style = AppType.meta, color = colors.text3, modifier = Modifier.padding(top = 2.dp))
            Text(
                "Like any agent you connect, it reads the screen, opens apps, and taps and types in them." +
                    (if (asksFirst) " Handset asks you before it taps Send or Pay." else "") +
                    " You can remove it in Settings, under Connected agents.",
                style = AppType.bodySmall,
                color = colors.text2,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (reconnect) Text("It's connected already. Allow gives it a new token in place of the old one.", style = AppType.bodySmall, color = colors.text2, modifier = Modifier.padding(top = 8.dp))

            SectionLabel("What else it can read", top = 24.dp)
            Source.entries.forEach { source ->
                SwitchRow(source.title, source.detail, source in sources) { on -> sources = if (on) sources + source else sources - source }
            }
        }
        Column(Modifier.widthIn(max = AppSize.pageMaxWidth).fillMaxWidth().padding(horizontal = AppSize.pageX, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (notice != null) {
                Text(
                    notice,
                    style = AppType.bodySmall,
                    color = colors.danger,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            FilledButton("Allow", Modifier.fillMaxWidth().onGloballyPositioned { onAllowBounds(it.boundsInWindow()) }, enabled = !connecting) { onAllow(sources) }
            TextAction("Don't allow", Modifier.padding(top = 4.dp), color = colors.text2, onClick = onDeny)
        }
    }
}
