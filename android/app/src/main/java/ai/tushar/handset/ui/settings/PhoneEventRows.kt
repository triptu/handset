package ai.tushar.handset.ui.settings

import ai.tushar.handset.R
import ai.tushar.handset.context.ContextStore
import ai.tushar.handset.context.SendStatus
import ai.tushar.handset.events.Destination
import ai.tushar.handset.events.From
import ai.tushar.handset.events.PhoneEvent
import ai.tushar.handset.events.PhoneEvents
import ai.tushar.handset.events.Trigger
import ai.tushar.handset.ui.Chevron
import ai.tushar.handset.ui.CheckRow
import ai.tushar.handset.ui.ChoiceDialog
import ai.tushar.handset.ui.ConfirmDialog
import ai.tushar.handset.ui.DialogCard
import ai.tushar.handset.ui.FilledButton
import ai.tushar.handset.ui.InputField
import ai.tushar.handset.ui.ListRow
import ai.tushar.handset.ui.PhIcon
import ai.tushar.handset.ui.SectionLabel
import ai.tushar.handset.ui.SwitchRow
import ai.tushar.handset.ui.TextAction
import ai.tushar.handset.ui.theme.AppTheme
import ai.tushar.handset.ui.theme.AppType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phone events: the one address events go to, a switch per event, and a test. The routine that acts on them lives in
 * Claude or wherever the address leads; Handset only tells it what happened.
 */
@Composable
fun PhoneEventRows(events: PhoneEvents, store: ContextStore) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val destination = remember(version) { events.destinations.load() }
    val on = remember(version) { Trigger.entries.associateWith(events::isOn) }
    val threshold = remember(version) { events.threshold }
    val from = remember(version) { events.from }
    var editing by remember { mutableStateOf(false) }
    var pickLevel by remember { mutableStateOf(false) }
    var pickFrom by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<String?>(null) }

    fun switch(trigger: Trigger, want: Boolean) {
        events.setOn(trigger, want)
        version++
    }

    if (destination == null) {
        ListRow(
            "Send events to a routine",
            text = "Tell a Claude routine, or any web address, when the battery runs low, charging starts or stops, you arrive at or leave a place, or someone you pick writes.",
            textLines = 4,
            titleColor = colors.accent,
            lead = { PhIcon(R.drawable.ph_plus, tint = colors.accent) },
            onClick = { editing = true },
        )
    } else {
        ListRow("Send to", text = destination.label, textLines = 2, end = { Chevron() }, onClick = { editing = true })
        SwitchRow("Battery below $threshold%", "Once each time it runs down, until it charges again.", on[Trigger.BATTERY_LOW] == true) { switch(Trigger.BATTERY_LOW, it) }
        if (on[Trigger.BATTERY_LOW] == true) ListRow("Battery level", trailing = "$threshold%", onClick = { pickLevel = true })
        SwitchRow("Charging started or stopped", "Each time the charger goes in or comes out.", on[Trigger.CHARGING] == true) { switch(Trigger.CHARGING, it) }
        SwitchRow(
            "Arrived at or left a place",
            if (events.placesRecording) "Places you named in Days, once you've stayed 10 minutes, and when you leave." else "Nothing is sent while Places is off. Turn it on under What Handset records.",
            on[Trigger.PLACES] == true,
        ) { switch(Trigger.PLACES, it) }
        SwitchRow("A notification from someone", "From the people and apps you pick.", on[Trigger.NOTIFICATIONS] == true) { switch(Trigger.NOTIFICATIONS, it) }
        if (on[Trigger.NOTIFICATIONS] == true) {
            ListRow("From", text = from.joinToString(", ") { it.label }.ifEmpty { "No one yet" }, textLines = 2, end = { Chevron() }, onClick = { pickFrom = true })
        }
        Text(
            "An event carries its type, the time, and the battery level, place, person or app. Never what a message says. At most 20 are sent an hour, and each shows in Days.",
            style = AppType.rowText,
            color = colors.text2,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        TextAction("Send a test event", Modifier.offset(x = (-14).dp), color = colors.accent) {
            test = "Sending"
            scope.launch {
                val row = events.sendTest() ?: return@launch run { test = "Couldn't send. Check the address above." }
                // Shown as it goes, until it is sent, refused or out of tries
                repeat(TEST_POLLS) {
                    val now = store.eventSend(row.id) ?: return@launch
                    test = PhoneEvent.outcome(now)
                    if (now.status != SendStatus.PENDING.name) return@launch
                    delay(TEST_POLL_MS)
                }
            }
        }
        test?.let {
            Text(it, style = AppType.rowText, color = if (it.startsWith("Not sent") || it.startsWith("Couldn't")) colors.danger else colors.text2, modifier = Modifier.padding(bottom = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
    }

    if (editing) {
        DestinationDialog(destination, onDismiss = { editing = false }) { saved ->
            events.setDestination(saved)
            editing = false
            test = null
            version++
        }
    }
    if (pickLevel) {
        ChoiceDialog("Battery level", PhoneEvents.THRESHOLDS.map { "Below $it%" }, PhoneEvents.THRESHOLDS.indexOf(threshold), onDismiss = { pickLevel = false }) {
            events.threshold = PhoneEvents.THRESHOLDS[it]
            pickLevel = false
            version++
        }
    }
    if (pickFrom) {
        FromDialog(store, from, onDismiss = { pickFrom = false }) {
            events.from = it
            pickFrom = false
            version++
        }
    }
}

/** The address and token. Saving checks the address; Remove asks first, since a routine's token is shown only once. */
@Composable
private fun DestinationDialog(current: Destination?, onDismiss: () -> Unit, onSave: (Destination?) -> Unit) {
    val colors = AppTheme.colors
    var url by remember { mutableStateOf(current?.url.orEmpty()) }
    var token by remember { mutableStateOf(current?.token.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val routine = Destination(url.trim()).routine
    Dialog(onDismissRequest = onDismiss) {
        DialogCard("Where to send events", "A Claude routine's fire URL, or any address that takes a JSON POST.") {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Address")
                InputField(url, { url = it; error = null }, label = "Address", placeholder = "https://api.anthropic.com/v1/claude_code/…", imeAction = ImeAction.Next)
                FieldLabel("Token")
                InputField(token, { token = it; error = null }, label = "Token", placeholder = if (routine) "sk-ant-oat01-…" else "Leave empty if it needs none", secret = true)
                val note = error ?: if (routine) "A Claude routine. Handset adds the headers it needs." else "The token is sent as Authorization: Bearer."
                Text(note, style = AppType.rowText, color = if (error != null) colors.danger else colors.text2)
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (current != null) TextAction("Remove", Modifier.offset(x = (-14).dp), color = colors.danger) { confirmRemove = true }
                Spacer(Modifier.weight(1f))
                TextAction("Cancel", onClick = onDismiss)
                FilledButton("Save") {
                    Destination.parse(url, token).onSuccess(onSave).onFailure { error = it.message }
                }
            }
        }
    }
    if (confirmRemove) {
        ConfirmDialog(
            "Stop sending events?",
            "Handset forgets the address and token. A Claude routine shows its token only once, so you'd generate a new one to connect again.",
            confirm = "Remove",
            onDismiss = { confirmRemove = false },
        ) {
            confirmRemove = false
            onSave(null)
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = AppType.label, color = AppTheme.colors.text2, modifier = Modifier.padding(top = 4.dp))
}

/** People and apps that sent notifications in the last 30 days, with the ones already picked kept on top */
@Composable
private fun FromDialog(store: ContextStore, current: List<From>, onDismiss: () -> Unit, onDone: (List<From>) -> Unit) {
    val colors = AppTheme.colors
    var picked by remember { mutableStateOf(current.toSet()) }
    val choices by produceState<Pair<List<From.Person>, List<From.App>>?>(null) {
        value = PhoneEvents.choices(store.notificationSenders(System.currentTimeMillis() - 30 * ContextStore.DAY_MS, MAX_SENDERS), current)
    }
    fun toggle(choice: From, want: Boolean) {
        picked = if (want) picked + choice else picked - choice
    }
    Dialog(onDismissRequest = onDismiss) {
        DialogCard("Notifications from", "People and apps from the notifications Handset kept in the last 30 days.") {
            val (people, apps) = choices ?: (emptyList<From.Person>() to emptyList())
            Column(Modifier.padding(top = 8.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (choices != null && people.isEmpty() && apps.isEmpty()) {
                    Text("None yet. Turn on Notifications under What Handset records, and the people and apps that write to you show here.", style = AppType.rowText, color = colors.text2, modifier = Modifier.padding(vertical = 8.dp))
                }
                if (people.isNotEmpty()) SectionLabel("People", top = 8.dp)
                people.forEach { person -> CheckRow(person.name, null, person in picked) { toggle(person, it) } }
                if (apps.isNotEmpty()) SectionLabel("Apps", top = 16.dp)
                apps.forEach { app -> CheckRow(app.label, "Any notification from it", app in picked) { toggle(app, it) } }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                TextAction("Cancel", onClick = onDismiss)
                FilledButton("Done") { onDone(current.filter { it in picked } + (picked - current.toSet())) }
            }
        }
    }
}

private const val TEST_POLLS = 40
private const val TEST_POLL_MS = 750L
private const val MAX_SENDERS = 60
