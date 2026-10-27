package ai.tushar.handset.ui.settings

import ai.tushar.handset.context.Keep
import ai.tushar.handset.context.Source
import ai.tushar.handset.events.Trigger
import ai.tushar.handset.transport.relay.RelayState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SettingsSummaryTest {
    private val off = SettingsState(
        agents = emptyList(),
        recording = emptyList(),
        keep = Keep.MONTH,
        pausedUntil = null,
        relay = null,
        direct = false,
        events = null,
        askBeforeSending = true,
        needAttention = 0,
        version = "1.0",
    )

    private fun SettingsState.text(section: SettingsSection) = summary(section) { "3:15 pm" }.text

    @Test
    fun `a fresh phone reads as off or none in every section`() {
        assertEquals("None yet", off.text(SettingsSection.AGENTS))
        assertEquals("Off", off.text(SettingsSection.CONNECTIONS))
        assertEquals("Off", off.text(SettingsSection.RECORDING))
        assertEquals("Off", off.text(SettingsSection.PHONE_EVENTS))
        assertEquals("All on", off.text(SettingsSection.PERMISSIONS))
        assertEquals("Version 1.0", off.text(SettingsSection.ABOUT))
    }

    @Test
    fun `agents are listed by name`() {
        assertEquals("Claude, Claude Code", off.copy(agents = listOf("Claude", "Claude Code")).text(SettingsSection.AGENTS))
    }

    @Test
    fun `recording names its sources and how long text is kept`() {
        assertEquals("Screen text and notifications · 30 days", off.copy(recording = listOf(Source.SCREEN, Source.NOTIFICATIONS)).text(SettingsSection.RECORDING))
        assertEquals("App use and places", off.copy(recording = listOf(Source.APP_USE, Source.PLACES)).text(SettingsSection.RECORDING))
        assertEquals("Paused until 3:15 pm", off.copy(recording = listOf(Source.SCREEN), pausedUntil = Instant.EPOCH).text(SettingsSection.RECORDING))
    }

    @Test
    fun `connections name the relay's state and the direct connection`() {
        assertEquals("Relay connected · Direct connection on", off.copy(relay = RelayState.Connected, direct = true).text(SettingsSection.CONNECTIONS))
        assertEquals("Relay offline", off.copy(relay = RelayState.Offline("No network")).text(SettingsSection.CONNECTIONS))
    }

    @Test
    fun `phone events list what is on, and are off with no switch on`() {
        assertEquals("Battery and charging", off.copy(events = listOf(Trigger.BATTERY_LOW, Trigger.CHARGING)).text(SettingsSection.PHONE_EVENTS))
        assertEquals("Off", off.copy(events = emptyList()).text(SettingsSection.PHONE_EVENTS))
    }

    @Test
    fun `only permissions that need the user draw attention`() {
        assertEquals(Summary("2 need attention", attention = true), off.copy(needAttention = 2).summary(SettingsSection.PERMISSIONS) { "" })
        assertEquals(Summary("1 needs attention", attention = true), off.copy(needAttention = 1).summary(SettingsSection.PERMISSIONS) { "" })
        assertEquals(false, off.copy(relay = RelayState.Offline("x")).summary(SettingsSection.CONNECTIONS) { "" }.attention)
    }

    @Test
    fun `safety says whether Send and Pay wait for the user`() {
        assertEquals("Asks before Send or Pay", off.text(SettingsSection.SAFETY))
        assertEquals("Doesn't ask before Send or Pay", off.copy(askBeforeSending = false).text(SettingsSection.SAFETY))
    }
}
