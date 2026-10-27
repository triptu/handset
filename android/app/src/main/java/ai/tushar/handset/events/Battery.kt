package ai.tushar.handset.events

import kotlinx.serialization.Serializable

/**
 * Turns battery readings into events. Charging started and stopped fire on each change of the plug. Battery low fires
 * once per discharge: the first reading below the level after the phone was unplugged, and not again until it charges.
 */
object Battery {
    data class Reading(val at: Long, val level: Int, val plugged: Boolean)

    /** What the last reading left behind. [lowSent] holds until the phone is plugged in. */
    @Serializable
    data class Memory(val plugged: Boolean, val unpluggedAt: Long, val lowSent: Boolean)

    data class Step(val memory: Memory, val events: List<PhoneEvent>)

    /**
     * Feeds one reading. The first reading only sets the memory: a battery already low when the switch went on, or a
     * charger already in, isn't news.
     */
    fun step(memory: Memory?, reading: Reading, threshold: Int): Step {
        val low = !reading.plugged && reading.level < threshold
        if (memory == null) return Step(Memory(reading.plugged, if (reading.plugged) 0 else reading.at, lowSent = low), emptyList())
        val events = ArrayList<PhoneEvent>()
        var next = memory
        if (reading.plugged != memory.plugged) {
            val type = if (reading.plugged) EventType.CHARGING_STARTED else EventType.CHARGING_STOPPED
            events += PhoneEvent(type, reading.at, "${type.code}|${reading.at}", battery = reading.level)
            next = if (reading.plugged) Memory(true, memory.unpluggedAt, lowSent = false) else Memory(false, reading.at, lowSent = false)
        }
        if (low && !next.lowSent) {
            events += PhoneEvent(EventType.BATTERY_LOW, reading.at, "${EventType.BATTERY_LOW.code}|${next.unpluggedAt}", battery = reading.level)
            next = next.copy(lowSent = true)
        }
        return Step(next, events)
    }
}
