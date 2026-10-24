package ai.enthral.sentinel

import android.app.Application

class SentinelApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
