package ai.enthral.sentinel.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ai.enthral.sentinel.SentinelApp
import java.util.concurrent.TimeUnit

private val Context.capture get() = (applicationContext as SentinelApp).container.capture

/** Drains the outbox. Unique ("event-sync"), needs a network, exponential backoff from 30 s. */
class EventSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (applicationContext.capture.sender.drain()) {
        DrainResult.Done, DrainResult.NotReady -> Result.success()
        DrainResult.Retry, DrainResult.Backoff -> Result.retry()
    }
}

/** One late-upgrade check (GET /v1/alerts) scheduled at +10 s / +30 s / +90 s after a send. */
class FollowUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (applicationContext.capture.sender.checkAlerts()) Result.success() else Result.failure()
}

/** Every 15 minutes: flush anything still queued and look for alerts that were never notified. */
class PeriodicSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.capture
        c.sender.drain()
        c.sender.checkAlerts()
        return Result.success()
    }
}

class WorkManagerSyncScheduler(private val context: Context) : SyncScheduler {
    private val wm get() = WorkManager.getInstance(context)
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun scheduleSync(delayMs: Long) {
        val req = OneTimeWorkRequestBuilder<EventSyncWorker>()
            .setConstraints(online)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // KEEP: a pending drain (with its backoff) already covers newly queued items.
        wm.enqueueUniqueWork(SYNC, ExistingWorkPolicy.KEEP, req)
    }

    override fun scheduleFollowUps(delaysMs: List<Long>) {
        delaysMs.forEach { d ->
            val req = OneTimeWorkRequestBuilder<FollowUpWorker>()
                .setConstraints(online)
                .setInitialDelay(d, TimeUnit.MILLISECONDS)
                .build()
            wm.enqueueUniqueWork("$FOLLOW_UP-$d", ExistingWorkPolicy.KEEP, req)
        }
    }

    fun schedulePeriodic() {
        val req = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(online)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancelAll() {
        wm.cancelUniqueWork(SYNC)
        wm.cancelUniqueWork(PERIODIC)
        FollowUpPolicy.DELAYS_MS.forEach { wm.cancelUniqueWork("$FOLLOW_UP-$it") }
    }

    companion object {
        const val SYNC = "event-sync"
        const val FOLLOW_UP = "alert-follow-up"
        const val PERIODIC = "periodic-sync"
    }
}
