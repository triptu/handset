package ai.tushar.handset.ui.settings

import ai.tushar.handset.context.ExcludedApps
import ai.tushar.handset.ui.LauncherIcon
import ai.tushar.handset.ui.PageHeader
import ai.tushar.handset.ui.SectionLabel
import ai.tushar.handset.ui.SwitchRow
import ai.tushar.handset.ui.days.AppRef
import ai.tushar.handset.ui.theme.AppSize
import ai.tushar.handset.ui.theme.AppTheme
import ai.tushar.handset.ui.theme.AppType
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val BUILT_IN = listOf(
    "Password managers and authenticators",
    "Payment and wallet apps",
    "Keyboards",
    "Settings and file managers",
    "The home screen and system screens",
    "Password fields in every app",
)

/** The apps whose screens are never read: the built-in list, then a switch for every other app */
@Composable
fun ExcludedAppsScreen(onBack: () -> Unit) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val apps by produceState<List<AppRef>?>(null) { value = withContext(Dispatchers.IO) { launchableApps(context) } }
    var chosen by remember { mutableStateOf(ExcludedApps.chosen) }

    Column(Modifier.fillMaxSize()) {
        PageHeader("Apps never read", onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = AppSize.pageX, vertical = 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(Modifier.widthIn(max = AppSize.pageMaxWidth)) {
                    Text("Handset never reads these, for your days or for an agent.", style = AppType.bodySmall, color = colors.text2)
                    SectionLabel("Always")
                    BUILT_IN.forEach { Text(it, style = AppType.bodySmall, color = colors.text, modifier = Modifier.padding(vertical = 8.dp)) }
                    SectionLabel("Your apps")
                }
            }
            items(apps.orEmpty(), key = { it.packageName }) { app ->
                val off = app.packageName in chosen
                Column(Modifier.widthIn(max = AppSize.pageMaxWidth)) {
                    SwitchRow(app.label, null, off, lead = { LauncherIcon(app.packageName) }) { never ->
                        ExcludedApps.choose(app.packageName, never)
                        chosen = ExcludedApps.chosen
                    }
                }
            }
            item { Column(Modifier.padding(bottom = 32.dp)) {} }
        }
    }
}

/** Apps with a launcher icon, except this one and the built-in list, by name */
private fun launchableApps(context: Context): List<AppRef> {
    val pm = context.packageManager
    return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { AppRef(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { it.packageName != context.packageName && !ExcludedApps.isBuiltIn(it.packageName) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}
