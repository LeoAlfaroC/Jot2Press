package io.github.leoalfaroc.jot2press.send

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.leoalfaroc.jot2press.Jot2PressApp
import io.github.leoalfaroc.jot2press.data.Queue
import io.github.leoalfaroc.jot2press.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.util.concurrent.TimeUnit

/** Decides when the background jobs run. The jobs themselves are at the end of this file. */
class Scheduler(
    private val work: WorkManager,
    private val queue: Queue,
    private val settings: SettingsStore,
    private val clock: Clock,
) {
    /**
     * Brings the background jobs in line with the queue. Call it after anything that may have left something to send:
     * an entry sent, edited or retried, a successful `Test connection`, the app starting.
     */
    suspend fun sync() {
        queue.promoteDue()
        for (entry in queue.inGrace()) {
            val wait = ((entry.graceEndsAt ?: 0) - clock.millis()).coerceAtLeast(0)
            val wakeUp = OneTimeWorkRequestBuilder<GraceWorker>().setInitialDelay(wait, TimeUnit.MILLISECONDS).build()
            work.enqueueUniqueWork(graceWork(entry.id), ExistingWorkPolicy.REPLACE, wakeUp)
        }
        if (queue.nextPending() != null) ensureSend()
    }

    /** The `Upload on Wi-Fi only` setting changed: the send job is replaced at once by one with the new requirement. */
    suspend fun reschedule() {
        if (queue.nextPending() != null) work.enqueueUniqueWork(SEND_WORK, ExistingWorkPolicy.REPLACE, sendRequest())
    }

    /**
     * Makes sure a send job will run from now on. A job that is waiting is replaced, which also restarts its backoff.
     * A job that is running is left to finish, with one more queued behind it: replacing it would cancel an upload
     * half way, and a slow upload would never end while entries keep being written.
     */
    private suspend fun ensureSend() {
        val jobs = work.getWorkInfosForUniqueWorkFlow(SEND_WORK).first()
        val running = jobs.any { it.state == WorkInfo.State.RUNNING }
        val queuedBehind = jobs.any { it.state == WorkInfo.State.BLOCKED }
        when {
            !running -> work.enqueueUniqueWork(SEND_WORK, ExistingWorkPolicy.REPLACE, sendRequest())
            !queuedBehind -> work.enqueueUniqueWork(SEND_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, sendRequest())
        }
    }

    private suspend fun sendRequest(): OneTimeWorkRequest {
        val network = if (settings.get().wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        return OneTimeWorkRequestBuilder<SendWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        const val SEND_WORK = "send"
        fun graceWork(entryId: String) = "grace-$entryId"
    }
}

/** Runs the sender when there is a network that the settings allow. */
class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as Jot2PressApp).container
        val result = try {
            if (container.sender.run() == Sender.Outcome.RETRY) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failure ends the job for good; storage that failed once may work on the next try.
            Result.retry()
        }
        // An entry becomes stuck by the passing of time alone, so the widget is told after every run.
        container.redrawWidget()
        return result
    }
}

/** Wakes up when an entry's grace window ends, to hand the entry to the sender. */
class GraceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as Jot2PressApp).container.scheduler.sync()
        return Result.success()
    }
}
