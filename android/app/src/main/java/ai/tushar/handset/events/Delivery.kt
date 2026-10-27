package ai.tushar.handset.events

import ai.tushar.handset.Handset
import ai.tushar.handset.context.ContextStore
import ai.tushar.handset.context.SendStatus
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** What one POST came to */
sealed interface Answer {
    /** A 2xx */
    data class Sent(val code: Int) : Answer

    /** Worth trying again later: a 5xx or a timeout */
    data class Busy(val reason: String) : Answer

    /** Never tried again: a 4xx, a redirect, or an address that can't be reached */
    data class Refused(val reason: String) : Answer
}

fun interface Sender {
    suspend fun post(destination: Destination, body: String): Answer
}

class HttpSender(private val client: OkHttpClient = defaultClient) : Sender {
    override suspend fun post(destination: Destination, body: String): Answer = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(destination.url).post(body.toRequestBody(JSON)).apply {
            destination.headers().forEach { (name, value) -> header(name, value) }
        }.build()
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    in 200..299 -> Answer.Sent(response.code)
                    in 500..599 -> Answer.Busy("The address answered ${response.code}.")
                    else -> Answer.Refused("The address answered ${response.code}.")
                }
            }
        } catch (_: InterruptedIOException) {
            Answer.Busy("The address took too long to answer.")
        } catch (e: Exception) {
            Answer.Refused("Couldn't reach the address: ${e.message ?: e::class.simpleName}.")
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()

        // A redirect would carry the token to another address
        val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

/**
 * Sends one kept event. Each send row is posted until it is sent, refused or out of tries, and a row that is no longer
 * to send is never posted again, so a retried or re-run worker doesn't send twice. Each POST counts against the hourly
 * cap, and a row past it waits.
 */
class Delivery(private val store: ContextStore, private val destination: () -> Destination?, private val sender: Sender) {
    sealed interface Next {
        data object Done : Next

        data object Retry : Next

        /** Try again at [at], when the cap allows another POST */
        data class Wait(val at: Long) : Next
    }

    suspend fun attempt(sendId: Long): Next {
        val row = store.eventSend(sendId) ?: return Next.Done
        if (row.status != SendStatus.PENDING.name && row.status != SendStatus.WAITING.name) return Next.Done
        val event = PhoneEvent.of(row) ?: return fail(sendId, "Handset doesn't know this event.", row.attempts)
        val to = destination() ?: return fail(sendId, "No address to send to.", row.attempts)
        store.reserveEventPost(PhoneEvents.CAP)?.let { at ->
            store.updateEventSend(sendId, SendStatus.WAITING, row.detail, row.attempts)
            return Next.Wait(at)
        }
        val attempts = row.attempts + 1
        return when (val answer = sender.post(to, Payload.body(event, to.routine))) {
            is Answer.Sent -> {
                store.updateEventSend(sendId, SendStatus.SENT, "HTTP ${answer.code}", attempts)
                Next.Done
            }
            is Answer.Refused -> fail(sendId, answer.reason, attempts)
            is Answer.Busy -> if (attempts >= MAX_ATTEMPTS) {
                fail(sendId, "${answer.reason} Gave up after $attempts tries.", attempts)
            } else {
                store.updateEventSend(sendId, SendStatus.PENDING, answer.reason, attempts)
                Next.Retry
            }
        }
    }

    private suspend fun fail(sendId: Long, reason: String, attempts: Int): Next {
        store.updateEventSend(sendId, SendStatus.FAILED, reason, attempts)
        return Next.Done
    }

    companion object {
        const val MAX_ATTEMPTS = 5
    }
}

/**
 * Posts one event once the phone is online, trying again with backoff while the address is busy. Held back by the cap,
 * it queues itself again for when the cap allows.
 */
class EventSendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sendId = inputData.getLong(SEND_ID, -1)
        return when (val next = Handset.instance.events.delivery.attempt(sendId)) {
            Delivery.Next.Done -> Result.success()
            Delivery.Next.Retry -> Result.retry()
            is Delivery.Next.Wait -> {
                // Appended, it starts its delay once this run ends
                enqueue(applicationContext, sendId, next.at - System.currentTimeMillis(), ExistingWorkPolicy.APPEND_OR_REPLACE)
                Result.success()
            }
        }
    }

    companion object {
        private const val SEND_ID = "send_id"

        fun enqueue(context: Context, sendId: Long, delayMs: Long = 0, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val request = OneTimeWorkRequestBuilder<EventSendWorker>()
                .setInputData(workDataOf(SEND_ID to sendId))
                .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("event_send_$sendId", policy, request)
        }
    }
}
