package ai.tushar.handset.ui.days

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A week of made-up days, ending today, for the debug build and previews until the store is wired in */
class FakeDayRepository(private val today: LocalDate = LocalDate.now(), private val zone: ZoneId = ZoneId.systemDefault()) : DayRepository {
    private val days: Map<LocalDate, Timeline>
    private val details = HashMap<MomentId, MomentDetail>()

    override suspend fun firstDay(): LocalDate = today.minusDays(RECORDED_DAYS)

    override suspend fun timeline(date: LocalDate): Timeline = days[date] ?: Timeline(emptyList(), emptyList())

    override suspend fun detail(id: MomentId): MomentDetail? = details[id] ?: moment(id)?.let(::plainDetail)

    override suspend fun search(query: String): List<SearchHit> {
        val words = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return days.values.flatMap { it.moments }.sortedByDescending { it.at }.mapNotNull { moment ->
            val text = searchText(moment).firstOrNull { line -> words.all { line.contains(it, ignoreCase = true) } } ?: return@mapNotNull null
            SearchHit(moment, text, words.flatMap { word -> Regex(Regex.escape(word), RegexOption.IGNORE_CASE).findAll(text).map { it.range }.toList() })
        }
    }

    override suspend fun agentRuns(agentId: String, name: String, limit: Int): List<Moment.AgentRun> =
        days.values.flatMap { it.moments }.filterIsInstance<Moment.AgentRun>().filter { it.agent == name }.sortedByDescending { it.at }.take(limit)

    // The made-up places have no ids, so they can't be named
    override suspend fun namePlace(visitId: Long, label: String) = Unit

    override suspend fun forgetPlace(visitId: Long) = Unit

    private fun moment(id: MomentId) = days.values.firstNotNullOfOrNull { day -> day.moments.firstOrNull { it.id == id } }

    private fun searchText(moment: Moment): List<String> {
        val detail = details[moment.id]
        val more = when (detail) {
            is MomentDetail.ScreenText -> detail.blocks.filterIsInstance<ScreenBlock.Text>().map { it.text }
            is MomentDetail.Conversation -> detail.messages.map { it.text }
            is MomentDetail.AgentSteps -> detail.steps.map { it.text }
            is MomentDetail.EventFields, null -> emptyList()
        }
        val line = when (moment) {
            is Moment.AppSession -> moment.line
            is Moment.Message -> moment.text
            is Moment.AgentRun -> moment.line
            is Moment.EventSent -> moment.line
        }
        return listOf(line) + more
    }

    private fun plainDetail(moment: Moment): MomentDetail = when (moment) {
        is Moment.AppSession -> MomentDetail.ScreenText(moment, listOf(ScreenBlock.Text(moment.line)))
        is Moment.Message -> MomentDetail.Conversation(moment, listOf(ChatLine(moment.at, moment.sender, moment.text, fromYou = false)))
        is Moment.AgentRun -> MomentDetail.AgentSteps(moment, listOf(AgentStep(moment.at, moment.line)))
        is Moment.EventSent -> MomentDetail.EventFields(moment, moment.title, "{\"type\":\"test\"}")
    }

    // Building the days

    private inner class DayBuilder(val date: LocalDate) {
        val moments = ArrayList<Moment>()
        val visits = ArrayList<Visit>()
        private var n = 0

        fun at(time: String): Instant = date.atTime(LocalTime.parse(time)).atZone(zone).toInstant()

        fun visit(label: String, start: String, end: String?) {
            visits += Visit(at(start), end?.let(::at), label)
        }

        fun session(app: AppRef, start: String, minutes: Long, line: String, detail: ((Moment.AppSession) -> MomentDetail)? = null) {
            val moment = Moment.AppSession(nextId(), at(start), at(start).plusSeconds(minutes * 60), app, line)
            moments += moment
            detail?.let { details[moment.id] = it(moment) }
        }

        fun message(app: AppRef, time: String, sender: String, text: String, group: Boolean = false, detail: ((Moment.Message) -> MomentDetail)? = null) {
            val moment = Moment.Message(nextId(), at(time), app, sender, text, group)
            moments += moment
            detail?.let { details[moment.id] = it(moment) }
        }

        fun agent(name: String, time: String, line: String, detail: ((Moment.AgentRun) -> MomentDetail)? = null) {
            val moment = Moment.AgentRun(nextId(), at(time), name, line)
            moments += moment
            detail?.let { details[moment.id] = it(moment) }
        }

        private fun nextId() = MomentId("$date-${n++}")
    }

    private fun day(daysAgo: Long, build: DayBuilder.() -> Unit): Pair<LocalDate, Timeline> {
        val builder = DayBuilder(today.minusDays(daysAgo)).apply(build)
        return builder.date to Timeline(builder.moments, builder.visits)
    }

    init {
        days = mapOf(
            day(0) {
                visit("Home", "07:40", "09:25")
                visit("Office", "10:05", "15:10")
                visit("Third Wave Coffee", "15:20", "16:40")
                visit("Home", "19:40", null)
                message(WHATSAPP, "06:52", "Amma", "Did you eat breakfast? Papa is asking about Diwali tickets")
                session(SPOTIFY, "08:05", 35, "Lo-fi Beats while you got ready")
                session(GMAIL, "08:42", 13, "Surge cohort 12: demo day moves to 19 November") { screen(it, "Surge cohort 12: demo day moves to 19 November\nSurge team\nDemo day moves to 19 November. Rehearsals start the week before.\nOffice hours with Rajan are open on Thursdays from 4 pm. Book through the portal.") }
                session(UBER, "09:25", 34, "Ride to Indiranagar, 34 min, ₹212")
                agent("Claude Code", "09:41", "Opening Slack, Tapping “#release-0-9”, Scrolling up") { steps(it, "09:41" to "Opening Slack", "09:41" to "Tapping “#release-0-9”", "09:42" to "Scrolling up", "09:42" to "Reading the screen", "09:43" to "Looking at the screen") }
                message(SLACK, "10:30", "Rahul", "Onboarding isn’t ready. I’d rather ship 0.9 on Thursday without it.") { rahulThread(it) }
                session(SLACK, "11:42", 18, "#release-0-9") { screen(it, "#release-0-9\nRahul\nOnboarding isn’t ready. I’d rather ship 0.9 on Thursday without it.\nYou\nAgreed. Onboarding goes in 0.10. Can you get CI green tonight?\nRahul\nYes. Two flaky tests left.") }
                message(WHATSAPP, "12:41", "Priya, Arjun", "In, I’ll book a table", group = true) { conversation(it, "12:30" to ("Priya" to "Drinks at Toit on Friday? 8ish"), "12:34" to ("Arjun" to "In"), "12:41" to (YOU to "In, I’ll book a table")) }
                session(CHROME, "13:05", 17, "How ESOPs are taxed when you exercise them") { esopArticle(it) }
                session(DOCS, "14:10", 42, "Release notes for 0.9, second draft")
                message(WHATSAPP, "15:18", "Arjun", "Here, got us the corner table")
                agent("Claude", "16:09", "Opening Calendar, Tapping “New event”, Typing in “Title”") { steps(it, "16:09" to "Reading notifications", "16:10" to "Opening Calendar", "16:10" to "Tapping “New event”", "16:10" to "Typing in “Title”", "16:11" to "Tapping “Save”. Couldn't find “Save” on the screen", "16:11" to "Tapping “Done”") }
                message(WHATSAPP, "16:52", "Arjun", "Thanks for the ESOP notes, sending them to Kavya")
                session(SWIGGY, "20:10", 9, "Chicken biryani from Meghana Foods, ₹389")
                session(WHATSAPP, "20:40", 24, "Call with Amma, 24 min")
                message(SLACK, "21:12", "Rahul", "Build is green. Tagging 0.9 in the morning.") { rahulThread(it) }
                session(KINDLE, "21:30", 40, "Rule of thumb: Opinions are worthless.") { screen(it, "Chapter 4\nRule of thumb: Opinions are worthless.\nAnything involving the future is an over-optimistic lie.") }
            },
            day(1) {
                visit("Office", "09:55", "20:50")
                visit("Home", "21:40", null)
                session(DOCS, "10:20", 130, "Surge deck notes with Meera")
                message(WHATSAPP, "13:40", "Meera", "Slide 7 still says 40 lakh, should be 55")
                session(MEET, "17:00", 45, "Practice pitch with Meera")
                agent("Claude", "23:02", "Switching Do not disturb")
                session(UBER, "21:04", 36, "Ride home to HSR Layout, ₹238")
            },
            day(2) {
                session(SPOTIFY, "09:30", 50, "Sunday Morning Jazz")
                message(WHATSAPP, "10:15", "Amma", "Are you coming home for Diwali?")
                session(KINDLE, "14:10", 95, "The measure of usefulness of an early customer conversation") { screen(it, "Chapter 1\nThe measure of usefulness of an early customer conversation is whether it gives us concrete facts about our customers’ lives and world views.") }
                session(MAPS, "18:05", 40, "Walking directions to Agara Lake")
                session(SWIGGY, "20:20", 8, "Masala dosa from Rameshwaram Cafe, ₹156")
            },
            day(3) {
                visit("Cubbon Park", "06:50", "07:50")
                visit("Home", "08:30", "12:30")
                session(STRAVA, "07:10", 38, "Morning run, 5.2 km")
                message(WHATSAPP, "11:05", "Priya", "Lunch at Toit at 1?")
                session(CHROME, "12:40", 6, "Toit Brewpub, Indiranagar: menu")
            },
            day(4) {
                visit("Office", "09:40", "19:05")
                session(GMAIL, "09:50", 20, "Three Android candidates for the panel")
                session(MEET, "11:00", 220, "Interview loop, three candidates")
                message(SLACK, "18:30", "Rahul", "Offer to Kavya approved")
            },
            day(24) {
                session(UBER, "19:48", 31, "Ride to Toit, Indiranagar, ₹186")
            },
        )
    }

    /** A session's text as the store keeps it: the screen's lines, a new screen marked with its time */
    private fun screen(moment: Moment.AppSession, vararg screens: String) = MomentDetail.ScreenText(
        moment,
        screens.flatMapIndexed { i, text ->
            val block = ScreenBlock.Text(text)
            if (i == 0) listOf(block) else listOf(ScreenBlock.NewScreen(moment.at.plusSeconds(i * SCREEN_GAP_S)), block)
        },
    )

    private fun esopArticle(moment: Moment.AppSession) = screen(
        moment,
        "How ESOPs are taxed when you exercise them\nWhen you exercise your options, the difference between the fair market value on that day and your exercise price is taxed as a perquisite, as part of your salary. Your employer deducts TDS on it.\n" +
            "When you later sell the shares, the gain over the fair market value at exercise is a capital gain. Unlisted shares held for more than 24 months count as long term.",
        "Deferring the tax\nEmployees of eligible startups can defer the tax on exercise for up to five years, or until they leave the company or sell the shares, whichever comes first.",
    )

    private fun DayBuilder.steps(moment: Moment.AgentRun, vararg steps: Pair<String, String>) =
        MomentDetail.AgentSteps(moment, steps.map { (time, text) -> AgentStep(at(time), text) })

    private fun DayBuilder.conversation(moment: Moment.Message, vararg lines: Pair<String, Pair<String, String>>) =
        MomentDetail.Conversation(moment, lines.map { (time, line) -> ChatLine(at(time), line.first, line.second, fromYou = line.first == YOU) })

    private fun DayBuilder.rahulThread(moment: Moment.Message) = conversation(
        moment,
        "10:30" to ("Rahul" to "Onboarding isn’t ready. I’d rather ship 0.9 on Thursday without it."),
        "11:42" to (YOU to "Agreed. Onboarding goes in 0.10. Can you get CI green tonight?"),
        "11:44" to ("Rahul" to "Yes. Two flaky tests left."),
        "21:12" to ("Rahul" to "Build is green. Tagging 0.9 in the morning."),
    )

    private companion object {
        const val RECORDED_DAYS = 30L
        const val SCREEN_GAP_S = 240L
        const val YOU = StoreDayRepository.YOU
        val WHATSAPP = AppRef("com.whatsapp", "WhatsApp")
        val SLACK = AppRef("com.Slack", "Slack")
        val CHROME = AppRef("com.android.chrome", "Chrome")
        val SWIGGY = AppRef("in.swiggy.android", "Swiggy")
        val UBER = AppRef("com.ubercab", "Uber")
        val GMAIL = AppRef("com.google.android.gm", "Gmail")
        val SPOTIFY = AppRef("com.spotify.music", "Spotify")
        val KINDLE = AppRef("com.amazon.kindle", "Kindle")
        val DOCS = AppRef("com.google.android.apps.docs.editors.docs", "Google Docs")
        val MEET = AppRef("com.google.android.apps.tachyon", "Google Meet")
        val MAPS = AppRef("com.google.android.apps.maps", "Maps")
        val STRAVA = AppRef("com.strava", "Strava")
    }
}
