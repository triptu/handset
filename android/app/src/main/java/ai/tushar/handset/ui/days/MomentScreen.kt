package ai.tushar.handset.ui.days

import ai.tushar.handset.R
import ai.tushar.handset.ui.IconButton
import ai.tushar.handset.ui.Initial
import ai.tushar.handset.ui.PageColumn
import ai.tushar.handset.ui.PageHeader
import ai.tushar.handset.ui.theme.AppSize
import ai.tushar.handset.ui.theme.AppTheme
import ai.tushar.handset.ui.theme.AppType
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * A moment opened: the text you read in that session, a person's messages, or an agent's steps. In the pane beside the
 * list it closes with an X; on its own it goes back with an arrow.
 */
@Composable
fun MomentScreen(id: MomentId, repository: DayRepository, inPane: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val detail by produceState<MomentDetail?>(null, id) { value = repository.detail(id) }
    val app = when (val moment = detail?.moment) {
        is Moment.AppSession -> moment.app
        is Moment.Message -> moment.app
        else -> null
    }
    val launch = remember(app) { app?.let { launchIntent(context, it.packageName) } }
    Column(Modifier.fillMaxSize()) {
        PageHeader(
            backIcon = if (inPane) R.drawable.ph_x else R.drawable.ph_arrow_left,
            backLabel = if (inPane) "Close" else "Back",
            onBack = onClose,
        ) {
            if (app != null && launch != null) IconButton(R.drawable.ph_arrow_square_out, "Open in ${app.label}") { context.startActivity(launch) }
        }
        val loaded = detail ?: return@Column
        PageColumn(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(4.dp))
            when (loaded) {
                is MomentDetail.ScreenText -> ScreenTextBody(loaded)
                is MomentDetail.Conversation -> ConversationBody(loaded)
                is MomentDetail.AgentSteps -> AgentStepsBody(loaded)
                is MomentDetail.EventFields -> EventFieldsBody(loaded)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun launchIntent(context: Context, packageName: String): Intent? =
    context.packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

@Composable
private fun Meta(text: String) {
    Text(text, style = AppType.meta.copy(fontFeatureSettings = "tnum"), color = AppTheme.colors.text3, modifier = Modifier.padding(bottom = 14.dp))
}

@Composable
private fun Title(text: String) {
    Text(text, style = AppType.pageTitle, color = AppTheme.colors.text, modifier = Modifier.padding(bottom = 16.dp).semantics { heading() })
}

@Composable
private fun ScreenTextBody(detail: MomentDetail.ScreenText) {
    val colors = AppTheme.colors
    val format = LocalFormat.current
    val moment = detail.moment
    Meta("${moment.app.label} · ${format.longDay(format.dayOf(moment.at))}, ${format.time(moment.at)} · ${format.minutes(moment.at, moment.end)}")
    if (detail.blocks.isEmpty()) Text("Only the time in the app was kept. Handset reads no text in some apps, such as Settings, or while Screen text is off.", style = AppType.body, color = colors.text2)
    detail.blocks.forEach { block ->
        when (block) {
            is ScreenBlock.Text -> Text(block.text, style = AppType.body, color = colors.text, modifier = Modifier.widthIn(max = 560.dp).padding(bottom = 14.dp))
            is ScreenBlock.NewScreen -> Break(format.time(block.at))
        }
    }
}

/** A thin rule with a short label in it, between parts of what was read */
@Composable
private fun Break(label: String) {
    val colors = AppTheme.colors
    Row(Modifier.widthIn(max = 560.dp).padding(top = 2.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(Modifier.width(16.dp), thickness = 1.dp, color = colors.rule)
        Text(label, style = AppType.meta, color = colors.text3)
        HorizontalDivider(Modifier.weight(1f), thickness = 1.dp, color = colors.rule)
    }
}

@Composable
private fun ConversationBody(detail: MomentDetail.Conversation) {
    val colors = AppTheme.colors
    val format = LocalFormat.current
    val moment = detail.moment
    Meta("${moment.app.label} · ${format.longDay(format.dayOf(moment.at))}")
    detail.messages.forEach { line ->
        Row(Modifier.padding(vertical = 8.dp).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Initial(line.sender)
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(line.sender, style = AppType.label.copy(fontSize = AppType.rowText.fontSize), color = colors.text)
                    Text(format.time(line.at), style = AppType.meta.copy(fontFeatureSettings = "tnum"), color = colors.text3)
                }
                Text(line.text, style = AppType.bodySmall, color = colors.text, modifier = Modifier.padding(top = 1.dp))
            }
        }
    }
}

@Composable
private fun AgentStepsBody(detail: MomentDetail.AgentSteps) {
    val colors = AppTheme.colors
    val format = LocalFormat.current
    val moment = detail.moment
    Meta("${moment.agent} · ${format.longDay(format.dayOf(moment.at))}, ${format.time(moment.at)}")
    Title(moment.line)
    detail.steps.forEach { step ->
        Row(Modifier.padding(vertical = 8.dp).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(format.time(step.at), style = AppType.time, color = colors.text3, modifier = Modifier.widthIn(min = 64.dp))
            Text(step.text, style = AppType.bodySmall, color = colors.text, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun EventFieldsBody(detail: MomentDetail.EventFields) {
    val colors = AppTheme.colors
    val format = LocalFormat.current
    val moment = detail.moment
    Meta("Phone event · ${format.longDay(format.dayOf(moment.at))}, ${format.time(moment.at)}")
    Title(moment.title)
    Text(moment.line, style = AppType.body, color = colors.text, modifier = Modifier.padding(bottom = 20.dp))
    Text("What was sent", style = AppType.label, color = colors.text3, modifier = Modifier.padding(bottom = 6.dp))
    Text(detail.sentence, style = AppType.bodySmall, color = colors.text2, modifier = Modifier.widthIn(max = 560.dp).padding(bottom = 8.dp))
    Text(detail.payload, style = AppType.bodySmall.copy(fontFamily = FontFamily.Monospace), color = colors.text2, modifier = Modifier.widthIn(max = 560.dp))
}

/** The pane beside the list when nothing is open in it */
@Composable
fun EmptyPane(text: String) {
    Column(Modifier.fillMaxSize().padding(AppSize.pageX), verticalArrangement = Arrangement.Center) {
        Text(text, style = AppType.rowText, color = AppTheme.colors.text3, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}
