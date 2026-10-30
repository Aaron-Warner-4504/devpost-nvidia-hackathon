package ai.enthral.sentinel.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT * FROM events WHERE stable_event_id = :stableId LIMIT 1")
    suspend fun getByStableId(stableId: String): EventEntity?

    @Query("SELECT * FROM events WHERE sync_state = 'pending' ORDER BY ts ASC")
    suspend fun getPending(): List<EventEntity>

    @Query("UPDATE events SET sync_state = :syncState WHERE stable_event_id = :stableId")
    suspend fun updateSyncState(stableId: String, syncState: String)

    @Query("DELETE FROM events WHERE created_at < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM events")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int
}

@Dao
interface AssessmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(assessment: AssessmentEntity): Long

    @Query("SELECT * FROM assessments WHERE stable_event_id = :stableId LIMIT 1")
    suspend fun getByStableId(stableId: String): AssessmentEntity?

    @Query("SELECT * FROM assessments ORDER BY created_at DESC LIMIT :limit")
    suspend fun getLatest(limit: Int = 100): List<AssessmentEntity>

    @Query("DELETE FROM assessments WHERE created_at < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM assessments")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM assessments")
    suspend fun count(): Int
}

@Dao
interface FeedbackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(feedback: FeedbackEntity): Long

    @Query("SELECT * FROM feedback WHERE alert_id = :alertId LIMIT 1")
    suspend fun getByAlertId(alertId: String): FeedbackEntity?

    @Query("SELECT * FROM feedback")
    suspend fun getAll(): List<FeedbackEntity>

    @Query("DELETE FROM feedback")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM feedback")
    suspend fun count(): Int
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: OutboxEntity): Long

    @Query("SELECT * FROM outbox WHERE device_event_id = :id")
    suspend fun get(id: String): OutboxEntity?

    @Update
    suspend fun update(row: OutboxEntity)

    /**
     * Next row to send: due pending/failed rows, plus in_flight rows whose lease ran out (the process died mid-send).
     * A ringing call goes first, then the oldest.
     */
    @Query(
        """SELECT * FROM outbox
           WHERE (state IN ('pending', 'failed') AND next_attempt_at <= :now)
              OR (state = 'in_flight' AND updated_at <= :leaseExpiredBefore)
           ORDER BY ringing DESC, enqueued_at ASC LIMIT 1""",
    )
    suspend fun nextDue(now: Long, leaseExpiredBefore: Long): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE state IN ('pending', 'in_flight', 'failed') ORDER BY enqueued_at ASC LIMIT :limit")
    suspend fun queued(limit: Int): List<OutboxEntity>

    @Query("SELECT state, COUNT(*) AS n FROM outbox GROUP BY state")
    suspend fun countByState(): List<OutboxStateCount>

    @Query("SELECT MIN(next_attempt_at) FROM outbox WHERE state IN ('pending', 'failed')")
    suspend fun earliestAttemptAt(): Long?

    /** Queued rows older than the TTL give up (payload blanked). */
    @Query(
        """UPDATE outbox SET state = 'dead', payload = '', updated_at = :now
           WHERE state IN ('pending', 'in_flight', 'failed') AND enqueued_at < :cutoff""",
    )
    suspend fun expire(cutoff: Long, now: Long): Int

    /** Over the size cap: the oldest queued rows give up. */
    @Query(
        """UPDATE outbox SET state = 'dead', payload = '', updated_at = :now
           WHERE device_event_id IN (
               SELECT device_event_id FROM outbox WHERE state IN ('pending', 'in_flight', 'failed')
               ORDER BY enqueued_at ASC LIMIT :n)""",
    )
    suspend fun killOldestQueued(n: Int, now: Long): Int

    @Query("SELECT COUNT(*) FROM outbox WHERE state IN ('pending', 'in_flight', 'failed')")
    suspend fun queuedCount(): Int

    @Query("SELECT device_event_id FROM outbox WHERE server_event_id = :serverEventId LIMIT 1")
    suspend fun deviceEventIdFor(serverEventId: String): String?

    /** Consent revoked: every row of that source goes, whatever its state. */
    @Query("DELETE FROM outbox WHERE consent = :consent")
    suspend fun deleteConsent(consent: String): Int

    /** Sent and dead rows are only kept for the status UI and late-alert mapping. */
    @Query("DELETE FROM outbox WHERE state IN ('sent', 'dead') AND updated_at < :cutoff")
    suspend fun pruneFinished(cutoff: Long): Int

    @Query("DELETE FROM outbox WHERE state IN ('pending', 'in_flight', 'failed')")
    suspend fun deleteQueued(): Int

    @Query("DELETE FROM outbox")
    suspend fun deleteAll(): Int
}
