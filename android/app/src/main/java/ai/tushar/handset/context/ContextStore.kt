package ai.tushar.handset.context

import androidx.room3.RoomRawQuery
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the phone remembers, on the phone: the writes the collectors make, and every query the day browser, the tools and
 * the MCP server read. Nothing here leaves the phone on its own.
 */
class ContextStore(val database: ContextDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = database.dao()

    // Writes

    suspend fun addFrame(frame: FrameRow) {
        dao.insertFrame(frame)
    }

    /** Returns whether the notification was new or changed */
    suspend fun putNotification(row: NotificationRow): Boolean = dao.putNotification(row)

    suspend fun putSessions(rows: List<SessionRow>) = dao.putSessions(rows)

    suspend fun addAgentAction(row: AgentActionRow) {
        dao.insertAgentAction(row)
    }

    /** Keeps a phone event to send, once per occurrence. See [ContextDao.claimEventSend]. */
    suspend fun claimEventSend(row: EventSendRow): EventSendRow? = dao.claimEventSend(row)

    /** Takes one of the [cap] POSTs an hour allows: null when taken, else when one frees. See [ContextDao.reserveEventPost]. */
    suspend fun reserveEventPost(cap: Int): Long? = dao.reserveEventPost(clock(), cap, HOUR_MS)

    suspend fun eventSend(id: Long): EventSendRow? = dao.eventSend(id)

    /** The phone events still to send */
    suspend fun unsentEventSends(): List<EventSendRow> = dao.unsentEventSends()

    suspend fun latestEventSend(): EventSendRow? = dao.latestEventSend()

    suspend fun updateEventSend(id: Long, status: SendStatus, detail: String, attempts: Int) = dao.updateEventSend(id, status.name, detail, attempts)

    /** The phone events of [date], oldest first. They stay out of [dayTimeline], which agents read. */
    suspend fun eventSends(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<EventSendRow> = dayRange(date, zone).let { (from, to) -> dao.eventSends(from, to) }

    /** The people and apps notifications came from since [since], the most frequent first */
    suspend fun notificationSenders(since: Long, limit: Int): List<NotificationSender> = dao.notificationSenders(since, limit)

    /** The name of the place [visitId] was at, or null while it is unnamed */
    suspend fun visitLabel(visitId: Long): String? = dao.visitLabel(visitId)

    /** Adds a visit, named when it is within a named place's radius. Returns its id. */
    suspend fun addVisit(start: Long, end: Long, latitude: Double, longitude: Double): Long =
        dao.insertVisit(VisitRow(start = start, end = end, latitude = latitude, longitude = longitude, placeId = placeAt(latitude, longitude)?.id))

    /** Moves the end of a visit that is still going on, and its centre as samples come in */
    suspend fun extendVisit(id: Long, end: Long, latitude: Double, longitude: Double) = dao.updateVisit(id, end, latitude, longitude)

    /**
     * Names the place of [visitId]. Every unnamed visit within [radiusM] of it, earlier or later, takes the name too.
     * A place that already has a name is renamed, with every visit to it. Returns the place's id, or null when the visit
     * is gone.
     */
    suspend fun nameVisit(visitId: Long, label: String, radiusM: Double = Places.VISIT_RADIUS_M): Long? {
        val visit = dao.visit(visitId) ?: return null
        visit.placeId?.let { placeId ->
            dao.renamePlace(placeId, label.trim())
            return placeId
        }
        val placeId = dao.insertPlace(PlaceRow(label = label.trim(), latitude = visit.latitude, longitude = visit.longitude, radiusM = radiusM))
        val near = dao.unnamedVisits().filter { Places.distanceM(it.latitude, it.longitude, visit.latitude, visit.longitude) <= radiusM }
        if (near.isNotEmpty()) dao.setVisitPlace(near.map { it.id }, placeId)
        return placeId
    }

    /** Forgets the place of [visitId]: its visits become unnamed, and later visits there stay unnamed */
    suspend fun forgetPlace(visitId: Long) {
        dao.visit(visitId)?.placeId?.let { dao.forgetPlace(it) }
    }

    private suspend fun placeAt(latitude: Double, longitude: Double): PlaceRow? = dao.places()
        .map { it to Places.distanceM(it.latitude, it.longitude, latitude, longitude) }
        .filter { (place, distance) -> distance <= place.radiusM }
        .minByOrNull { it.second }?.first

    // Queries

    /** One thing in a day's timeline, by its start time */
    sealed interface TimelineItem {
        val at: Long

        data class Session(override val at: Long, val end: Long, val packageName: String, val app: String) : TimelineItem
        data class Visit(override val at: Long, val end: Long, val visitId: Long, val label: String?, val latitude: Double, val longitude: Double) : TimelineItem
        data class Notification(override val at: Long, val packageName: String, val app: String, val sender: String, val text: String, val conversation: String) : TimelineItem
        data class AgentAction(override val at: Long, val agent: String, val tool: String, val summary: String, val outcome: String, val agentId: String? = null) : TimelineItem
    }

    /** Sessions, visits, notifications and agent actions of [date], in time order. Items that cross midnight count on both days. */
    suspend fun dayTimeline(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<TimelineItem> {
        val (from, to) = dayRange(date, zone)
        val items = ArrayList<TimelineItem>()
        dao.sessions(from, to).mapTo(items) { TimelineItem.Session(it.start, it.end, it.packageName, it.app) }
        dao.visits(from, to).mapTo(items) { TimelineItem.Visit(it.start, it.end, it.id, it.label, it.latitude, it.longitude) }
        dao.notifications(from, to).mapTo(items) { TimelineItem.Notification(it.at, it.packageName, it.app, it.sender, it.text, it.conversation) }
        dao.agentActions(from, to).mapTo(items) { TimelineItem.AgentAction(it.at, it.agent, it.tool, it.summary, it.outcome, it.agentId) }
        return items.sortedBy { it.at }
    }

    /** A screen or a notification that matched a search. [kind] is `screen` or `notification`. */
    data class SearchHit(val kind: String, val at: Long, val packageName: String, val app: String, val sender: String?, val snippet: String)

    /**
     * Screens and notifications since [since] that hold every word of [query], words matched by their start, best match
     * first. The same snippet in the same app, as a chat shows on every screen, is listed once, at its latest time.
     * [notifications] false searches screens alone.
     */
    suspend fun search(query: String, since: Long, limit: Int = 20, notifications: Boolean = true): List<SearchHit> {
        val match = matchExpression(query) ?: return emptyList()
        val sql = """
            SELECT * FROM (
                SELECT 'screen' AS kind, f.id, f.at, f.packageName, f.app, NULL AS sender, snippet(frames_fts, 0, '[', ']', '…', $SNIPPET_TOKENS) AS snippet,
                    bm25(frames_fts) AS rank
                FROM frames_fts JOIN frames f ON f.id = frames_fts.rowid WHERE frames_fts MATCH ? AND f.at >= ?
                UNION ALL
                SELECT 'notification' AS kind, n.id, n.at, n.packageName, n.app, n.sender, snippet(notifications_fts, 1, '[', ']', '…', $SNIPPET_TOKENS) AS snippet,
                    bm25(notifications_fts) AS rank
                FROM notifications_fts JOIN notifications n ON n.id = notifications_fts.rowid WHERE notifications_fts MATCH ? AND n.at >= ?
            ) ORDER BY rank, at DESC LIMIT ?
        """.trimIndent()
        val rows = dao.searchHits(
            RoomRawQuery(sql) { statement ->
                statement.bindText(1, match)
                statement.bindLong(2, since)
                statement.bindText(3, match)
                statement.bindLong(4, if (notifications) since else Long.MAX_VALUE)
                statement.bindLong(5, (limit * OVERFETCH).toLong())
            },
        )
        return rows
            .groupBy { Triple(it.kind, it.app, it.snippet) }
            .map { (_, same) -> same.maxBy { it.at } }
            .take(limit)
            .map { SearchHit(it.kind, it.at, it.packageName, it.app, it.sender, it.snippet) }
    }

    /** The text of the screens read in one app between [from] and [to], both included, oldest first */
    suspend fun framesOf(packageName: String, from: Long, to: Long): List<FrameRow> = dao.framesOf(packageName, from, to)

    /** Every screen read on [date], oldest first */
    suspend fun frames(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<FrameRow> = dayRange(date, zone).let { (from, to) -> dao.frames(from, to) }

    /** The messages of one conversation between [from] and [to], both included, oldest first */
    suspend fun conversation(conversation: String, from: Long, to: Long): List<NotificationRow> = dao.conversation(conversation, from, to)

    /**
     * One agent's steps between [from] and [to], both included, oldest first: those with [agentId], and those kept with no
     * id under its [name], as adb's and the ones from before ids were kept.
     */
    suspend fun agentActions(agentId: String, name: String, from: Long, to: Long): List<AgentActionRow> = dao.agentActionsOf(agentId, name, from, to)

    /** One agent's newest steps, newest first, matched as in [agentActions] */
    suspend fun latestAgentActions(agentId: String, name: String, limit: Int): List<AgentActionRow> = dao.latestAgentActions(agentId, name, limit)

    /** When the oldest thing kept was recorded, or null when nothing is */
    suspend fun firstAt(): Long? = dao.firstAt()

    /** The notifications from one person or thread, oldest first */
    data class InboxGroup(val app: String, val sender: String, val conversation: String, val messages: List<InboxMessage>) {
        val latest: Long get() = messages.maxOf { it.at }
    }

    data class InboxMessage(val at: Long, val sender: String, val text: String)

    /**
     * Every notification that came in since [since], grouped by conversation, the group with the newest message first.
     * Screen text isn't included.
     */
    suspend fun inboxSince(since: Long): List<InboxGroup> = dao.notifications(since, Long.MAX_VALUE)
        .groupBy { it.conversation }
        .map { (conversation, rows) ->
            // A group chat's conversation holds many senders; it is named by the notification's title
            val senders = rows.map { it.sender }.distinct()
            InboxGroup(rows.first().app, senders.singleOrNull() ?: senders.joinToString(", "), conversation, rows.map { InboxMessage(it.at, it.sender, it.text) })
        }
        .sortedByDescending { it.latest }

    // Retention

    /** Drops screen text and notifications after [rawDays] and everything else, phone events included, after a year. Search entries go with their rows. */
    suspend fun clean(rawDays: Int = RAW_DAYS) {
        val now = clock()
        dao.deleteFramesBefore(now - rawDays * DAY_MS)
        dao.deleteNotificationsBefore(now - rawDays * DAY_MS)
        val yearAgo = now - KEPT_DAYS * DAY_MS
        dao.deleteSessionsBefore(yearAgo)
        dao.deleteVisitsBefore(yearAgo)
        dao.deleteAgentActionsBefore(yearAgo)
        dao.deleteEventSendsBefore(yearAgo)
        // Nothing older than a day is read back, and a chat's notification goes once its text would have
        dao.deleteDeletionsBefore(now - rawDays * DAY_MS)
        dao.deleteDeletedNotificationsBefore(now - rawDays * DAY_MS)
    }

    /**
     * Deletes everything recorded since midnight and its search entries. Named places stay. The span and the
     * notifications it held are kept, so app use read back and a chat's notification posted again don't bring them back.
     */
    suspend fun deleteToday(zone: ZoneId = ZoneId.systemDefault()) {
        val now = clock()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val (from, _) = dayRange(today, zone)
        dao.insertDeletion(DeletionRow(start = from, end = now))
        dao.keepDeletedNotifications(from, now)
        dao.deleteFramesSince(from)
        dao.deleteNotificationsSince(from)
        dao.deleteSessionsSince(from)
        dao.deleteVisitsSince(from)
        dao.deleteAgentActionsSince(from)
        dao.deleteEventSendsSince(from)
    }

    /** The end of the latest span Delete today emptied, or 0. Collectors don't read back before it. */
    suspend fun deletedThrough(): Long = dao.deletedThrough() ?: 0L

    /** Rows in each table, for checking the store */
    suspend fun counts(): Map<String, Long> = dao.counts().associate { it.name to it.count }

    companion object {
        const val DAY_MS = 24 * 60 * 60_000L
        const val RAW_DAYS = 30
        const val KEPT_DAYS = 365L
        private const val HOUR_MS = 60 * 60_000L
        private const val SNIPPET_TOKENS = 12
        private const val OVERFETCH = 4

        fun dayRange(date: LocalDate, zone: ZoneId): Pair<Long, Long> =
            date.atStartOfDay(zone).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        /** Each word of [query] as a quoted prefix term, all required, so punctuation in the query can't break FTS5's syntax */
        fun matchExpression(query: String): String? = Regex("[\\p{L}\\p{N}]+").findAll(query).map { "\"${it.value}\"*" }.toList()
            .takeIf { it.isNotEmpty() }?.joinToString(" ")
    }
}
