package ai.enthral.sentinel.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["stable_event_id"], unique = true),
        Index(value = ["ts"]),
        Index(value = ["created_at"]),
    ],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "ts")
    val ts: Long,
    @ColumnInfo(name = "redacted_text")
    val redactedText: String,
    @ColumnInfo(name = "sender_hash")
    val senderHash: String?,
    @ColumnInfo(name = "stable_event_id")
    val stableEventId: String,
    @ColumnInfo(name = "sync_state")
    val syncState: String, // "pending", "synced", "failed"
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "assessments",
    indices = [
        Index(value = ["stable_event_id"], unique = true),
        Index(value = ["created_at"]),
    ],
)
data class AssessmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "stable_event_id")
    val stableEventId: String,
    @ColumnInfo(name = "tier")
    val tier: String, // "info", "caution", "high", "critical"
    @ColumnInfo(name = "signals")
    val signals: String, // comma-separated
    @ColumnInfo(name = "stages")
    val stages: String, // comma-separated
    @ColumnInfo(name = "explanation")
    val explanation: String,
    @ColumnInfo(name = "origin")
    val origin: String, // "local" | "server"
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "feedback",
    indices = [
        Index(value = ["alert_id"], unique = true),
    ],
)
data class FeedbackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "alert_id")
    val alertId: String,
    @ColumnInfo(name = "label")
    val label: String, // "scam" | "safe"
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * One event waiting for (or done with) delivery to POST /v1/events. Keyed by the stable device_event_id, so the same
 * capture can never be queued twice and a resend is idempotent on the server. [payload] is the REDACTED EventIn as
 * JSON (raw text never reaches this table) and is blanked once the row is sent or dead.
 */
@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["state", "next_attempt_at"]),
        Index(value = ["consent"]),
        Index(value = ["server_event_id"]),
    ],
)
data class OutboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "device_event_id")
    val deviceEventId: String,
    /** Which consent covers this row (ConsentSource name): revoking it purges the row. */
    @ColumnInfo(name = "consent")
    val consent: String,
    @ColumnInfo(name = "payload")
    val payload: String,
    @ColumnInfo(name = "state")
    val state: String, // OutboxState.wire
    @ColumnInfo(name = "attempts")
    val attempts: Int = 0,
    @ColumnInfo(name = "enqueued_at")
    val enqueuedAt: Long,
    @ColumnInfo(name = "next_attempt_at")
    val nextAttemptAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "ringing")
    val ringing: Boolean = false,
    /** Last HTTP status (null: network error or never tried). */
    @ColumnInfo(name = "last_status")
    val lastStatus: Int? = null,
    /** The server's event id once sent: maps late alerts (GET /v1/alerts) back to this device event. */
    @ColumnInfo(name = "server_event_id")
    val serverEventId: String? = null,
)

/** Rows per state, for the sync status UI. */
data class OutboxStateCount(
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "n") val n: Int,
)
