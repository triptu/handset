package ai.tushar.handset.ui

import ai.tushar.handset.ui.theme.AppTheme
import ai.tushar.handset.ui.theme.AppType
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ABOUT = listOf(
    "How it works" to listOf(
        "You ask your AI agent to do something on your phone.",
        "It opens apps, taps and types on this phone, and a line at the top of the screen says what it is doing.",
        "Each step shows up in your day, under the agent's name.",
    ),
    "What it can see and do" to listOf(
        "Read what is on the screen.",
        "Tap, type, scroll and open apps.",
        "Read and reply to notifications.",
        "Read your messages, contacts, location and photos, only when you turn each on for that agent.",
    ),
    "What it never does" to listOf(
        "Send what it reads anywhere but the agent you connected.",
        "Run while it is turned off.",
        "Keep your screens unless you turn that on in Settings.",
        "Tap Send or Pay without your Allow, while that setting is on.",
    ),
    "Stopping it" to listOf(
        "Tap Stop at the top of the screen, or turn off “Let agents use this phone” in Settings.",
    ),
)

/** What the app is, what it can and cannot do, and how to stop it, in short lines */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxSize()) {
        PageHeader("How it works", onBack = onBack)
        PageColumn(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("An AI agent uses your phone for you. You stay in control.", style = AppType.bodySmall, color = colors.text2)
            ABOUT.forEach { (heading, lines) ->
                SectionLabel(heading)
                lines.forEach { Text(it, style = AppType.bodySmall, color = colors.text, modifier = Modifier.padding(vertical = 6.dp)) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private val LICENCES = listOf("Google Sans Flex" to "licenses/google-sans-flex-OFL.txt", "Phosphor Icons" to "licenses/phosphor-icons-MIT.txt")

/** The licences of the font and icons the app bundles */
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val texts by produceState<List<Pair<String, String>>>(emptyList()) {
        value = withContext(Dispatchers.IO) { LICENCES.map { (name, path) -> name to context.assets.open(path).bufferedReader().use { it.readText() } } }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Licences", onBack = onBack)
        PageColumn(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            texts.forEach { (name, text) ->
                SectionLabel(name, top = 12.dp)
                Text(text.trim(), style = AppType.meta.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp), color = colors.text2)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
