package ai.tushar.handset.ui.settings

import ai.tushar.handset.Handset
import ai.tushar.handset.context.Keep
import ai.tushar.handset.context.Source
import ai.tushar.handset.device.Access
import ai.tushar.handset.ui.Chevron
import ai.tushar.handset.ui.ChoiceDialog
import ai.tushar.handset.ui.ConfirmDialog
import ai.tushar.handset.ui.ListRow
import ai.tushar.handset.ui.SectionLabel
import ai.tushar.handset.ui.SwitchRow
import ai.tushar.handset.ui.days.LocalFormat
import ai.tushar.handset.ui.theme.AppTheme
import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import java.time.Instant

/** A switch per source of the day record and the apps never read, then how long text is kept, Pause for an hour and Delete today */
@Composable
fun RecordingSection(handset: Handset, onBack: (() -> Unit)?, onExcludedApps: () -> Unit) {
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

    SectionFrame(SettingsSection.RECORDING, onBack) {
        SectionLabel("What it records", top = 4.dp)
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
        ListRow("Apps never read", text = "Password managers, payments, keyboards and the apps you add.", textLines = 2, end = { Chevron() }, onClick = onExcludedApps)

        SectionLabel("Your record")
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
    }

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
