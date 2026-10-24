package ai.enthral.sentinel

import ai.enthral.sentinel.data.api.OkHttpSentinelApi
import ai.enthral.sentinel.data.api.debugLoggingInterceptor
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The debug logging interceptor must never leak the device token or message bodies. */
class HttpLoggingTest {
    @Test fun `token and bodies never appear in debug logs`() = runTest {
        val lines = mutableListOf<String>()
        val server = MockWebServer().also { it.start() }
        try {
            val client = OkHttpClient.Builder().addInterceptor(debugLoggingInterceptor { lines += it }).build()
            val api = OkHttpSentinelApi(server.url("/").toString(), client, tokenProvider = { "tok-secret-123" })
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"device_id":"dev_1","token":"issued-secret-456"}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("[]"))
            api.register()
            api.getAlerts()
        } finally {
            server.shutdown()
        }
        val log = lines.joinToString("\n")
        assertTrue("logger should have run", log.contains("/v1/alerts"))
        assertFalse(log.contains("tok-secret-123"))
        assertFalse(log.contains("issued-secret-456"))
        assertTrue(log.contains("Authorization: ██") || log.contains("Authorization: **"))
    }
}
