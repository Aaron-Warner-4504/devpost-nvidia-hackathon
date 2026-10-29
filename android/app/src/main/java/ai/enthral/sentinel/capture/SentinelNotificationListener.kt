package ai.enthral.sentinel.capture

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import ai.enthral.sentinel.SentinelApp
import kotlinx.coroutines.launch

/**
 * Reads notifications of the allowlisted apps only ([CaptureAllowlist]) and hands them to [CapturePipeline], which
 * masks them on the phone before anything is queued or sent. Any other app's notification is dropped by package
 * name before a single extra is read. Never logs content.
 *
 * Reliability: the system binds this service while notification access is granted and keeps the process alive; if it
 * disconnects (OEM kill, update) it asks to be rebound, and the BootReceiver / 15-minute worker catch up the queue.
 */
class SentinelNotificationListener : NotificationListenerService() {
    private val capture get() = (applicationContext as SentinelApp).container.capture

    override fun onListenerConnected() {
        capture.scheduler.schedulePeriodic()
        capture.sendNow()
    }

    override fun onListenerDisconnected() {
        requestRebind(ComponentName(this, SentinelNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val c = capture
        if (sbn.packageName !in c.allowlist || sbn.packageName == packageName) return
        val raw = try {
            AndroidNotificationReader.read(sbn)
        } catch (_: RuntimeException) {
            return // malformed extras (bad parcelables from the posting app): skip it
        }
        c.scope.launch { c.pipeline.onPosted(raw) }
    }
}
