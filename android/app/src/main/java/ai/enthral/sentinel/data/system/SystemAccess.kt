package ai.enthral.sentinel.data.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Live view of what the OS has actually granted (the user can change it any time in system settings). */
interface SystemAccess {
    fun notificationAccessGranted(): Boolean
    fun callLogGranted(): Boolean
    fun postNotificationsGranted(): Boolean

    /** READ_CONTACTS: only used for yes/no "is this a saved contact" checks. */
    fun contactsGranted(): Boolean = false

    /** True when Sentinel is exempt from battery optimisation (capture survives Doze / OEM killers better). */
    fun batteryOptimizationIgnored(): Boolean = true
}

class AndroidSystemAccess(private val context: Context) : SystemAccess {
    override fun notificationAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    override fun callLogGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    override fun contactsGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    override fun batteryOptimizationIgnored(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    override fun postNotificationsGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
}
