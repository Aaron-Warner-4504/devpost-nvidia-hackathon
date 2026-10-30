package ai.enthral.sentinel

import ai.enthral.sentinel.data.db.AssessmentDao
import ai.enthral.sentinel.data.db.AssessmentEntity
import ai.enthral.sentinel.data.db.EventDao
import ai.enthral.sentinel.data.db.EventEntity
import ai.enthral.sentinel.data.db.FeedbackDao
import ai.enthral.sentinel.data.db.FeedbackEntity
import ai.enthral.sentinel.data.db.RoomLocalStore
import ai.enthral.sentinel.domain.Tier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStoreTest {

    private class MockEventDao : EventDao {
        val map = mutableMapOf<String, EventEntity>()

        override suspend fun insert(event: EventEntity): Long {
            map[event.stableEventId] = event
            return map.size.toLong()
        }

        override suspend fun getByStableId(stableId: String): EventEntity? = map[stableId]

        override suspend fun getPending(): List<EventEntity> =
            map.values.filter { it.syncState == "pending" }

        override suspend fun updateSyncState(stableId: String, syncState: String) {
            val cur = map[stableId] ?: return
            map[stableId] = cur.copy(syncState = syncState)
        }

        override suspend fun deleteOlderThan(cutoffMs: Long): Int {
            val toRemove = map.filter { it.value.createdAt < cutoffMs }.keys
            toRemove.forEach { map.remove(it) }
            return toRemove.size
        }

        override suspend fun deleteAll(): Int {
            val count = map.size
            map.clear()
            return count
        }

        override suspend fun count(): Int = map.size
    }

    private class MockAssessmentDao : AssessmentDao {
        val map = mutableMapOf<String, AssessmentEntity>()

        override suspend fun insertOrUpdate(assessment: AssessmentEntity): Long {
            map[assessment.stableEventId] = assessment
            return map.size.toLong()
        }

        override suspend fun getByStableId(stableId: String): AssessmentEntity? = map[stableId]

        override suspend fun getLatest(limit: Int): List<AssessmentEntity> =
            map.values.sortedByDescending { it.createdAt }.take(limit)

        override suspend fun deleteOlderThan(cutoffMs: Long): Int {
            val toRemove = map.filter { it.value.createdAt < cutoffMs }.keys
            toRemove.forEach { map.remove(it) }
            return toRemove.size
        }

        override suspend fun deleteAll(): Int {
            val count = map.size
            map.clear()
            return count
        }

        override suspend fun count(): Int = map.size
    }

    private class MockFeedbackDao : FeedbackDao {
        val map = mutableMapOf<String, FeedbackEntity>()

        override suspend fun insertOrUpdate(feedback: FeedbackEntity): Long {
            map[feedback.alertId] = feedback
            return map.size.toLong()
        }

        override suspend fun getByAlertId(alertId: String): FeedbackEntity? = map[alertId]

        override suspend fun getAll(): List<FeedbackEntity> = map.values.toList()

        override suspend fun deleteAll(): Int {
            val count = map.size
            map.clear()
            return count
        }

        override suspend fun count(): Int = map.size
    }

    private class FakeAppDb(
        val events: MockEventDao = MockEventDao(),
        val assessments: MockAssessmentDao = MockAssessmentDao(),
        val feedback: MockFeedbackDao = MockFeedbackDao(),
    ) : ai.enthral.sentinel.data.db.AppDatabase() {
        override fun eventDao(): EventDao = events
        override fun assessmentDao(): AssessmentDao = assessments
        override fun feedbackDao(): FeedbackDao = feedback
        override fun outboxDao(): ai.enthral.sentinel.data.db.OutboxDao = throw UnsupportedOperationException()
        override fun clearAllTables() {}
        override fun createInvalidationTracker(): androidx.room.InvalidationTracker {
            return androidx.room.InvalidationTracker(this, emptyMap(), emptyMap(), "events", "assessments", "feedback")
        }
        override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper {
            throw UnsupportedOperationException()
        }
    }

    @Test
    fun `records event and assessment then marks synced`() = runTest {
        val db = FakeAppDb()
        val store = RoomLocalStore(db)

        store.recordEvent(
            source = "sms",
            ts = 1000L,
            redactedText = "hello [OTP]",
            senderHash = "abc123",
            stableEventId = "evt_001",
        )

        val pending = store.getPendingEvents()
        assertEquals(1, pending.size)
        assertEquals("evt_001", pending[0].stableEventId)

        store.recordAssessment(
            stableEventId = "evt_001",
            tier = Tier.Caution,
            signals = listOf("unknown_sender"),
            stages = setOf("contact"),
            explanation = "offline check, fewer signals",
            origin = "local",
        )

        val assessment = store.getAssessment("evt_001")
        assertNotNull(assessment)
        assertEquals("caution", assessment?.tier)
        assertEquals("local", assessment?.origin)

        store.markEventSynced("evt_001")
        val pendingAfter = store.getPendingEvents()
        assertEquals(0, pendingAfter.size)
    }

    @Test
    fun `server assessment upgrades tier and never downgrades`() = runTest {
        val db = FakeAppDb()
        val store = RoomLocalStore(db)

        // Local tier is High
        store.recordAssessment(
            stableEventId = "evt_002",
            tier = Tier.High,
            signals = listOf("urgency", "credential_or_otp_ask"),
            stages = setOf("pressure", "ask"),
            explanation = "offline check, fewer signals",
            origin = "local",
        )

        // Server returns Caution: tier must NOT downgrade from High to Caution
        store.recordAssessment(
            stableEventId = "evt_002",
            tier = Tier.Caution,
            signals = listOf("urgency"),
            stages = setOf("pressure"),
            explanation = "server review",
            origin = "server",
        )

        val assessment = store.getAssessment("evt_002")
        assertEquals("high", assessment?.tier)
        assertEquals("server", assessment?.origin)

        // Server returns Critical: upgrades to Critical
        store.recordAssessment(
            stableEventId = "evt_002",
            tier = Tier.Critical,
            signals = listOf("urgency", "credential_or_otp_ask", "story_progression"),
            stages = setOf("contact", "trust", "pressure", "ask"),
            explanation = "multi-step scam confirmed",
            origin = "server",
        )

        val upgraded = store.getAssessment("evt_002")
        assertEquals("critical", upgraded?.tier)
    }

    @Test
    fun `delete everything wipes all tables`() = runTest {
        val db = FakeAppDb()
        val store = RoomLocalStore(db)

        store.recordEvent("sms", 1000L, "text", null, "evt_003")
        store.recordAssessment("evt_003", Tier.Info, emptyList(), emptySet(), "ok")
        store.recordFeedback("alert_1", "scam")

        assertEquals(1, db.events.count())
        assertEquals(1, db.assessments.count())
        assertEquals(1, db.feedback.count())

        store.deleteEverything()

        assertEquals(0, db.events.count())
        assertEquals(0, db.assessments.count())
        assertEquals(0, db.feedback.count())
    }
}
