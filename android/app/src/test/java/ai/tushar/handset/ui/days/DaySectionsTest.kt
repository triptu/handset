package ai.tushar.handset.ui.days

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

class DaySectionsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 10, 27)
    private val app = AppRef("com.example", "Example")

    private fun at(time: String): Instant = day.atTime(LocalTime.parse(time)).atZone(zone).toInstant()
    private fun session(time: String) = Moment.AppSession(MomentId(time), at(time), at(time).plusSeconds(60), app, time)

    private fun headings(sections: List<DaySection>) = sections.map { section ->
        when (section) {
            is DaySection.Place -> section.visit.label
            is DaySection.Loose -> section.label
        } + " " + section.moments.joinToString(",") { it.id.value }
    }

    @Test
    fun `a day without visits is split into morning, afternoon and evening`() {
        val sections = arrange(Timeline(listOf(session("20:00"), session("09:00"), session("09:30"), session("14:00")), emptyList()), zone)
        assertEquals(listOf("Morning 09:00,09:30", "Afternoon 14:00", "Evening 20:00"), headings(sections))
    }

    @Test
    fun `moments go under the last place they were at or had left, and the ones before the first place under the part of the day`() {
        val visits = listOf(Visit(at("07:40"), at("09:25"), "Home"), Visit(at("10:05"), at("15:10"), "Office"), Visit(at("19:40"), null, "Home"))
        val moments = listOf(session("06:52"), session("08:05"), session("09:25"), session("09:41"), session("11:42"), session("16:52"), session("21:12"))
        assertEquals(
            listOf("Morning 06:52", "Home 08:05,09:25,09:41", "Office 11:42,16:52", "Home 21:12"),
            headings(arrange(Timeline(moments, visits), zone)),
        )
    }

    @Test
    fun `a visit with nothing in it still shows`() {
        val sections = arrange(Timeline(emptyList(), listOf(Visit(at("06:50"), at("07:50"), "Cubbon Park"))), zone)
        assertEquals(listOf("Cubbon Park "), headings(sections))
    }

    @Test
    fun `a stay names am or pm once when both ends share it`() {
        val format = Format(zone, day, use24Hour = false, locale = Locale.UK)
        assertEquals("7:40 to 9:25 am", format.span(at("07:40"), at("09:25")))
        assertEquals("10:05 am to 3:10 pm", format.span(at("10:05"), at("15:10")))
        assertEquals("from 7:40 pm", format.span(at("19:40"), null))
        assertEquals("07:40 to 21:25", Format(zone, day, use24Hour = true).span(at("07:40"), at("21:25")))
    }
}
