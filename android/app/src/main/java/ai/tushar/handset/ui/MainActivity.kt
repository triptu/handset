package ai.tushar.handset.ui

import ai.tushar.handset.BuildConfig
import ai.tushar.handset.Handset
import ai.tushar.handset.ui.days.FakeDayRepository
import ai.tushar.handset.ui.days.Format
import ai.tushar.handset.ui.days.LocalFormat
import ai.tushar.handset.ui.days.StoreDayRepository
import ai.tushar.handset.ui.settings.McpAgentDirectory
import ai.tushar.handset.ui.settings.FakeAgentDirectory
import ai.tushar.handset.ui.theme.AppTheme
import ai.tushar.handset.ui.theme.HandsetTheme
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        val handset = Handset.instance
        val data = appData(this, handset, intent.takeIf { BuildConfig.DEBUG && it.hasExtra(SAMPLE_DATA) }?.getBooleanExtra(SAMPLE_DATA, false))
        setContent {
            HandsetTheme {
                Box(Modifier.fillMaxSize().background(AppTheme.colors.background)) { HandsetApp(handset, data) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val handset = Handset.instance
        handset.inFront(true)
        // The day record reads app sessions when the app opens, so the day is current
        handset.scope.launch { handset.sessions.pull() }
    }

    override fun onPause() {
        super.onPause()
        Handset.instance.inFront(false)
    }
}

/**
 * The real day record and agents. A debug build shows a made-up week instead once started with
 * `--ez sample_data true`, for screenshots, until started with `--ez sample_data false`.
 */
private fun appData(context: Context, handset: Handset, sample: Boolean?): AppData {
    val preferences = context.getSharedPreferences("debug", Context.MODE_PRIVATE)
    if (sample != null) preferences.edit().putBoolean(SAMPLE_DATA, sample).apply()
    return if (BuildConfig.DEBUG && preferences.getBoolean(SAMPLE_DATA, false)) {
        AppData(FakeDayRepository(), FakeAgentDirectory())
    } else {
        AppData(StoreDayRepository(handset.store, launchable = { context.packageManager.getLaunchIntentForPackage(it) != null }), McpAgentDirectory(handset.mcp.agents, handset.scope))
    }
}

private const val SAMPLE_DATA = "sample_data"

/** Where the app is: the intro and setup before Accessibility is on, then the tabs */
private enum class Stage { INTRO, SETUP, ABOUT, MAIN }

@Composable
private fun HandsetApp(handset: Handset, data: AppData) {
    val context = LocalContext.current
    // Permissions change in system settings, so read them again each time the app comes back
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val setup = remember(resumes) { SetupStatus.read(context) }
    val format = remember(resumes) { Format(ZoneId.systemDefault(), LocalDate.now(), DateFormat.is24HourFormat(context)) }
    var stage by rememberSaveable { mutableStateOf(if (setup.accessibility) Stage.MAIN else Stage.INTRO) }

    CompositionLocalProvider(LocalFormat provides format) {
        when (stage) {
            Stage.MAIN -> AppShell(handset, data, setup, resumes) { onBack ->
                SetupScreen(handset, setup, onBack = onBack, onDone = onBack, onSkip = null)
            }
            else -> Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                BackHandler(enabled = stage != Stage.INTRO) { stage = Stage.INTRO }
                when (stage) {
                    Stage.INTRO -> IntroScreen(onSetUp = { stage = Stage.SETUP }, onHowItWorks = { stage = Stage.ABOUT }, onSkip = { stage = Stage.MAIN })
                    Stage.SETUP -> SetupScreen(handset, setup, onBack = { stage = Stage.INTRO }, onDone = { stage = Stage.MAIN }, onSkip = { stage = Stage.MAIN })
                    Stage.ABOUT -> AboutScreen(onBack = { stage = Stage.INTRO })
                    Stage.MAIN -> Unit
                }
            }
        }
    }
}
