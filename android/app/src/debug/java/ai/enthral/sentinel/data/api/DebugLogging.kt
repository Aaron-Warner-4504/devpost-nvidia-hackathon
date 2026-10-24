package ai.enthral.sentinel.data.api

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Debug-only HTTP logging. Level HEADERS on purpose: bodies carry message text and (on register) the device
 * token, so they are never logged. Authorization is redacted as defence in depth.
 */
fun debugLoggingInterceptor(sink: (String) -> Unit = { Log.d("SentinelHttp", it) }): HttpLoggingInterceptor =
    HttpLoggingInterceptor { sink(it) }.apply {
        level = HttpLoggingInterceptor.Level.HEADERS
        redactHeader("Authorization")
        redactHeader("Cookie")
        redactHeader("Set-Cookie")
    }

internal fun addDebugLogging(builder: OkHttpClient.Builder) {
    builder.addInterceptor(debugLoggingInterceptor())
}
