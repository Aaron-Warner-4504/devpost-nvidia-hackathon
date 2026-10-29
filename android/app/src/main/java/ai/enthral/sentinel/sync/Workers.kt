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

/** New call-log rows -> outbox -> send. Unique ("call-log-sync"), replaced by a newer call end. */
class CallLogSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.capture
        c.callLog.run()
        return Result.success()
    }
}

/**
 * A call is ringing (call screening) and its event is already in the encrypted outbox: drain now. Expedited so it
 * runs within seconds even though the screening service is unbound right after allowing the call (on API < 31
 * WorkManager runs it as a short foreground job with the low-importance notification of [getForegroundInfo]).
 */
class CallScreeningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (applicationContext.capture.sender.drain()) {
        DrainResult.Done, DrainResult.NotReady -> Result.success()
        DrainResult.Retry, DrainResult.Backoff -> { applicationContext.capture.scheduler.scheduleSync(); Result.success() }
    }

    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo {
        val n = androidx.core.app.NotificationCompat.Builder(applicationContext, ai.enthral.sentinel.data.notify.NotificationChannels.UPDATES)
            .setSmallIcon(ai.enthral.sentinel.R.drawable.ic_shield)
            .setContentTitle(applicationContext.getString(ai.enthral.sentinel.R.string.call_checking))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()
        return androidx.work.ForegroundInfo(0x5E17, n)
    }
}

/** Every 15 minutes: catch up the call log, flush anything still queued, look for alerts never notified. */
class PeriodicSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.capture
        runCatching { c.callLog.run() }
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

    override fun scheduleCallLogSync(delayMs: Long) {
        val req = OneTimeWorkRequestBuilder<CallLogSyncWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniqueWork(CALL_LOG, ExistingWorkPolicy.REPLACE, req)
    }

    fun scheduleScreeningDrain() {
        val req = OneTimeWorkRequestBuilder<CallScreeningWorker>()
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(online)
            .build()
        wm.enqueueUniqueWork(SCREEN, ExistingWorkPolicy.REPLACE, req)
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
        wm.cancelUniqueWork(CALL_LOG)
        FollowUpPolicy.DELAYS_MS.forEach { wm.cancelUniqueWork("$FOLLOW_UP-$it") }
    }

    companion object {
        const val SYNC = "event-sync"
        const val FOLLOW_UP = "alert-follow-up"
        const val PERIODIC = "periodic-sync"
        const val CALL_LOG = "call-log-sync"
        const val SCREEN = "call-screen"
    }
}
