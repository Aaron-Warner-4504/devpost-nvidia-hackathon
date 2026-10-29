package ai.enthral.sentinel.capture

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.os.BundleCompat

/** Copies the fields Sentinel may use out of a StatusBarNotification into plain data. Called only for allowlisted apps. */
object AndroidNotificationReader {
    fun read(sbn: StatusBarNotification): RawNotification {
        val n = sbn.notification
        val extras: Bundle = n.extras ?: Bundle.EMPTY
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val messages = style?.messages.orEmpty().map { m ->
            RawMessage(
                text = m.text?.toString(),
                timestamp = m.timestamp,
                senderName = m.person?.name?.toString(),
                senderUri = m.person?.uri,
            )
        }
        val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        return RawNotification(
            packageName = sbn.packageName,
            key = sbn.key,
            postTime = sbn.postTime,
            ongoing = sbn.isOngoing || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            foregroundService = (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0,
            groupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            category = n.category,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty(),
            conversationTitle = style?.conversationTitle?.toString(),
            isGroupConversation = style?.isGroupConversation ?: false,
            messages = messages,
            peopleUris = peopleUris(extras),
            hasProgress = progressMax > 0 || indeterminate,
            isMedia = extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || template.contains("MediaStyle"),
        )
    }

    private fun peopleUris(extras: Bundle): List<String> {
        val out = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 28) {
            BundleCompat.getParcelableArrayList(extras, Notification.EXTRA_PEOPLE_LIST, android.app.Person::class.java)
                ?.mapNotNullTo(out) { it.uri }
        }
        @Suppress("DEPRECATION")
        extras.getStringArray(Notification.EXTRA_PEOPLE)?.let { out.addAll(it) }
        return out.distinct()
    }
}
