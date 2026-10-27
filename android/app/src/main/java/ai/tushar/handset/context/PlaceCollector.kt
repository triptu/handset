package ai.tushar.handset.context

import ai.tushar.handset.Handset
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Records visits while Places is on. Android delivers a coarse fix every few minutes, batched, to [LocationReceiver], from
 * Wi-Fi and cell towers rather than GPS where it can; fixes other apps ask for come along at no cost. No Google Play
 * services are needed, and no coordinates are turned into addresses, since that would send them to Google. [onVisit]
 * hears when a visit begins, once the stay is long enough, and when it ends.
 */
class PlaceCollector(
    private val context: Context,
    private val store: ContextStore,
    private val recording: Recording,
    private val onVisit: suspend (arrived: Boolean, visitId: Long) -> Unit = { _, _ -> },
) {
    private val preferences = context.getSharedPreferences("places", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(LocationManager::class.java)
    private val lock = Mutex()

    private val intent: PendingIntent by lazy {
        PendingIntent.getBroadcast(context, 0, Intent(context, LocationReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    /** Asks for fixes when Places is on and allowed, and stops asking otherwise. Safe to call often. */
    @SuppressLint("MissingPermission")
    fun sync() {
        val wanted = recording.isOn(Source.PLACES) && Source.PLACES.granted(context)
        runCatching { manager.removeUpdates(intent) }
        if (!wanted) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.hasProvider(LocationManager.FUSED_PROVIDER)) {
                val request = LocationRequest.Builder(INTERVAL_MS)
                    .setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
                    .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
                    .setMaxUpdateDelayMillis(MAX_DELAY_MS)
                    .build()
                manager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, request, intent)
            } else {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, INTERVAL_MS, 0f, intent)
            }
            // Fixes that other apps ask for, which cost nothing more
            manager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, MIN_INTERVAL_MS, 0f, intent)
        }.onFailure { Log.w(TAG, "location updates refused: $it") }
    }

    /** Feeds fixes to the stay in progress, and writes its visit once it is long enough */
    suspend fun accept(locations: List<Location>) = lock.withLock {
        if (!recording.recording(Source.PLACES)) {
            // A stay doesn't carry on across a pause
            forgetStay()
            return@withLock
        }
        // A stay that began before Delete today starts again, so its visit isn't written back
        var stay = loadStay()?.takeIf { it.start > store.deletedThrough() }
        locations.filterNot { recording.excluded(Source.PLACES, it.time) }.sortedBy { it.time }.forEach { location ->
            val sample = Places.Sample(location.time, location.latitude, location.longitude, if (location.hasAccuracy()) location.accuracy else 0f)
            val step = Places.step(stay, sample) ?: return@forEach
            step.ended?.let { ended ->
                ended.visitId?.let {
                    store.extendVisit(it, ended.last, ended.latitude, ended.longitude)
                    onVisit(false, it)
                }
            }
            var current = step.current
            if (current.isVisit) {
                current = if (current.visitId == null) {
                    current.copy(visitId = store.addVisit(current.start, current.last, current.latitude, current.longitude).also { onVisit(true, it) })
                } else {
                    store.extendVisit(current.visitId, current.last, current.latitude, current.longitude)
                    current
                }
            }
            stay = current
        }
        saveStay(stay)
    }

    private fun forgetStay() = preferences.edit().remove(STAY).apply()

    private fun loadStay(): Places.Stay? = preferences.getString(STAY, null)?.let { runCatching { json.decodeFromString<Places.Stay>(it) }.getOrNull() }

    private fun saveStay(stay: Places.Stay?) = preferences.edit().putString(STAY, stay?.let { json.encodeToString(it) }).apply()

    private companion object {
        const val TAG = "Handset/Places"
        const val STAY = "stay"
        const val INTERVAL_MS = 5 * 60_000L
        const val MIN_INTERVAL_MS = 2 * 60_000L
        const val MAX_DELAY_MS = 15 * 60_000L
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Where Android delivers location fixes, also when the app's process had ended */
class LocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val locations = buildList {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Location>(LocationManager.KEY_LOCATIONS)?.let { addAll(it) }
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Location>(LocationManager.KEY_LOCATION_CHANGED)?.let { add(it) }
        }.distinctBy { it.time }
        if (locations.isEmpty()) return
        val handset = Handset.instance
        val pending = goAsync()
        handset.scope.launch {
            try {
                handset.places.accept(locations)
            } finally {
                pending.finish()
            }
        }
    }
}
