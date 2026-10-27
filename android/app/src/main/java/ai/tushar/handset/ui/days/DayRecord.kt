package ai.tushar.handset.ui.days

import java.time.Instant
import java.time.LocalDate

/** An app on the phone: its package, to open it and pick its glyph, and the name it shows */
data class AppRef(val packageName: String, val label: String)

@JvmInline
value class MomentId(val value: String)

/** One row of a day: something you did, someone who wrote to you, or something an agent did */
sealed interface Moment {
    val id: MomentId
    val at: Instant

    /** Time in one app, with one line of what was on screen */
    data class AppSession(override val id: MomentId, override val at: Instant, val end: Instant, val app: AppRef, val line: String) : Moment

    /** Notifications from one conversation that came close together. [group] when several people wrote, with [sender] naming them. */
    data class Message(override val id: MomentId, override val at: Instant, val app: AppRef, val sender: String, val text: String, val group: Boolean = false) : Moment

    /** One request an agent carried out, as the steps it took in a row */
    data class AgentRun(override val id: MomentId, override val at: Instant, val agent: String, val line: String) : Moment

    /** A phone event sent to the user's routine, or one that couldn't be: what happened, and how the send went */
    data class EventSent(override val id: MomentId, override val at: Instant, val title: String, val line: String) : Moment
}

/**
 * Where the phone stayed for a while. [end] is null while you are still there. [named] is false while the place has no
 * name, and [label] then says so. [id] names or renames the place through [DayRepository.namePlace], or is null when it
 * can't be named.
 */
data class Visit(val start: Instant, val end: Instant?, val label: String, val named: Boolean = true, val id: Long? = null)

/** Everything recorded on one day, in any order */
data class Timeline(val moments: List<Moment>, val visits: List<Visit>)

/** A moment opened: what it holds beyond its row */
sealed interface MomentDetail {
    val moment: Moment

    /** The text you read in an app session */
    data class ScreenText(override val moment: Moment.AppSession, val blocks: List<ScreenBlock>) : MomentDetail

    /** The conversation's messages that day, up to this moment */
    data class Conversation(override val moment: Moment.Message, val messages: List<ChatLine>) : MomentDetail

    /** Each step the agent took */
    data class AgentSteps(override val moment: Moment.AgentRun, val steps: List<AgentStep>) : MomentDetail

    /** The fields the event carried, as the address received them */
    data class EventFields(override val moment: Moment.EventSent, val sentence: String, val payload: String) : MomentDetail
}

sealed interface ScreenBlock {
    data class Text(val text: String) : ScreenBlock

    /** A screen that shares nothing with the one before it, as a new page, from [at] */
    data class NewScreen(val at: Instant) : ScreenBlock
}

data class ChatLine(val at: Instant, val sender: String, val text: String, val fromYou: Boolean)

data class AgentStep(val at: Instant, val text: String)

/** A stored screen or notification that holds the words searched for. [matches] are the ranges of [snippet] to bold. */
data class SearchHit(val moment: Moment, val snippet: String, val matches: List<IntRange>)

/**
 * The day record as the screens read it. The store answers these from its day timeline, its full-text search and its
 * sessions' screen text in [StoreDayRepository]; previews, and debug builds when asked, use [FakeDayRepository].
 */
interface DayRepository {
    /** The first day anything was recorded, or null before the first. Days before it can't be opened. */
    suspend fun firstDay(): LocalDate?

    suspend fun timeline(date: LocalDate): Timeline

    suspend fun detail(id: MomentId): MomentDetail?

    /** Hits for every word of [query], best first */
    suspend fun search(query: String): List<SearchHit>

    /** The latest runs of steps of the agent with [agentId], newest first. Steps kept before ids were are matched by [name]. */
    suspend fun agentRuns(agentId: String, name: String, limit: Int): List<Moment.AgentRun>

    /** Names or renames the place of visit [visitId]. Later visits within 150 m of it take the name. */
    suspend fun namePlace(visitId: Long, label: String)

    /** Forgets the name of the place of visit [visitId], for its visits and later ones */
    suspend fun forgetPlace(visitId: Long)
}
