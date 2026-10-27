package ai.tushar.handset.context

import android.content.Context
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.RoomRawQuery
import androidx.room3.Transaction
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers

// Every time is milliseconds since the epoch. Days are cut at local midnight when a query runs.

/** The text of one settled window */
@Entity(tableName = "frames", indices = [Index("at"), Index("packageName", "at")])
data class FrameRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val packageName: String,
    val app: String,
    val text: String,
)

/**
 * One notification, or one message of a messaging notification. [dedupe] is the notification's key, with the message's
 * time and sender for a message, so an update rewrites its row instead of adding one.
 */
@Entity(tableName = "notifications", indices = [Index("dedupe", unique = true), Index("at"), Index("conversation", "at")])
data class NotificationRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dedupe: String,
    val at: Long,
    val updatedAt: Long,
    val packageName: String,
    val app: String,
    /** The person for a message, else the notification's title */
    val sender: String,
    val text: String,
    /** Which chat or thread it belongs to, for grouping: the app's conversation id, or the app and the title */
    val conversation: String,
    /** Whether [sender] names a person: a message in a chat, or a notification Android ties to a conversation or a person */
    @ColumnInfo(defaultValue = "0") val fromPerson: Boolean = false,
)

/** A stretch of time one app was in front, from Android's usage events */
@Entity(tableName = "sessions", indices = [Index("packageName", "start", unique = true), Index("start")])
data class SessionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val app: String,
    val start: Long,
    val end: Long,
)

/** A place the user named. Later visits within [radiusM] of it take its name. */
@Entity(tableName = "places")
data class PlaceRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Double,
)

/** Staying in one spot for a while. [placeId] is null until the user names the place. */
@Entity(tableName = "visits", indices = [Index("start"), Index("placeId")])
data class VisitRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val start: Long,
    val end: Long,
    val latitude: Double,
    val longitude: Double,
    val placeId: Long? = null,
)

/**
 * A tool call an agent made, as the runner finished it. [outcome] is `ok`, or the failure's code and first line.
 * [agentId] is the agent's own id, which tells apart two agents with one name; it is null over adb, and for steps kept
 * before ids were.
 */
@Entity(tableName = "agent_actions", indices = [Index("at")])
data class AgentActionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val agent: String,
    val tool: String,
    val summary: String,
    val outcome: String,
    val agentId: String? = null,
)

/** A span Delete today emptied. What a collector reads back from it, as a chat's earlier messages, isn't kept again. */
@Entity(tableName = "deletions")
data class DeletionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val start: Long,
    val end: Long,
)

/** A notification Delete today removed, by its [NotificationRow.dedupe], so the same notification posted again stays out. [at] is when. */
@Entity(tableName = "deleted_notifications")
data class DeletedNotificationRow(
    @PrimaryKey val dedupe: String,
    val at: Long,
)

/**
 * One phone event Handset sent, or tried to send, to the user's routine. [occurrence] names the thing that happened, as
 * a discharge or a visit, so it is sent once however often it is seen again. Only the fields the payload carries are
 * kept, never message text.
 */
@Entity(tableName = "event_sends", indices = [Index("occurrence", unique = true), Index("at")])
data class EventSendRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurrence: String,
    val at: Long,
    val type: String,
    val battery: Int? = null,
    val place: String? = null,
    val person: String? = null,
    val app: String? = null,
    /** One of [SendStatus], by name */
    val status: String,
    /** Why it wasn't sent, or the answer's HTTP status once it was */
    val detail: String = "",
    val attempts: Int = 0,
)

/** Where a phone event's send stands. [WAITING] is held back by the hourly cap and goes once it allows. */
enum class SendStatus { PENDING, WAITING, SENT, FAILED }

/** One POST of a phone event, tries included, so the hourly cap counts what was sent */
@Entity(tableName = "event_posts", indices = [Index("at")])
data class EventPostRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val at: Long)

/** A full-text match, from either search table */
data class SearchHitRow(
    val kind: String,
    val id: Long,
    val at: Long,
    val packageName: String,
    val app: String,
    val sender: String?,
    val snippet: String,
    val rank: Double,
)

data class VisitWithLabel(
    val id: Long,
    val start: Long,
    val end: Long,
    val latitude: Double,
    val longitude: Double,
    val label: String?,
)

data class TableCount(val name: String, val count: Long)

/** Who sent notifications, and from which app, with how many were kept. [fromPerson] when any of them named a person. */
data class NotificationSender(val packageName: String, val app: String, val sender: String, val count: Int, val fromPerson: Boolean)

@Dao
interface ContextDao {
    @Insert suspend fun insertFrame(frame: FrameRow): Long

    @Query("SELECT * FROM notifications WHERE dedupe = :dedupe")
    suspend fun notificationByDedupe(dedupe: String): NotificationRow?

    @Insert suspend fun insertNotification(row: NotificationRow): Long

    @Query("UPDATE notifications SET text = :text, sender = :sender, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateNotification(id: Long, sender: String, text: String, updatedAt: Long)

    /**
     * Adds [row], or rewrites the row with its [NotificationRow.dedupe] when the text changed. A row from a span Delete
     * today emptied, or one it removed, isn't written. Returns whether anything was written.
     */
    @Transaction
    suspend fun putNotification(row: NotificationRow): Boolean {
        if (deletedAt(row.at) || wasDeleted(row.dedupe)) return false
        val old = notificationByDedupe(row.dedupe) ?: return insertNotification(row) > 0
        if (old.text == row.text && old.sender == row.sender) return false
        updateNotification(old.id, row.sender, row.text, row.updatedAt)
        return true
    }

    @Insert suspend fun insertDeletion(row: DeletionRow): Long

    @Query("SELECT EXISTS(SELECT 1 FROM deletions WHERE :at >= start AND :at < `end`)")
    suspend fun deletedAt(at: Long): Boolean

    @Query("SELECT MAX(`end`) FROM deletions")
    suspend fun deletedThrough(): Long?

    @Query("INSERT OR REPLACE INTO deleted_notifications (dedupe, at) SELECT dedupe, :now FROM notifications WHERE at >= :from")
    suspend fun keepDeletedNotifications(from: Long, now: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM deleted_notifications WHERE dedupe = :dedupe)")
    suspend fun wasDeleted(dedupe: String): Boolean

    @Query("DELETE FROM deletions WHERE `end` < :before") suspend fun deleteDeletionsBefore(before: Long): Int
    @Query("DELETE FROM deleted_notifications WHERE at < :before") suspend fun deleteDeletedNotificationsBefore(before: Long): Int

    @Query("SELECT * FROM sessions WHERE packageName = :packageName AND start = :start")
    suspend fun sessionAt(packageName: String, start: Long): SessionRow?

    @Insert suspend fun insertSession(row: SessionRow): Long

    @Query("UPDATE sessions SET `end` = :end WHERE id = :id")
    suspend fun updateSessionEnd(id: Long, end: Long)

    /** Adds a session, or moves the end of the one that started at the same time, as a session still open grows */
    @Transaction
    suspend fun putSessions(rows: List<SessionRow>) {
        rows.forEach { row ->
            if (deletedAt(row.start)) return@forEach
            val old = sessionAt(row.packageName, row.start)
            if (old == null) insertSession(row) else if (old.end != row.end) updateSessionEnd(old.id, row.end)
        }
    }

    @Insert suspend fun insertVisit(row: VisitRow): Long

    @Query("UPDATE visits SET `end` = :end, latitude = :latitude, longitude = :longitude WHERE id = :id")
    suspend fun updateVisit(id: Long, end: Long, latitude: Double, longitude: Double)

    @Query("SELECT * FROM visits WHERE id = :id")
    suspend fun visit(id: Long): VisitRow?

    @Query("SELECT * FROM places")
    suspend fun places(): List<PlaceRow>

    @Insert suspend fun insertPlace(row: PlaceRow): Long

    @Query("UPDATE visits SET placeId = :placeId WHERE id IN (:ids)")
    suspend fun setVisitPlace(ids: List<Long>, placeId: Long)

    @Query("UPDATE places SET label = :label WHERE id = :id")
    suspend fun renamePlace(id: Long, label: String)

    /** Removes a place and leaves its visits unnamed */
    @Transaction
    suspend fun forgetPlace(id: Long) {
        clearVisitPlace(id)
        deletePlace(id)
    }

    @Query("UPDATE visits SET placeId = NULL WHERE placeId = :id")
    suspend fun clearVisitPlace(id: Long)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun deletePlace(id: Long)

    @Query("SELECT * FROM visits WHERE placeId IS NULL")
    suspend fun unnamedVisits(): List<VisitRow>

    @Insert suspend fun insertAgentAction(row: AgentActionRow): Long

    @Query("SELECT * FROM event_sends WHERE occurrence = :occurrence")
    suspend fun eventSendFor(occurrence: String): EventSendRow?

    @Query("SELECT * FROM event_sends WHERE id = :id")
    suspend fun eventSend(id: Long): EventSendRow?

    @Insert suspend fun insertEventSend(row: EventSendRow): Long

    @Query("UPDATE event_sends SET status = :status, detail = :detail, attempts = :attempts WHERE id = :id")
    suspend fun updateEventSend(id: Long, status: String, detail: String, attempts: Int)

    /** Keeps [row] as a send to make, once per occurrence: null when its occurrence was kept before */
    @Transaction
    suspend fun claimEventSend(row: EventSendRow): EventSendRow? {
        if (eventSendFor(row.occurrence) != null) return null
        return row.copy(id = insertEventSend(row))
    }

    @Query("DELETE FROM event_posts WHERE at <= :through") suspend fun deleteEventPostsThrough(through: Long): Int

    @Query("SELECT at FROM event_posts ORDER BY at") suspend fun eventPostTimes(): List<Long>

    @Insert suspend fun insertEventPost(row: EventPostRow): Long

    /**
     * Takes one of [cap] POSTs a [window] allows, at [now]. Returns null when one was taken, else the time the oldest
     * POST leaves the window and frees one.
     */
    @Transaction
    suspend fun reserveEventPost(now: Long, cap: Int, window: Long): Long? {
        deleteEventPostsThrough(now - window)
        val times = eventPostTimes()
        if (times.size >= cap) return times[times.size - cap] + window
        insertEventPost(EventPostRow(at = now))
        return null
    }

    @Query("SELECT * FROM event_sends WHERE at >= :from AND at < :to ORDER BY at")
    suspend fun eventSends(from: Long, to: Long): List<EventSendRow>

    @Query("SELECT * FROM event_sends WHERE status IN ('PENDING', 'WAITING')")
    suspend fun unsentEventSends(): List<EventSendRow>

    @Query("SELECT * FROM event_sends ORDER BY id DESC LIMIT 1")
    suspend fun latestEventSend(): EventSendRow?

    @Query("SELECT * FROM sessions WHERE `end` > :from AND start < :to ORDER BY start")
    suspend fun sessions(from: Long, to: Long): List<SessionRow>

    @Query(
        "SELECT v.id, v.start, v.`end`, v.latitude, v.longitude, p.label FROM visits v LEFT JOIN places p ON p.id = v.placeId " +
            "WHERE v.`end` > :from AND v.start < :to ORDER BY v.start",
    )
    suspend fun visits(from: Long, to: Long): List<VisitWithLabel>

    @Query("SELECT packageName, app, sender, COUNT(*) AS count, MAX(fromPerson) AS fromPerson FROM notifications WHERE at >= :since GROUP BY packageName, sender ORDER BY count DESC LIMIT :limit")
    suspend fun notificationSenders(since: Long, limit: Int): List<NotificationSender>

    @Query("SELECT p.label FROM visits v JOIN places p ON p.id = v.placeId WHERE v.id = :id")
    suspend fun visitLabel(id: Long): String?

    @Query("SELECT * FROM notifications WHERE at >= :from AND at < :to ORDER BY at")
    suspend fun notifications(from: Long, to: Long): List<NotificationRow>

    @Query("SELECT * FROM agent_actions WHERE at >= :from AND at < :to ORDER BY at")
    suspend fun agentActions(from: Long, to: Long): List<AgentActionRow>

    @Query("SELECT * FROM frames WHERE at >= :from AND at < :to ORDER BY at")
    suspend fun frames(from: Long, to: Long): List<FrameRow>

    @Query("SELECT * FROM frames WHERE packageName = :packageName AND at >= :from AND at <= :to ORDER BY at")
    suspend fun framesOf(packageName: String, from: Long, to: Long): List<FrameRow>

    @Query("SELECT * FROM notifications WHERE conversation = :conversation AND at >= :from AND at <= :to ORDER BY at")
    suspend fun conversation(conversation: String, from: Long, to: Long): List<NotificationRow>

    @Query("SELECT * FROM agent_actions WHERE (agentId = :agentId OR (agentId IS NULL AND agent = :name)) AND at >= :from AND at <= :to ORDER BY at")
    suspend fun agentActionsOf(agentId: String, name: String, from: Long, to: Long): List<AgentActionRow>

    @Query("SELECT * FROM agent_actions WHERE agentId = :agentId OR (agentId IS NULL AND agent = :name) ORDER BY at DESC LIMIT :limit")
    suspend fun latestAgentActions(agentId: String, name: String, limit: Int): List<AgentActionRow>

    @Query(
        "SELECT MIN(at) FROM (SELECT MIN(at) AS at FROM frames UNION ALL SELECT MIN(at) FROM notifications " +
            "UNION ALL SELECT MIN(start) FROM sessions UNION ALL SELECT MIN(start) FROM visits UNION ALL SELECT MIN(at) FROM agent_actions " +
            "UNION ALL SELECT MIN(at) FROM event_sends)",
    )
    suspend fun firstAt(): Long?

    @RawQuery suspend fun searchHits(query: RoomRawQuery): List<SearchHitRow>

    @Query("DELETE FROM frames WHERE at < :before") suspend fun deleteFramesBefore(before: Long): Int
    @Query("DELETE FROM notifications WHERE at < :before") suspend fun deleteNotificationsBefore(before: Long): Int
    @Query("DELETE FROM sessions WHERE `end` < :before") suspend fun deleteSessionsBefore(before: Long): Int
    @Query("DELETE FROM visits WHERE `end` < :before") suspend fun deleteVisitsBefore(before: Long): Int
    @Query("DELETE FROM agent_actions WHERE at < :before") suspend fun deleteAgentActionsBefore(before: Long): Int
    @Query("DELETE FROM event_sends WHERE at < :before") suspend fun deleteEventSendsBefore(before: Long): Int

    @Query("DELETE FROM frames WHERE at >= :from") suspend fun deleteFramesSince(from: Long): Int
    @Query("DELETE FROM notifications WHERE at >= :from") suspend fun deleteNotificationsSince(from: Long): Int
    @Query("DELETE FROM sessions WHERE `end` >= :from") suspend fun deleteSessionsSince(from: Long): Int
    @Query("DELETE FROM visits WHERE `end` >= :from") suspend fun deleteVisitsSince(from: Long): Int
    @Query("DELETE FROM agent_actions WHERE at >= :from") suspend fun deleteAgentActionsSince(from: Long): Int
    @Query("DELETE FROM event_sends WHERE at >= :from") suspend fun deleteEventSendsSince(from: Long): Int

    @Query(
        """
        SELECT 'frames' AS name, COUNT(*) AS count FROM frames UNION ALL SELECT 'notifications', COUNT(*) FROM notifications
        UNION ALL SELECT 'sessions', COUNT(*) FROM sessions UNION ALL SELECT 'visits', COUNT(*) FROM visits
        UNION ALL SELECT 'places', COUNT(*) FROM places UNION ALL SELECT 'agent_actions', COUNT(*) FROM agent_actions
        UNION ALL SELECT 'event_sends', COUNT(*) FROM event_sends
        """,
    )
    suspend fun counts(): List<TableCount>
}

@Database(
    entities = [
        FrameRow::class, NotificationRow::class, SessionRow::class, PlaceRow::class, VisitRow::class, AgentActionRow::class,
        DeletionRow::class, DeletedNotificationRow::class, EventSendRow::class, EventPostRow::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class ContextDatabase : RoomDatabase() {
    abstract fun dao(): ContextDao

    companion object {
        const val FILE = "context.db"

        fun open(context: Context, path: String = context.getDatabasePath(FILE).absolutePath): ContextDatabase =
            Room.databaseBuilder<ContextDatabase>(context, path).configure().build()

        /** A database that lives only in memory, for tests */
        fun inMemory(context: Context): ContextDatabase = Room.inMemoryDatabaseBuilder<ContextDatabase>(context).configure().build()

        // The bundled SQLite, since the phone's own may be built without FTS5
        private fun RoomDatabase.Builder<ContextDatabase>.configure() = setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addCallback(SearchTables)
            .addMigrations(AddDeletions, AddEventSends, AddSenderKindsAndEventPosts, AddAgentIds)

        /** Keeps what Delete today removed, so collectors don't write it again */
        private val AddDeletions = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("CREATE TABLE IF NOT EXISTS `deletions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `start` INTEGER NOT NULL, `end` INTEGER NOT NULL)")
                connection.execSQL("CREATE TABLE IF NOT EXISTS `deleted_notifications` (`dedupe` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`dedupe`))")
            }
        }

        /** Keeps the phone events sent to the user's routine */
        private val AddEventSends = object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `event_sends` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `occurrence` TEXT NOT NULL, " +
                        "`at` INTEGER NOT NULL, `type` TEXT NOT NULL, `battery` INTEGER, `place` TEXT, `person` TEXT, `app` TEXT, " +
                        "`status` TEXT NOT NULL, `detail` TEXT NOT NULL, `attempts` INTEGER NOT NULL)",
                )
                connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_event_sends_occurrence` ON `event_sends` (`occurrence`)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_event_sends_at` ON `event_sends` (`at`)")
            }
        }

        /** Marks which notifications came from a person, so only those senders are offered as people, and counts POSTs for the cap */
        private val AddSenderKindsAndEventPosts = object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `notifications` ADD COLUMN `fromPerson` INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("CREATE TABLE IF NOT EXISTS `event_posts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_event_posts_at` ON `event_posts` (`at`)")
            }
        }

        /** Keeps the agent's id on each step, so two agents with one name don't see each other's steps. Older steps keep none. */
        private val AddAgentIds = object : Migration(4, 5) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `agent_actions` ADD COLUMN `agentId` TEXT")
            }
        }
    }
}

/**
 * The full-text indexes, which Room doesn't make for FTS5. Each reads its text from its table, and triggers keep it in
 * step, so a row deleted by retention or by Delete today leaves the search too.
 */
private object SearchTables : RoomDatabase.Callback() {
    override suspend fun onCreate(connection: SQLiteConnection) {
        index(connection, "frames", listOf("text"))
        index(connection, "notifications", listOf("sender", "text"))
    }

    private suspend fun index(connection: SQLiteConnection, table: String, columns: List<String>) {
        val fts = "${table}_fts"
        val list = columns.joinToString(", ")
        val newValues = columns.joinToString(", ") { "new.$it" }
        val oldValues = columns.joinToString(", ") { "old.$it" }
        connection.execSQL(
            "CREATE VIRTUAL TABLE $fts USING fts5($list, content='$table', content_rowid='id', tokenize='unicode61 remove_diacritics 2')",
        )
        connection.execSQL("CREATE TRIGGER ${table}_ai AFTER INSERT ON $table BEGIN INSERT INTO $fts(rowid, $list) VALUES (new.id, $newValues); END")
        connection.execSQL(
            "CREATE TRIGGER ${table}_ad AFTER DELETE ON $table BEGIN INSERT INTO $fts($fts, rowid, $list) VALUES ('delete', old.id, $oldValues); END",
        )
        connection.execSQL(
            "CREATE TRIGGER ${table}_au AFTER UPDATE ON $table BEGIN " +
                "INSERT INTO $fts($fts, rowid, $list) VALUES ('delete', old.id, $oldValues); " +
                "INSERT INTO $fts(rowid, $list) VALUES (new.id, $newValues); END",
        )
    }
}
