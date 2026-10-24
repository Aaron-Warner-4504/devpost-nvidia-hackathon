package ai.enthral.sentinel.data.api

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Builds the app's OkHttp client. Debug builds add redacted header logging (see src/debug); release has no logging. */
object HttpClientFactory {
    fun create(enableDebugLogging: Boolean): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS) // /check runs the full agent and vision model
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .apply { if (enableDebugLogging) addDebugLogging(this) }
        .build()
}

// addDebugLogging(builder) lives in src/debug (real, redacted logging) and src/release (no-op).
