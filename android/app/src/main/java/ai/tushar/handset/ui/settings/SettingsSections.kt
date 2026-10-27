package ai.tushar.handset.ui.settings

import ai.tushar.handset.R
import ai.tushar.handset.context.Keep
import ai.tushar.handset.context.Source
import ai.tushar.handset.events.Trigger
import ai.tushar.handset.transport.relay.RelayState
import androidx.annotation.DrawableRes
import java.time.Instant

/** Settings' top level, one row per section, in this order. Agents' error messages name these titles. */
enum class SettingsSection(val title: String, @DrawableRes val icon: Int) {
    AGENTS("Agents", R.drawable.ph_robot),
    RECORDING("Recording", R.drawable.ph_record),
    CONNECTIONS("Connections", R.drawable.ph_plugs),
    PHONE_EVENTS("Phone events", R.drawable.ph_lightning),
    SAFETY("Safety", R.drawable.ph_shield_check),
    PERMISSIONS("Android permissions", R.drawable.ph_key),
    ABOUT("About", R.drawable.ph_info),
}

/**
 * What the top level reads to sum up each section. [agents] are the names of the agents that are on, beside a count of
 * [pausedAgents]. [recording] holds the sources that are on and allowed, [relay] is
 * null until a relay is connected, [events] is null until events have an address, and [pausedUntil] is null unless
 * recording is paused.
 */
data class SettingsState(
    val agents: List<String>,
    val pausedAgents: Int,
    val recording: List<Source>,
    val keep: Keep,
    val pausedUntil: Instant?,
    val relay: RelayState?,
    val direct: Boolean,
    val events: List<Trigger>?,
    val askBeforeSending: Boolean,
    val needAttention: Int,
    val version: String,
)

/** A section's current state in a few words. Only a permission that needs the user is [attention]. */
data class Summary(val text: String, val attention: Boolean = false)

fun SettingsState.summary(section: SettingsSection, time: (Instant) -> String): Summary = when (section) {
    SettingsSection.AGENTS -> Summary(
        listOfNotNull(agents.joinToString(", ").ifEmpty { null }, "$pausedAgents paused".takeIf { pausedAgents > 0 }).joinToString(" · ").ifEmpty { "None yet" },
    )
    SettingsSection.CONNECTIONS -> Summary(
        listOfNotNull(
            when (relay) {
                null -> null
                RelayState.Off -> "Relay off"
                RelayState.Connecting -> "Relay connecting"
                RelayState.Connected -> "Relay connected"
                is RelayState.Offline -> "Relay offline"
            },
            "Direct connection on".takeIf { direct },
        ).joinToString(" · ").ifEmpty { "Off" },
    )
    SettingsSection.SAFETY -> Summary(if (askBeforeSending) "Asks before Send or Pay" else "Doesn't ask before Send or Pay")
    SettingsSection.RECORDING -> Summary(
        when {
            pausedUntil != null -> "Paused until ${time(pausedUntil)}"
            recording.isEmpty() -> "Off"
            // How long applies to screen text and notifications alone
            recording.any { it == Source.SCREEN || it == Source.NOTIFICATIONS } -> "${sentence(recording.map { it.title })} · ${keep.label}"
            else -> sentence(recording.map { it.title })
        },
    )
    SettingsSection.PHONE_EVENTS -> Summary(events?.takeIf { it.isNotEmpty() }?.let { on -> sentence(on.map(::eventName)) } ?: "Off")
    SettingsSection.PERMISSIONS -> when (needAttention) {
        0 -> Summary("All on")
        1 -> Summary("1 needs attention", attention = true)
        else -> Summary("$needAttention need attention", attention = true)
    }
    SettingsSection.ABOUT -> Summary("Version $version")
}

private fun eventName(trigger: Trigger) = when (trigger) {
    Trigger.BATTERY_LOW -> "Battery"
    Trigger.CHARGING -> "Charging"
    Trigger.PLACES -> "Places"
    Trigger.NOTIFICATIONS -> "Notifications"
}

/** "Screen text, notifications and places": the first name as it is, the rest lower case */
private fun sentence(names: List<String>): String {
    val words = names.mapIndexed { i, name -> if (i == 0) name else name.lowercase() }
    return if (words.size <= 1) words.joinToString() else words.dropLast(1).joinToString(", ") + " and " + words.last()
}
