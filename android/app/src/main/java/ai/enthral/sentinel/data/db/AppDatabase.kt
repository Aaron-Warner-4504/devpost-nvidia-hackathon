package ai.enthral.sentinel.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        EventEntity::class,
        AssessmentEntity::class,
        FeedbackEntity::class,
        OutboxEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun assessmentDao(): AssessmentDao
    abstract fun feedbackDao(): FeedbackDao
    abstract fun outboxDao(): OutboxDao
}
