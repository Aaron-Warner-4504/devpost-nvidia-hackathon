package ai.enthral.sentinel.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ai.enthral.sentinel.SentinelApp

/** After a reboot or an app update: re-arm the 15-minute flush/alert check and drain anything still queued. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val capture = (context.applicationContext as SentinelApp).container.capture
        capture.scheduler.schedulePeriodic()
        capture.scheduler.scheduleSync()
    }
}
