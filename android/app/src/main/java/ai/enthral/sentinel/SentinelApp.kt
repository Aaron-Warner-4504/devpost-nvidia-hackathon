package ai.enthral.sentinel

import android.app.Application

class SentinelApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // 15-minute catch-up (outbox flush, call log, missed alerts). KEEP: re-arming is free. Never in unit tests.
        runCatching { container.capture.scheduler.schedulePeriodic() }
    }
}
