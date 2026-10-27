package ai.tushar.handset.ui.days

import ai.tushar.handset.context.AgentActionRow
import ai.tushar.handset.context.ContextStore
import ai.tushar.handset.context.ContextStore.TimelineItem
import ai.tushar.handset.context.EventSendRow
import ai.tushar.handset.context.FrameRow
import ai.tushar.handset.events.Payload
import ai.tushar.handset.events.PhoneEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The day record as the screens read it, from the [ContextStore]. A moment's id holds the app, conversation or agent it
 * belongs to and its time range, so opening it reads that range again rather than keeping anything in memory.
 * [launchable] tells apps the user opens from those that only pass by, as a permission prompt.
 */
class StoreDayRepository(
    private val store: ContextStore,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val launchable: (String) -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) : DayRepository {
    override suspend fun firstDay(): LocalDate? = store.firstAt()?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    override suspend fun timeline(date: LocalDate): Timeline {
        val items = store.dayTimeline(date, zone)
        val frames = store.frames(date, zone)
        val moments = sessions(items.filterIsInstance<TimelineItem.Session>(), frames) +
            messages(items.filterIsInstance<TimelineItem.Notification>()) +
            runs(items.filterIsInstance<TimelineItem.AgentAction>().map { AgentActionRow(at = it.at, agent = it.agent, tool = it.tool, summary = it.summary, outcome = it.outcome, agentId = it.agentId) }) +
            store.eventSends(date, zone).mapNotNull(::eventMoment)
        val visits = items.filterIsInstance<TimelineItem.Visit>().map { visit ->
            // A visit that is still being extended is where the phone is now
            val now = clock()
            val going = date == Instant.ofEpochMilli(now).atZone(zone).toLocalDate() && now - visit.end < STILL_THERE_MS
            Visit(Instant.ofEpochMilli(visit.at), if (going) null else Instant.ofEpochMilli(visit.end), visit.label ?: UNNAMED_PLACE, named = visit.label != null, id = visit.visitId)
        }
        return Timeline(moments, visits)
    }

    override suspend fun detail(id: MomentId): MomentDetail? {
        val key = Key.parse(id) ?: return null
        return when (key.kind) {
            Kind.SESSION -> {
                // The day's own moment, for the app's name when nothing was read in it
                val day = Instant.ofEpochMilli(key.from).atZone(zone).toLocalDate()
                val moment = timeline(day).moments.firstOrNull { it.id == id } as? Moment.AppSession ?: return null
                MomentDetail.ScreenText(moment, blocks(store.framesOf(key.name, key.from, key.to)))
            }
            Kind.MESSAGES -> {
                val rows = store.conversation(key.name, key.from, key.to)
                val moment = messages(rows.map { TimelineItem.Notification(it.at, it.packageName, it.app, it.sender, it.text, it.conversation) }).firstOrNull() ?: return null
                // The whole day's conversation up to this moment, so the messages before it give it context
                val (dayStart, _) = ContextStore.dayRange(Instant.ofEpochMilli(key.from).atZone(zone).toLocalDate(), zone)
                val lines = store.conversation(key.name, dayStart, key.to).map { ChatLine(Instant.ofEpochMilli(it.at), it.sender, it.text, fromYou = it.sender == YOU) }
                MomentDetail.Conversation(moment, lines)
            }
            Kind.AGENT -> {
                val actions = store.agentActions(key.name, key.name, key.from, key.to)
                val moment = runs(actions).firstOrNull() ?: return null
                MomentDetail.AgentSteps(moment, actions.map { AgentStep(Instant.ofEpochMilli(it.at), step(it)) })
            }
            Kind.EVENT -> {
                val row = key.name.toLongOrNull()?.let { store.eventSend(it) } ?: return null
                val event = PhoneEvent.of(row) ?: return null
                MomentDetail.EventFields(eventMoment(row) ?: return null, event.sentence, Payload.readable(event, zone))
            }
        }
    }

    override suspend fun search(query: String): List<SearchHit> {
        val days = HashMap<LocalDate, List<Moment>>()
        return store.search(query, since = 0, limit = SEARCH_LIMIT).mapNotNull { hit ->
            val date = Instant.ofEpochMilli(hit.at).atZone(zone).toLocalDate()
            val moments = days.getOrPut(date) { timeline(date).moments }
            val kind = if (hit.kind == "notification") Kind.MESSAGES else Kind.SESSION
            // The moment the hit happened in: the app session it was read in, or the run of messages it came in
            val moment = moments.firstOrNull { moment ->
                val key = Key.parse(moment.id)
                val app = when (moment) {
                    is Moment.AppSession -> moment.app.packageName
                    is Moment.Message -> moment.app.packageName
                    is Moment.AgentRun, is Moment.EventSent -> null
                }
                key != null && key.kind == kind && app == hit.packageName && hit.at in key.from..key.to
            } ?: return@mapNotNull null
            val (snippet, matches) = marked(hit.snippet)
            SearchHit(moment, snippet, matches)
        }.distinctBy { it.moment.id }
    }

    override suspend fun agentRuns(agentId: String, name: String, limit: Int): List<Moment.AgentRun> =
        runs(store.latestAgentActions(agentId, name, RECENT_STEPS).reversed()).sortedByDescending { it.at }.take(limit)

    override suspend fun namePlace(visitId: Long, label: String) {
        store.nameVisit(visitId, label)
    }

    override suspend fun forgetPlace(visitId: Long) = store.forgetPlace(visitId)

    // Building moments

    /** App sessions from Android's usage record, and runs of screens read in one app outside any of them */
    private fun sessions(sessions: List<TimelineItem.Session>, frames: List<FrameRow>): List<Moment.AppSession> {
        val framed = sessions.map { session ->
            // A screen is read once it settles, so its frame can land just after the session ends
            val inside = frames.filter { it.packageName == session.packageName && it.at in session.at..session.end + SETTLE_MS }
            Triple(session, inside, Key(Kind.SESSION, session.at, session.end + SETTLE_MS, session.packageName))
        }
        val covered = framed.flatMap { it.second }.toSet()
        // Short visits to an app with nothing read are noise, as passing through it on the way to another, and so are system screens
        val fromUsage = framed.filter { (session, inside) -> inside.isNotEmpty() || session.end - session.at >= MIN_SESSION_MS && launchable(session.packageName) }
            .map { (session, inside, key) -> sessionMoment(key, inside, session.app) }
        val loose = ArrayList<List<FrameRow>>()
        for (frame in frames.filter { it !in covered }) {
            val last = loose.lastOrNull()?.last()
            if (last != null && last.packageName == frame.packageName && frame.at - last.at < FRAME_GAP_MS) {
                loose[loose.lastIndex] = loose.last() + frame
            } else {
                loose += listOf(frame)
            }
        }
        return fromUsage + loose.map { run -> sessionMoment(Key(Kind.SESSION, run.first().at, run.last().at, run.first().packageName), run) }
    }

    private fun sessionMoment(key: Key, frames: List<FrameRow>, app: String? = null): Moment.AppSession {
        val label = app ?: frames.firstOrNull()?.app ?: key.name
        val line = frames.firstNotNullOfOrNull { frame -> frame.text.lineSequence().map { it.trim() }.firstOrNull { it.length >= MIN_LINE && it != label } }
            ?: if (frames.isEmpty()) "No screen text kept" else frames.first().text.lineSequence().first().trim()
        return Moment.AppSession(key.id, Instant.ofEpochMilli(key.from), Instant.ofEpochMilli(key.to), AppRef(key.name, label), line)
    }

    /** A conversation's messages that came close together are one moment, shown by its latest message */
    private fun messages(notifications: List<TimelineItem.Notification>): List<Moment.Message> =
        notifications.groupBy { it.conversation }.flatMap { (conversation, rows) ->
            bursts(rows.sortedBy { it.at }, MESSAGE_GAP_MS) { it.at }.map { burst ->
                val latest = burst.last()
                val senders = burst.map { it.sender }.filter { it != YOU }.distinct()
                Moment.Message(
                    Key(Kind.MESSAGES, burst.first().at, latest.at, conversation).id,
                    Instant.ofEpochMilli(latest.at),
                    AppRef(latest.packageName, latest.app),
                    if (senders.size > 1) senders.take(MAX_SENDERS).joinToString(", ") else senders.firstOrNull() ?: latest.sender,
                    latest.text,
                    group = senders.size > 1,
                )
            }
        }

    /**
     * An agent's steps that came close together are one run, named by what it did. Agents are told apart by id, or by
     * name for steps kept without one, which the run's id holds.
     */
    private fun runs(actions: List<AgentActionRow>): List<Moment.AgentRun> =
        actions.groupBy { it.agentId ?: it.agent }.flatMap { (who, rows) ->
            bursts(rows.sortedBy { it.at }, RUN_GAP_MS) { it.at }.map { burst ->
                val did = burst.map { it.summary }.distinct()
                val shown = did.filter { it !in READING }.ifEmpty { did }
                val line = shown.take(MAX_STEPS_IN_LINE).joinToString(", ") + if (shown.size > MAX_STEPS_IN_LINE) ", and more" else ""
                Moment.AgentRun(Key(Kind.AGENT, burst.first().at, burst.last().at, who).id, Instant.ofEpochMilli(burst.first().at), burst.last().agent, line)
            }
        }

    private fun eventMoment(row: EventSendRow): Moment.EventSent? {
        val event = PhoneEvent.of(row) ?: return null
        return Moment.EventSent(Key(Kind.EVENT, row.at, row.at, row.id.toString()).id, Instant.ofEpochMilli(row.at), event.title, PhoneEvent.outcome(row))
    }

    private fun step(action: AgentActionRow): String = if (action.outcome == "ok") action.summary else "${action.summary}. ${action.outcome}"

    private fun <T> bursts(sorted: List<T>, gapMs: Long, at: (T) -> Long): List<List<T>> {
        val out = ArrayList<MutableList<T>>()
        for (item in sorted) {
            val last = out.lastOrNull()
            if (last != null && at(item) - at(last.last()) < gapMs) last += item else out += mutableListOf(item)
        }
        return out
    }

    /**
     * The text of a session's screens, each line once. A screen that shares no line with the one before it starts a
     * new part, marked with its time.
     */
    private fun blocks(frames: List<FrameRow>): List<ScreenBlock> {
        val seen = HashSet<String>()
        val blocks = ArrayList<ScreenBlock>()
        var previous: Set<String> = emptySet()
        for (frame in frames) {
            val lines = frame.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val new = lines.filter { seen.add(it) }
            if (new.isEmpty()) continue
            if (blocks.isNotEmpty() && lines.none { it in previous }) blocks += ScreenBlock.NewScreen(Instant.ofEpochMilli(frame.at))
            blocks += ScreenBlock.Text(new.joinToString("\n"))
            previous = lines.toSet()
        }
        return blocks
    }

    private enum class Kind(val code: String) { SESSION("s"), MESSAGES("n"), AGENT("a"), EVENT("e") }

    /** What a moment's id holds: its kind, its range, and the app, conversation, agent or event send, which may hold any character */
    private data class Key(val kind: Kind, val from: Long, val to: Long, val name: String) {
        val id: MomentId get() = MomentId("${kind.code}|$from|$to|$name")

        companion object {
            fun parse(id: MomentId): Key? {
                val parts = id.value.split('|', limit = 4)
                if (parts.size != 4) return null
                val kind = Kind.entries.firstOrNull { it.code == parts[0] } ?: return null
                return Key(kind, parts[1].toLongOrNull() ?: return null, parts[2].toLongOrNull() ?: return null, parts[3])
            }
        }
    }

    companion object {
        /** The name a messaging notification gives the phone's owner */
        const val YOU = "Me"
        const val UNNAMED_PLACE = "A place you stayed"
        private const val SETTLE_MS = 5_000L
        private const val STILL_THERE_MS = 20 * 60_000L
        private const val MIN_SESSION_MS = 60_000L
        private const val FRAME_GAP_MS = 10 * 60_000L
        private const val MESSAGE_GAP_MS = 30 * 60_000L
        private const val RUN_GAP_MS = 5 * 60_000L
        private const val MIN_LINE = 12
        private const val MAX_SENDERS = 3
        private const val MAX_STEPS_IN_LINE = 3
        private const val SEARCH_LIMIT = 40
        private const val RECENT_STEPS = 200
        private val READING = setOf("Reading the screen", "Looking at the screen", "Waiting for the screen")

        /** The snippet without the store's [brackets], and where the bracketed words were */
        fun marked(snippet: String): Pair<String, List<IntRange>> {
            val out = StringBuilder()
            val matches = ArrayList<IntRange>()
            var start = -1
            for (char in snippet) {
                when {
                    char == '[' && start < 0 -> start = out.length
                    char == ']' && start >= 0 -> {
                        if (out.length > start) matches += start until out.length
                        start = -1
                    }
                    else -> out.append(char)
                }
            }
            return out.toString() to matches
        }
    }
}
