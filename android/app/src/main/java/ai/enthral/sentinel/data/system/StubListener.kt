package ai.enthral.sentinel.data.system

import android.service.notification.NotificationListenerService

/**
 * Registers the app in the system "Notification access" list so the user can grant it during onboarding.
 * Deliberately empty: it does not read, store or forward any notification. Capture arrives with the
 * notification pipeline (plan task A6).
 */
class SentinelNotificationListener : NotificationListenerService()
