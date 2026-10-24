package ai.enthral.sentinel.data.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ai.enthral.sentinel.R
import ai.enthral.sentinel.domain.Alert
import ai.enthral.sentinel.domain.Tier

object NotificationChannels {
    const val CRITICAL = "alerts_critical"
    const val HIGH = "alerts_high"
    const val UPDATES = "updates"
}

class AndroidAlertNotifier(private val context: Context) : AlertNotifier {
    private val manager = NotificationManagerCompat.from(context)

    override fun ensureChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                NotificationChannels.CRITICAL,
                context.getString(R.string.channel_critical_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_critical_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                NotificationChannels.HIGH,
                context.getString(R.string.channel_high_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.channel_high_desc) },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                NotificationChannels.UPDATES,
                context.getString(R.string.channel_updates_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.channel_updates_desc) },
        )
    }

    override fun canNotify(): Boolean =
        manager.areNotificationsEnabled() &&
            (android.os.Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)

    override fun notify(alert: Alert) {
        if (!alert.tier.interrupts || !canNotify()) return
        ensureChannels()
        val critical = alert.tier == Tier.Critical
        val tierLabel = context.getString(if (critical) R.string.tier_critical else R.string.tier_high)
        val open = PendingIntent.getActivity(
            context,
            alert.id.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse("sentinel://alert/${Uri.encode(alert.id)}"))
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = context.getString(R.string.notification_title, tierLabel)
        // Lock screen shows only a generic line; the headline appears once the phone is unlocked.
        val public = NotificationCompat.Builder(context, channelId(critical))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.notification_public_title))
            .build()
        val n = NotificationCompat.Builder(context, channelId(critical))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(alert.headline)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.headline + "\n" + alert.nextStep))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            manager.notify(alert.id.hashCode(), n)
        } catch (_: SecurityException) {
            // permission revoked between canNotify() and notify(): nothing to do
        }
    }

    private fun channelId(critical: Boolean) = if (critical) NotificationChannels.CRITICAL else NotificationChannels.HIGH
}
