package ai.tushar.handset.ui

import ai.tushar.handset.Handset
import ai.tushar.handset.R
import ai.tushar.handset.device.Access
import ai.tushar.handset.device.NotificationWatcher
import ai.tushar.handset.device.PhoneAccessibilityService
import ai.tushar.handset.ui.settings.AccessSwitches
import ai.tushar.handset.ui.settings.rememberTurnAllOn
import ai.tushar.handset.ui.theme.AppSize
import ai.tushar.handset.ui.theme.AppTheme
import ai.tushar.handset.ui.theme.AppType
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Which of the three system permissions are on. Read again each time the app comes back, because they change in system settings. */
data class SetupStatus(val accessibility: Boolean, val notifications: Boolean, val battery: Boolean) {
    val doneCount: Int get() = listOf(accessibility, notifications, battery).count { it }
    val complete: Boolean get() = doneCount == STEPS

    companion object {
        const val STEPS = 3

        fun read(context: Context) = SetupStatus(
            accessibility = PhoneAccessibilityService.instance != null || accessibilityEnabled(context),
            notifications = notificationAccessEnabled(context),
            battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
        )

        private fun accessibilityEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.contains(ComponentName(context, PhoneAccessibilityService::class.java).flattenToString())
        }

        private fun notificationAccessEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            return enabled.contains(ComponentName(context, NotificationWatcher::class.java).flattenToString())
        }
    }
}

/** The first screen, before Accessibility is on: what the app is, in three lines */
@Composable
fun IntroScreen(onSetUp: () -> Unit, onHowItWorks: () -> Unit, onSkip: () -> Unit) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxSize().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.weight(1f).widthIn(max = AppSize.pageMaxWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AppSize.pageX),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Let an AI agent use your phone", style = AppType.pageTitle.copy(fontSize = AppType.pageTitle.fontSize * 1.3f, lineHeight = AppType.pageTitle.lineHeight * 1.3f), color = colors.text, modifier = Modifier.semantics { heading() })
            Text("Your agent opens apps, taps and types for you. Everything stays on this phone.", style = AppType.bodySmall, color = colors.text2, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
            IntroPoint(R.drawable.ph_chat_circle, "You ask your agent", "Say what you want done, like “Reply to my last WhatsApp message”.")
            IntroPoint(R.drawable.ph_hand_tap, "It does it on this phone", "It reads the screen, then taps, types and scrolls, the way you would.")
            IntroPoint(R.drawable.ph_lock_simple, "You stay in control", "A line at the top says what it is doing, with Stop.")
        }
        Column(Modifier.widthIn(max = AppSize.pageMaxWidth).fillMaxWidth().padding(horizontal = AppSize.pageX), horizontalAlignment = Alignment.CenterHorizontally) {
            FilledButton("Set up", Modifier.fillMaxWidth(), onClick = onSetUp)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextAction("How it works", onClick = onHowItWorks)
                TextAction("Not now", color = colors.text2, onClick = onSkip)
            }
        }
    }
}

@Composable
private fun IntroPoint(icon: Int, title: String, detail: String) {
    ListRow(title, text = detail, textLines = 3, lead = { PhIcon(icon, tint = AppTheme.colors.text2) }, onClick = null)
}

/** The checklist of the three permissions, each with the button that opens its system settings page, then the optional access */
@Composable
fun SetupScreen(handset: Handset, setup: SetupStatus, onBack: (() -> Unit)?, onDone: () -> Unit, onSkip: (() -> Unit)?) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    var accessVersion by remember { mutableIntStateOf(0) }
    val turnAllOn = rememberTurnAllOn(handset) { accessVersion++ }

    fun openAccessibility() = context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    fun openAppInfo() = context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

    Column(Modifier.fillMaxSize()) {
        PageHeader("Set up", onBack = onBack)
        PageColumn(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("Each one opens a page in Android's settings. Turn it on there, then come back.", style = AppType.bodySmall, color = colors.text2)
            SectionLabel("Needed", trailing = "${setup.doneCount} of ${SetupStatus.STEPS} done")
            SetupRow("Accessibility", "Lets agents read the screen and tap, type and scroll.", setup.accessibility, onOpen = ::openAccessibility)
            if (!setup.accessibility) AccessibilitySteps(onOpenAccessibility = ::openAccessibility, onOpenAppInfo = ::openAppInfo)
            SetupRow("Notification access", "Lets it read and reply to messages.", setup.notifications) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            SetupRow("Battery unrestricted", "Keeps it reachable while the screen is off.", setup.battery) {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            }
            // Asked for here, all at once, so the user isn't stopped later in the middle of a request
            SectionLabel("Optional")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Agents can do more with these. Each has a switch in Settings.", style = AppType.rowText, color = colors.text2, modifier = Modifier.weight(1f))
                val allOn = remember(accessVersion) { Access.entries.all { handset.isAccessOn(it) && it.granted(context) } }
                if (!allOn) TextAction("Turn all on", Modifier.offset(x = 14.dp), color = colors.accent, onClick = turnAllOn)
            }
            androidx.compose.runtime.key(accessVersion) { AccessSwitches(handset) }
            Spacer(Modifier.height(24.dp))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = AppSize.pageX).padding(top = 8.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!setup.accessibility) Text("Turn on Accessibility to continue.", style = AppType.rowText, color = colors.text2, modifier = Modifier.padding(bottom = 8.dp))
            FilledButton(if (setup.complete) "Done" else "Continue", Modifier.widthIn(max = AppSize.pageMaxWidth).fillMaxWidth(), enabled = setup.accessibility, onClick = onDone)
            if (onSkip != null && !setup.accessibility) TextAction("Not now", color = colors.text2, onClick = onSkip)
        }
    }
}

@Composable
private fun SetupRow(title: String, detail: String, done: Boolean, onOpen: () -> Unit) {
    val colors = AppTheme.colors
    ListRow(
        title,
        modifier = Modifier.semantics { stateDescription = if (done) "Done" else "Not done" },
        text = detail,
        textLines = 2,
        lead = {
            if (done) {
                Box(Modifier.size(24.dp).background(colors.live, CircleShape), contentAlignment = Alignment.Center) {
                    PhIcon(R.drawable.ph_check, tint = colors.background, size = 14.dp)
                }
            } else {
                Box(Modifier.size(24.dp).border(2.dp, colors.text3, CircleShape))
            }
        },
        // The whole row opens the page; the words only show that it does
        end = if (done) null else ({ Text("Turn on", style = AppType.button, color = colors.accent) }),
        onClick = if (done) null else onOpen,
    )
}

/** The three steps that let a sideloaded app's accessibility service be turned on, on Android 13 and later */
@Composable
private fun AccessibilitySteps(onOpenAccessibility: () -> Unit, onOpenAppInfo: () -> Unit) {
    val colors = AppTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(start = AppSize.lead + AppSize.rowGap, bottom = 8.dp).background(colors.surface, RoundedCornerShape(AppSize.rowRadius)).padding(16.dp),
    ) {
        Text(
            "Android greys out the switch for an app installed from a file, until you allow restricted settings.",
            style = AppType.rowText,
            color = colors.text2,
        )
        Step(1, "Open Accessibility and try to turn on Handset once.", "Open Accessibility", onOpenAccessibility)
        Step(2, "Open App info, tap ⋮ at the top right and choose Allow restricted settings.", "Open App info", onOpenAppInfo)
        Step(3, "Come back to Accessibility and turn it on.", "Open Accessibility", onOpenAccessibility)
    }
}

@Composable
private fun Step(number: Int, text: String, action: String, onAction: () -> Unit) {
    val colors = AppTheme.colors
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("$number.", style = AppType.rowText, color = colors.text3)
        Column(Modifier.weight(1f)) {
            Text(text, style = AppType.rowText, color = colors.text)
            TextAction(action, Modifier.offset(x = (-14).dp), color = colors.accent, onClick = onAction)
        }
    }
}
