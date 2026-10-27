package ai.tushar.handset.context

import ai.tushar.handset.Handset
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.text.DateFormat
import java.util.Date

/** A Quick Settings tile that pauses the day record for an hour, or resumes it */
class PauseTileService : TileService() {
    override fun onStartListening() = update()

    override fun onClick() {
        val recording = Handset.instance.recording
        if (recording.paused) recording.resume() else recording.pauseForAnHour()
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val recording = Handset.instance.recording
        tile.state = if (recording.paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            recording.stopped -> "Stopped"
            recording.paused -> "Until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(recording.pausedUntil.value))}"
            else -> "For an hour"
        }
        tile.updateTile()
    }
}
