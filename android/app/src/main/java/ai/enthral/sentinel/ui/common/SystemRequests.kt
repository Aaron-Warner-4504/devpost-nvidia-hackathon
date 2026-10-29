package ai.enthral.sentinel.ui.common

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import ai.enthral.sentinel.ui.onboarding.SystemRequest

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun Context.openAppSettings() = startSafely(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
)

fun Context.openNotificationAccessSettings() {
    val ok = startSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    if (!ok) openAppSettings()
}

fun Context.openChannelSettings(channelId: String) {
    val ok = startSafely(
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId),
    )
    if (!ok) openAppSettings()
}

fun Context.openAppNotificationSettings() {
    val ok = startSafely(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
    )
    if (!ok) openAppSettings()
}

/**
 * Asks Android to stop battery-optimising Sentinel (system dialog). Falls back to the battery-optimisation list
 * and then to app settings on OEMs that do not offer the dialog.
 */
@android.annotation.SuppressLint("BatteryLife") // the user asked for it after a plain explanation; capture is the core function
fun Context.requestBatteryExemption() {
    val ok = startSafely(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null)),
    ) || startSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    if (!ok) openAppSettings()
}

private fun Context.startSafely(intent: Intent): Boolean = try {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}

/**
 * Returns a function that performs [SystemRequest]s (system settings screens and the call-log permission) and
 * reports the permission result to [onCallResult].
 */
@Composable
fun rememberSystemRequester(onCallResult: (granted: Boolean, permanentlyDenied: Boolean) -> Unit): (SystemRequest) -> Unit {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onCallResult)
    // Call info: the call log (number, time, duration, direction), phone state (to notice when a call ends) and
    // contacts (yes/no "is this number saved"), asked together after the consent sheet. Only READ_CALL_LOG is required.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.READ_CALL_LOG] == true
        val activity = context.findActivity()
        // Denied and the system will not show its dialog again -> only app settings can fix it.
        val permanent = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_CALL_LOG)
        latest.value(granted, permanent)
    }
    // Notifications source: first ask for READ_CONTACTS (yes/no "saved contact" checks; names never leave the phone),
    // then open Notification access whatever the answer. Contacts are optional: without them is_contact stays unknown
    // and sender names are always replaced by a code.
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        context.openNotificationAccessSettings()
    }
    return remember(launcher, contactsLauncher, context) {
        { request ->
            when (request) {
                SystemRequest.OpenNotificationAccess -> {
                    val needContacts = androidx.core.content.ContextCompat.checkSelfPermission(
                        context, Manifest.permission.READ_CONTACTS,
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (needContacts) contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
                    else context.openNotificationAccessSettings()
                }
                SystemRequest.OpenAppSettings -> context.openAppSettings()
                SystemRequest.RequestCallPermission -> launcher.launch(CALL_PERMISSIONS)
            }
        }
    }
}

val CALL_PERMISSIONS = arrayOf(
    Manifest.permission.READ_CALL_LOG,
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.READ_CONTACTS,
)

/** Launcher for POST_NOTIFICATIONS (API 33+); below that the call just opens the app's notification settings. */
@Composable
fun rememberPostNotificationsRequester(onResult: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { latest.value() }
    return remember(launcher, context) {
        {
            if (android.os.Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else context.openAppNotificationSettings()
        }
    }
}
