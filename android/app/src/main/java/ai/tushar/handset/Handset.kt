package ai.tushar.handset

import ai.tushar.handset.context.AgentActionRow
import ai.tushar.handset.context.ContextCollector
import ai.tushar.handset.context.ExcludedApps
import ai.tushar.handset.context.ContextDatabase
import ai.tushar.handset.context.ContextStore
import ai.tushar.handset.context.ContextWorker
import ai.tushar.handset.context.NotificationCollector
import ai.tushar.handset.context.PlaceCollector
import ai.tushar.handset.context.Recording
import ai.tushar.handset.context.SessionCollector
import ai.tushar.handset.context.StoredScreenText
import ai.tushar.handset.device.Access
import ai.tushar.handset.device.Consent
import ai.tushar.handset.device.PhoneAccessibilityService
import ai.tushar.handset.events.PhoneEvents
import ai.tushar.handset.runner.ToolCall
import ai.tushar.handset.runner.ToolRunner
import ai.tushar.handset.tools.CallLabels
import ai.tushar.handset.tools.ToolArgs
import ai.tushar.handset.tools.Toolkit
import ai.tushar.handset.transport.mcp.McpServer
import ai.tushar.handset.transport.mcp.certificate
import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The process scope of the app: the tools, the runner every transport calls, and the user's settings */
class Handset(context: Context) {
    private val preferences = context.getSharedPreferences("handset", Context.MODE_PRIVATE)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The day record: what the phone remembers, in the app's private storage, and the collectors that feed it */
    val recording = Recording(context, preferences)
    val store = ContextStore(ContextDatabase.open(context))
    val sessions = SessionCollector(context, store, recording)
    val places = PlaceCollector(context, store, recording) { arrived, visitId -> events.onVisit(arrived, visitId) }
    val notifications = NotificationCollector(context, store, recording, scope)

    /** What happens on the phone, sent to the user's routine when they turned it on */
    val events = PhoneEvents(context, store, recording, scope)

    val consent = Consent(context)
    val tools = Toolkit(context)

    private val _acting = MutableStateFlow<String?>(null)

    /** What an agent is doing right now, as the pill says it, or null while no call runs */
    val acting: StateFlow<String?> get() = _acting

    /** Runs the calls of every transport, so turning Handset off ends them all */
    val runner = ToolRunner(
        tools.all,
        object : ToolRunner.Host {
            override val enabled get() = controlEnabled
            override val askBeforeSending get() = this@Handset.askBeforeSending
            override suspend fun ask(prompt: String) = consent.ask(prompt)
            override suspend fun currentScreen() = tools.phone.actions.readText()
            override fun record(call: ToolCall, detail: String, ok: Boolean) = recordAgentAction(call, detail, ok)

            // "Claude Code is opening WhatsApp", or "Handset · Opening WhatsApp" when the caller has no name, as adb
            override fun showStatus(label: String, agent: String?) {
                val line = agent?.let { "$it is ${label.replaceFirstChar(Char::lowercaseChar)}" } ?: "Handset · $label"
                _acting.value = line
                pill()?.show(line)
            }

            override fun clearStatus() {
                _acting.value = null
                pill()?.done()
            }


        },
    )

    @Volatile private var appInFront = false

    /** The floating pill, while the user wants it and Handset's own screens, which show the acting line, aren't in front */
    private fun pill() = PhoneAccessibilityService.instance?.pill?.takeIf { showStatusPill && !appInFront }

    /** Hands what an agent is doing from the pill to the line in the app as the app comes to the front, and back as it leaves */
    fun inFront(front: Boolean) {
        appInFront = front
        val pill = PhoneAccessibilityService.instance?.pill?.takeIf { showStatusPill } ?: return
        val line = _acting.value
        if (front) pill.hideNow() else if (line != null) pill.show(line)
    }

    init {
        ExcludedApps.load(preferences)
    }

    /** The MCP server's switch and the agents the user added */
    val mcp = McpServer(context)
    val relay = ai.tushar.handset.transport.relay.RelayClient(context, this)

    /** The text of the screens the user saw, while Screen text is on. It is written to the store, on the phone. */
    val collector = ContextCollector(this, StoredScreenText(store, scope))

    private val _control = MutableStateFlow(preferences.getBoolean(CONTROL_ENABLED, true))

    /** [controlEnabled] as the screens show it, which Stop in the pill turns off too */
    val control: StateFlow<Boolean> get() = _control

    /** Whether agents may use this phone. Off refuses every call. */
    var controlEnabled: Boolean
        get() = preferences.getBoolean(CONTROL_ENABLED, true)
        set(value) {
            preferences.edit().putBoolean(CONTROL_ENABLED, value).apply()
            _control.value = value
        }

    /** Whether a pill at the top of the screen says what the agent is doing while it works, with Stop */
    var showStatusPill: Boolean
        get() = preferences.getBoolean(SHOW_STATUS_PILL, true)
        set(value) = preferences.edit().putBoolean(SHOW_STATUS_PILL, value).apply()

    /** Whether a tap on Send or Pay waits for the user's Allow */
    var askBeforeSending: Boolean
        get() = preferences.getBoolean(ASK_BEFORE_SENDING, true)
        set(value) = preferences.edit().putBoolean(ASK_BEFORE_SENDING, value).apply()

    /**
     * Keeps one step an agent took, or was refused, in the day record, whatever the record's own switches say: what
     * agents do on the phone is always written down. Calls over adb have no agent name.
     */
    fun recordAgentAction(call: ToolCall, detail: String, ok: Boolean) {
        val row = AgentActionRow(
            at = System.currentTimeMillis(),
            agent = call.agent ?: "adb",
            agentId = call.agentId,
            tool = call.tool,
            summary = CallLabels.describe(call.tool, ToolArgs(call.params)),
            outcome = if (ok) "ok" else detail.lineSequence().first().take(MAX_OUTCOME),
        )
        scope.launch { store.addAgentAction(row) }
    }

    /** Whether the user turned on [access], at setup or in Settings; Android's permission is checked apart from this */
    fun isAccessOn(access: Access): Boolean = preferences.getBoolean(access.pref, false)

    fun setAccessOn(access: Access, on: Boolean) = preferences.edit().putBoolean(access.pref, on).apply()

    /** Off ends the calls running and waiting at once and refuses new ones. The day record has its own switches. */
    fun switchControl(enabled: Boolean) {
        controlEnabled = enabled
        if (!enabled) runner.stopAll()
    }

    companion object {
        private const val CONTROL_ENABLED = "control_enabled"
        private const val ASK_BEFORE_SENDING = "ask_before_sending"
        private const val SHOW_STATUS_PILL = "show_status_pill"
        private const val MAX_OUTCOME = 200

        lateinit var instance: Handset
            private set

        fun install(application: Application) {
            if (::instance.isInitialized) return
            instance = Handset(application)
            ContextWorker.schedule(application)
            instance.places.sync()
            instance.events.sync()
            // An app uninstalled while Handset wasn't told loses its agent here
            instance.mcp.agents.forgetGoneApps(application.packageManager::certificate)
        }
    }
}

class HandsetApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Handset.install(this)
    }
}
