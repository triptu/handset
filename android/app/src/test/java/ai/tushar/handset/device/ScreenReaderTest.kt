package ai.tushar.handset.device

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen reader on screens recorded from real apps with the adb `dump` command, in `src/test/resources/screens`.
 * Personal details in them were replaced. To add a screen, record it and add what the agent must, and must not, see.
 */
class ScreenReaderTest {
    private fun read(name: String): ScreenSnapshot = recorded(name).let { ScreenReader.read(it.root, it.app, it.width, it.height) }

    private fun recorded(name: String): RecordedScreen {
        val file = javaClass.getResource("/screens/$name.json") ?: error("No recorded screen $name")
        return Json { ignoreUnknownKeys = true }.decodeFromString(RecordedScreen.serializer(), file.readText())
    }

    private fun ScreenSnapshot.line(text: String): String = this.text.lines().first { it.contains(text) }

    private fun ScreenSnapshot.lineOf(id: Int?): String = text.lines()[id ?: error("No element")]

    @Test
    fun floatingBarsDoNotHideTheAlarmList() {
        // Samsung Clock draws its tab bar and its add and menu buttons in a full-screen layer over the alarms
        val screen = read("samsung-clock-alarms")
        assertTrue(screen.text, screen.text.contains("Add alarm"))
        assertTrue(screen.text, screen.text.contains("call alex, 10:30 AM"))
        assertTrue(screen.text, screen.text.contains("World clock"))
    }

    @Test
    fun aCardDoesNotRepeatWhatItsSummarySays() {
        val alarm = read("samsung-clock-alarms").line("12:30 AM, 7 AM, Every day")
        assertFalse(alarm, alarm.contains("· AM ·"))
        assertFalse(alarm, alarm.contains("Every day (Every day,)"))
    }

    @Test
    fun aSheetOverPartOfThePageDoesNotHideIt() {
        // Swiggy's home with "Rate your previous order" open over its lower part
        val screen = read("swiggy-home")
        assertTrue(screen.text, screen.text.contains("Search for products"))
        assertTrue(screen.text, screen.text.contains("Rate your previous order"))
        assertTrue(screen.text, screen.text.contains("Crazy Deals"))
    }

    @Test
    fun aPageCoveredByTheNextIsLeftOut() {
        // Blinkit keeps the search page under its results
        val blinkit = read("blinkit-search-eggs")
        assertFalse(blinkit.text, blinkit.text.contains("Recent searches"))
        val stacked = read("chrome-stacked-pages")
        assertTrue(stacked.text, stacked.text.contains("Results for banana"))
        assertFalse(stacked.text, stacked.text.contains("Recent searches"))
    }

    @Test
    fun aSeeThroughLayerOverAMapHidesNothing() {
        val screen = read("chrome-map-overlay")
        assertTrue(screen.text, screen.text.contains("City Hospital"))
        assertTrue(screen.text, screen.text.contains("Where to?"))
    }

    @Test
    fun aProductCardIsOneLineWithItsButtons() {
        val screen = read("blinkit-search-eggs")
        val card = screen.line("item \"Suguna Delfrez Heart Speciality Eggs")
        assertTrue(card, card.contains("is available for ₹84"))
        assertTrue(card, card.contains("8 mins"))
        assertFalse(screen.text, screen.text.lines().any { it.trim().endsWith("\"8 mins\" @") || it.contains("text \"8 mins\"") })
        assertTrue(screen.text, screen.text.contains("\"ADD\""))
        assertTrue("${screen.nodes.size} elements", screen.nodes.size < 60)
    }

    @Test
    fun aTapTargetPrefersASuggestionToTheFieldThatHoldsTheText() {
        val screen = read("uber-indiranagar")
        val suggestion = screen.find("Indiranagar", avoidFields = true)
        assertNotEquals(screen.find("Indiranagar"), suggestion)
        assertTrue("picked [$suggestion]: ${screen.text.lines()[suggestion!!]}", screen.text.lines()[suggestion].contains("button \"Indiranagar, Bengaluru"))
    }

    @Test
    fun aListInsideAScrollingPageIsListed() {
        val screen = read("samsung-settings")
        assertTrue(screen.text, screen.text.contains("id=recycler_view"))
        assertEquals(1, screen.text.lines().count { it.contains("Connections") && it.contains("click") })
    }

    @Test
    fun expectNeedsTextOnTheLayerInFront() {
        // "milk" is only on the page that the results cover
        val screen = read("chrome-stacked-pages")
        assertFalse(screen.showsNew("milk", emptySet()))
        assertTrue(screen.showsNew("Banana Chips", emptySet()))
    }

    @Test
    fun expectIsNotMetByThePageUnderAResultsPageThatIsStillLoading() {
        // Blinkit adds the results page over its top picks; until the results come, the top picks show through
        val before = recorded("blinkit-eggs-suggestions").let { ScreenReader.marks(it.root, "is available for") }
        assertFalse(read("blinkit-eggs-loading").showsNew("is available for", before))
        assertTrue(read("blinkit-eggs-results").showsNew("is available for", before))
    }

    @Test
    fun aScrollTriesTheListOfItemsBeforeThePageAroundIt() {
        // Samsung Clock's alarms are a disabled grid view inside a scrolling page; Settings' list is inside a coordinator
        val clock = read("samsung-clock-alarms")
        assertTrue(clock.lineOf(clock.scrollOrder("down").first()), clock.lineOf(clock.scrollOrder("down").first()).contains("id=alarm_list"))
        val settings = read("samsung-settings")
        assertTrue(settings.lineOf(settings.scrollOrder("down").first()), settings.lineOf(settings.scrollOrder("down").first()).contains("id=recycler_view"))
    }

    @Test
    fun aTapTargetPrefersTheRowToItsSwitch() {
        val screen = read("samsung-clock-alarms")
        assertFalse(screen.lineOf(screen.find("9:20 PM")), screen.lineOf(screen.find("9:20 PM")).contains("switch"))
    }

    @Test
    fun onlyAProgressBarWithoutAValueIsLoading() {
        fun screen(range: Range?) = ScreenReader.read(
            RecordedNode(
                className = "android.widget.FrameLayout",
                bounds = Box(0, 0, 1080, 2400),
                flags = "vn",
                children = listOf(
                    RecordedNode(text = "Screen time", className = "android.widget.TextView", bounds = Box(0, 0, 500, 100), flags = "vn"),
                    RecordedNode(className = "android.widget.ProgressBar", bounds = Box(0, 100, 500, 140), flags = "vn", range = range),
                ),
            ),
            "Home",
            1080,
            2400,
        )
        assertFalse(screen(Range(78f, 0f, 100f)).loading)
        assertTrue(screen(null).loading)
    }
}
