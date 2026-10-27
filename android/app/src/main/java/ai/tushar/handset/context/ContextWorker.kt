package ai.tushar.handset.context

import ai.tushar.handset.Handset
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Every few hours, whenever Android finds it convenient: reads new app sessions, asks for location fixes again (Android
 * forgets the request when the phone restarts), and once a day deletes what is past its retention.
 */
class ContextWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val handset = Handset.instance
        handset.sessions.pull()
        handset.places.sync()
        val preferences = applicationContext.getSharedPreferences("sessions", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - preferences.getLong(LAST_CLEAN, 0L) >= CLEAN_EVERY_MS) {
            handset.store.clean(handset.recording.keep.days)
            preferences.edit().putLong(LAST_CLEAN, now).apply()
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "context"
        private const val LAST_CLEAN = "last_clean"
        private const val CLEAN_EVERY_MS = 20 * 60 * 60_000L
        private const val INTERVAL_HOURS = 3L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ContextWorker>(INTERVAL_HOURS, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
