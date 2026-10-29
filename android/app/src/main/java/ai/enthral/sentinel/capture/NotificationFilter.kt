package ai.enthral.sentinel.capture

import ai.enthral.sentinel.domain.Source

/** One MessagingStyle message, as plain data (no Android types, so the logic is JVM-testable). */
data class RawMessage(
    val text: String?,
    val timestamp: Long,
    val senderName: String?,
    /** Person.uri of the message sender (contact lookup URI, `tel:` or `mailto:`), if the app provided it. */
    val senderUri: String?,
)

/**
 * Everything Sentinel may look at in a notification, copied out of the StatusBarNotification by
 * [AndroidNotificationReader]. Nothing here is persisted or logged.
 */
data class RawNotification(
    val packageName: String,
    val key: String,
    val postTime: Long,
    val ongoing: Boolean = false,
    val foregroundService: Boolean = false,
    val groupSummary: Boolean = false,
    val category: String? = null,
    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val textLines: List<String> = emptyList(),
    val conversationTitle: String? = null,
    val isGroupConversation: Boolean = false,
    val messages: List<RawMessage> = emptyList(),
    /** EXTRA_PEOPLE_LIST Person uris. */
    val peopleUris: List<String> = emptyList(),
    val hasProgress: Boolean = false,
    val isMedia: Boolean = false,
)

/** Why a notification was not captured (counted, never logged with content). */
enum class IgnoreReason { NotAllowlisted, OwnApp, Ongoing, ForegroundService, GroupSummary, Category, MediaOrProgress, Empty, NothingNew }

sealed interface FilterDecision {
    data class Accept(val source: Source) : FilterDecision
    data class Ignore(val reason: IgnoreReason) : FilterDecision
}

/** A piece of content ready for redaction: still raw, lives only in memory for the duration of one callback. */
data class Extracted(
    val source: Source,
    val packageName: String,
    /** Stable per conversation / notification: used for de-duplication and MessagingStyle cursors. */
    val conversationKey: String,
    val title: String?,
    val text: String,
    val timestamp: Long,
    /** Seed for the idempotent device_event_id (with the redacted text). */
    val idSeed: String,
    val personUris: List<String>,
    val isGroup: Boolean,
    /** Newest MessagingStyle timestamp included; the caller advances the cursor to it once queued. */
    val cursorTs: Long?,
)

/** Decides whether a notification is captured at all. Pure. */
class NotificationFilter(private val allowlist: Map<String, Source>, private val ownPackage: String) {
    fun decide(n: RawNotification): FilterDecision {
        if (n.packageName == ownPackage) return FilterDecision.Ignore(IgnoreReason.OwnApp)
        val source = allowlist[n.packageName] ?: return FilterDecision.Ignore(IgnoreReason.NotAllowlisted)
        return when {
            n.foregroundService -> FilterDecision.Ignore(IgnoreReason.ForegroundService)
            n.ongoing -> FilterDecision.Ignore(IgnoreReason.Ongoing)
            n.groupSummary -> FilterDecision.Ignore(IgnoreReason.GroupSummary)
            n.category != null && n.category in IGNORED_CATEGORIES -> FilterDecision.Ignore(IgnoreReason.Category)
            n.isMedia || n.hasProgress -> FilterDecision.Ignore(IgnoreReason.MediaOrProgress)
            else -> FilterDecision.Accept(source)
        }
    }

    companion object {
        /** Notification.CATEGORY_* values that never carry a message: calls, media, progress, system status. */
        val IGNORED_CATEGORIES = setOf(
            "call", "missed_call", "transport", "progress", "service", "status", "alarm", "sys", "navigation",
            "stopwatch", "location_sharing", "workout",
        )
    }
}

/**
 * Pulls the text out of an accepted notification. MessagingStyle notifications yield only the messages newer than
 * the per-conversation cursor (WhatsApp re-posts the whole recent history with every new message).
 */
object NotificationExtractor {
    /** Cursor / de-dupe key of a MessagingStyle conversation. */
    fun messagingKey(n: RawNotification): String {
        val conv = n.conversationTitle?.trim()?.takeIf { it.isNotEmpty() } ?: n.title?.trim()?.takeIf { it.isNotEmpty() }
        return "${n.packageName}|${conv ?: n.key}"
    }

    fun extract(n: RawNotification, source: Source, lastSeenTs: Long?): Extracted? {
        val convTitle = n.conversationTitle?.trim()?.takeIf { it.isNotEmpty() }
        val title = n.title?.trim()?.takeIf { it.isNotEmpty() }
        if (n.messages.isNotEmpty()) {
            val fresh = n.messages.filter { !it.text.isNullOrBlank() && (lastSeenTs == null || it.timestamp > lastSeenTs) }
                .sortedBy { it.timestamp }
            if (fresh.isEmpty()) return null
            val convKey = messagingKey(n)
            // 1:1 chats: the conversation is the other person; groups: the group name (never a member's name).
            val sender = if (n.isGroupConversation) convTitle ?: title else convTitle ?: fresh.last().senderName ?: title
            val uris = if (n.isGroupConversation) emptyList()
            else (fresh.mapNotNull { it.senderUri } + n.peopleUris).distinct()
            val newest = fresh.last().timestamp
            return Extracted(
                source = source,
                packageName = n.packageName,
                conversationKey = convKey,
                title = sender,
                text = fresh.joinToString("\n") { it.text!!.trim() },
                timestamp = newest,
                idSeed = "${n.packageName}|$convKey|$newest",
                personUris = uris,
                isGroup = n.isGroupConversation,
                cursorTs = newest,
            )
        }
        val body = n.bigText?.trim()?.takeIf { it.isNotEmpty() }
            ?: n.textLines.map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.joinToString("\n")
            ?: n.text?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val text = if (!CaptureAllowlist.titleIsSender(n.packageName) && title != null) "$title\n$body" else body
        return Extracted(
            source = source,
            packageName = n.packageName,
            conversationKey = "${n.packageName}|${n.key}",
            title = title,
            text = text,
            timestamp = n.postTime,
            idSeed = "${n.packageName}|${n.key}|${n.postTime}",
            personUris = n.peopleUris,
            isGroup = n.isGroupConversation,
            cursorTs = null,
        )
    }
}
