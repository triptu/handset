package ai.tushar.handset.events

import ai.tushar.handset.context.EventSendRow
import ai.tushar.handset.context.SendStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** What happened on the phone, as its payload names it */
enum class EventType(val code: String, val title: String) {
    BATTERY_LOW("battery_low", "Battery low"),
    CHARGING_STARTED("charging_started", "Charging started"),
    CHARGING_STOPPED("charging_stopped", "Charging stopped"),
    ARRIVED("arrived", "Arrived"),
    LEFT("left", "Left"),
    NOTIFICATION("notification", "Notification"),
    TEST("test", "Test event"),
    ;

    companion object {
        fun of(code: String): EventType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One thing that happened, with only the fields a routine needs: the battery level, the place's name, and the person's
 * or app's name. It has no field for message text, so none can be sent. [occurrence] names the thing itself, as one
 * discharge or one visit, so seeing it again doesn't send it again.
 */
data class PhoneEvent(
    val type: EventType,
    val at: Long,
    val occurrence: String,
    val battery: Int? = null,
    val place: String? = null,
    val person: String? = null,
    val app: String? = null,
) {
    fun row(): EventSendRow = EventSendRow(
        occurrence = occurrence, at = at, type = type.code, battery = battery, place = place, person = person, app = app, status = SendStatus.PENDING.name,
    )

    /**
     * Names the occurrence in every payload, the same on each try, so a routine can drop one it already handled. A
     * hash, since an occurrence can hold a notification's key.
     */
    val id: String
        get() = "evt_" + MessageDigest.getInstance("SHA-256").digest(occurrence.encodeToByteArray()).take(ID_BYTES).joinToString("") { "%02x".format(it) }

    /** The event as a row says it: "Arrived at Office", "Notification from Asha" */
    val title: String
        get() = when (type) {
            EventType.ARRIVED -> "Arrived at $place"
            EventType.LEFT -> "Left $place"
            EventType.NOTIFICATION -> "Notification from ${person ?: app}"
            else -> type.title
        }

    /** One plain sentence for a routine's prompt to read */
    val sentence: String
        get() = when (type) {
            EventType.BATTERY_LOW -> "The phone's battery is at $battery%, below the level set in Handset, and it isn't charging."
            EventType.CHARGING_STARTED -> "The phone started charging, at $battery%."
            EventType.CHARGING_STOPPED -> "The phone stopped charging, at $battery%."
            EventType.ARRIVED -> "The phone arrived at $place."
            EventType.LEFT -> "The phone left $place."
            EventType.NOTIFICATION -> if (person != null) "A notification came from $person in $app." else "A notification came from $app."
            EventType.TEST -> "A test event from Handset. Nothing happened on the phone."
        }

    companion object {
        private const val ID_BYTES = 12

        /** How a send went, as Days and Settings say it */
        fun outcome(row: EventSendRow): String = when (row.status) {
            SendStatus.SENT.name -> "Sent"
            SendStatus.WAITING.name -> "Waiting. Handset sends at most ${PhoneEvents.CAP} events an hour, so this one goes within the hour."
            SendStatus.FAILED.name -> "Not sent. ${row.detail}"
            else -> if (row.attempts == 0) "Sending" else "Trying again. ${row.detail}"
        }

        fun of(row: EventSendRow): PhoneEvent? = EventType.of(row.type)?.let {
            PhoneEvent(it, row.at, row.occurrence, row.battery, row.place, row.person, row.app)
        }
    }
}

/** The body a phone event is sent as */
object Payload {
    private val json = Json { prettyPrint = true }

    fun fields(event: PhoneEvent, zone: ZoneId = ZoneId.systemDefault()): JsonObject = buildJsonObject {
        put("event_id", event.id)
        put("type", event.type.code)
        put("time", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(event.at).truncatedTo(ChronoUnit.SECONDS).atZone(zone)))
        event.battery?.let { put("battery", it) }
        event.place?.let { put("place", it) }
        event.person?.let { put("person", it) }
        event.app?.let { put("app", it) }
    }

    /** The fields as indented JSON, as a routine's text holds them and Days shows them */
    fun readable(event: PhoneEvent, zone: ZoneId = ZoneId.systemDefault()): String = json.encodeToString(JsonObject.serializer(), fields(event, zone))

    /**
     * The fields as a JSON object, or for a Claude routine, inside `text`: the sentence and then the fields as a JSON
     * block, since a routine reads `text` as words.
     */
    fun body(event: PhoneEvent, routine: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        if (!routine) return fields(event, zone).toString()
        val text = "Handset phone event. ${event.sentence}\n\n```json\n${readable(event, zone)}\n```"
        return JsonObject(mapOf("text" to JsonPrimitive(text))).toString()
    }
}
