package ai.enthral.sentinel.data.db

import ai.enthral.sentinel.domain.Tier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface LocalStore {
    suspend fun recordEvent(
        source: String,
        ts: Long,
        redactedText: String,
        senderHash: String?,
        stableEventId: String,
        syncState: String = "pending",
    ): Long

    suspend fun recordAssessment(
        stableEventId: String,
        tier: Tier,
        signals: List<String>,
        stages: Set<String>,
        explanation: String,
        origin: String = "local",
    ): Long

    suspend fun getAssessment(stableEventId: String): AssessmentEntity?

    suspend fun getPendingEvents(): List<EventEntity>

    suspend fun markEventSynced(stableEventId: String)

    suspend fun recordFeedback(alertId: String, label: String)

    suspend fun applyRetention(maxAgeDays: Int = 30): Int

    suspend fun deleteEverything()
}

class RoomLocalStore(
    private val database: suspend () -> AppDatabase,
    /** Delete-everything hook after the tables are emptied (production: delete the file and the key). */
    private val onWipe: suspend () -> Unit = {},
) : LocalStore {
    constructor(db: AppDatabase) : this({ db })

    private val tierRank = mapOf(
        "info" to 0,
        "caution" to 1,
        "high" to 2,
        "critical" to 3,
    )

    override suspend fun recordEvent(
        source: String,
        ts: Long,
        redactedText: String,
        senderHash: String?,
        stableEventId: String,
        syncState: String,
    ): Long = withContext(Dispatchers.IO) {
        val entity = EventEntity(
            source = source,
            ts = ts,
            redactedText = redactedText,
            senderHash = senderHash,
            stableEventId = stableEventId,
            syncState = syncState,
        )
        database().eventDao().insert(entity)
    }

    override suspend fun recordAssessment(
        stableEventId: String,
        tier: Tier,
        signals: List<String>,
        stages: Set<String>,
        explanation: String,
        origin: String,
    ): Long = withContext(Dispatchers.IO) {
        val existing = database().assessmentDao().getByStableId(stableEventId)
        val newTierStr = tier.name.lowercase()

        // Server results later supersede (upgrade-only)
        if (existing != null) {
            val existingRank = tierRank[existing.tier.lowercase()] ?: 0
            val newRank = tierRank[newTierStr] ?: 0

            // A server review always marks the row as reviewed (origin), but a lower server tier never replaces the
            // higher tier or the explanation that goes with it: upgrade-only.
            if (origin == "server" || newRank >= existingRank) {
                val effectiveTier = if (newRank >= existingRank) newTierStr else existing.tier
                val effectiveExplanation = if (newRank >= existingRank) explanation else existing.explanation
                val mergedSignals = (existing.signals.split(",").filter { it.isNotBlank() } + signals).distinct().joinToString(",")
                val mergedStages = (existing.stages.split(",").filter { it.isNotBlank() } + stages).distinct().joinToString(",")

                val updated = existing.copy(
                    tier = effectiveTier,
                    signals = mergedSignals,
                    stages = mergedStages,
                    explanation = effectiveExplanation,
                    origin = origin,
                )
                return@withContext database().assessmentDao().insertOrUpdate(updated)
            } else {
                return@withContext existing.id
            }
        }

        val entity = AssessmentEntity(
            stableEventId = stableEventId,
            tier = newTierStr,
            signals = signals.joinToString(","),
            stages = stages.joinToString(","),
            explanation = explanation,
            origin = origin,
        )
        database().assessmentDao().insertOrUpdate(entity)
    }

    override suspend fun getAssessment(stableEventId: String): AssessmentEntity? = withContext(Dispatchers.IO) {
        database().assessmentDao().getByStableId(stableEventId)
    }

    override suspend fun getPendingEvents(): List<EventEntity> = withContext(Dispatchers.IO) {
        database().eventDao().getPending()
    }

    override suspend fun markEventSynced(stableEventId: String) = withContext(Dispatchers.IO) {
        database().eventDao().updateSyncState(stableEventId, "synced")
    }

    override suspend fun recordFeedback(alertId: String, label: String) {
        withContext(Dispatchers.IO) { database().feedbackDao().insertOrUpdate(FeedbackEntity(alertId = alertId, label = label)) }
    }

    override suspend fun applyRetention(maxAgeDays: Int): Int = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(maxAgeDays.toLong())
        val deletedEvents = database().eventDao().deleteOlderThan(cutoff)
        val deletedAssessments = database().assessmentDao().deleteOlderThan(cutoff)
        deletedEvents + deletedAssessments
    }

    override suspend fun deleteEverything() = withContext(Dispatchers.IO) {
        database().eventDao().deleteAll()
        database().assessmentDao().deleteAll()
        database().feedbackDao().deleteAll()
        onWipe()
    }
}
