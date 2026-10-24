package ai.enthral.sentinel.data.api

import okhttp3.OkHttpClient

/** Release builds never log HTTP traffic (the logging interceptor is not even on the classpath). */
@Suppress("UNUSED_PARAMETER")
internal fun addDebugLogging(builder: OkHttpClient.Builder) = Unit
