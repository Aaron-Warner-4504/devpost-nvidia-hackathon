package ai.enthral.sentinel

import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.api.EventIn
import ai.enthral.sentinel.data.api.EventResult
import ai.enthral.sentinel.data.api.OkHttpSentinelApi
import ai.enthral.sentinel.data.api.SentinelJson
import ai.enthral.sentinel.data.api.toDomain
import ai.enthral.sentinel.domain.CallDirection
import ai.enthral.sentinel.domain.FeedbackLabel
import ai.enthral.sentinel.domain.Source
import ai.enthral.sentinel.domain.Tier
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Exercises the client against docs/api-contract.md. Bodies below are the contract's own examples. */
class ApiClientTest {
    private lateinit var server: MockWebServer
    private var token: String? = "tok-secret-123"
    private lateinit var api: OkHttpSentinelApi

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        api = OkHttpSentinelApi(server.url("/").toString(), OkHttpClient(), tokenProvider = { token })
    }

    @After fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val eventResultExample = """
        {"event_id":"evt_123","duplicate":false,"alert":{"id":"alr_123","event_id":"evt_123","tier":"high",
        "headline":"Likely bank-impersonation scam",
        "why":[{"event_id":"evt_120","ts":"2026-10-17T10:00:00Z","text":"Same sender asked for an OTP yesterday"}],
        "next_step":"Do not click the link or share any OTP. Call your bank on the number printed on your card.",
        "signals":["urgency","lookalike_domain","credential_or_otp_ask"],"created_at":"2026-10-17T10:00:00Z"}}
    """.trimIndent()

    private val sms = EventIn(
        deviceEventId = "dev-1", source = Source.Sms, ts = "2026-10-17T10:00:00Z", sender = "+911234567890",
        text = "Your account is blocked, click http://x.co", triageScore = 0.8, lang = "en", isContact = false,
    )

    @Test fun `register parses the device and sends no Authorization header`() = runTest {
        server.enqueue(json("""{"device_id":"dev_123","token":"example-token"}"""))
        val r = api.register()
        assertEquals("example-token", (r as ApiResult.Success).value.token)
        assertEquals("dev_123", r.value.deviceId)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/devices/register", req.path)
        assertNull(req.getHeader("Authorization"))
    }

    @Test fun `event request carries the bearer token and exact contract field names`() = runTest {
        server.enqueue(json(eventResultExample))
        val r = api.postEvent(sms) as ApiResult.Success
        val req = server.takeRequest()
        assertEquals("/v1/events", req.path)
        assertEquals("Bearer tok-secret-123", req.getHeader("Authorization"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(
            setOf("device_event_id", "source", "ts", "sender", "text", "triage_score", "lang", "is_contact"),
            body.keys,
        )
        assertEquals("sms", body["source"]!!.jsonPrimitive.content)
        assertEquals("0.8", body["triage_score"]!!.jsonPrimitive.content)
        assertEquals("false", body["is_contact"]!!.jsonPrimitive.content)
        assertEquals(Tier.High, r.value.alert!!.tier)
    }

    @Test fun `call events serialize the nested call object`() = runTest {
        server.enqueue(json("""{"event_id":"evt_9","duplicate":false,"alert":null}"""))
        val call = ai.enthral.sentinel.data.api.CallMeta("+911234567890", 42, false, CallDirection.In)
        val r = api.postEvent(EventIn("dev-2", Source.Call, "2026-10-17T10:00:00Z", call = call, triageScore = 0.4))
        assertNull((r as ApiResult.Success).value.alert)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val c = body["call"]!!.jsonObject
        assertEquals("+911234567890", c["number"]!!.jsonPrimitive.content)
        assertEquals("42", c["duration_s"]!!.jsonPrimitive.content)
        assertEquals("in", c["direction"]!!.jsonPrimitive.content)
        assertFalse("omitted optionals must not be sent as null", body.containsKey("sender"))
    }

    @Test fun `contract EventResult example round-trips unchanged and maps to the domain`() {
        val parsed = SentinelJson.decodeFromString(EventResult.serializer(), eventResultExample)
        val reencoded = SentinelJson.encodeToJsonElement(EventResult.serializer(), parsed)
        assertEquals(Json.parseToJsonElement(eventResultExample), reencoded)
        val alert = parsed.alert!!.toDomain()
        assertEquals(Instant.parse("2026-10-17T10:00:00Z"), alert.createdAt)
        assertEquals("evt_120", alert.why.single().eventId)
    }

    @Test fun `duplicate result is a success with no alert`() = runTest {
        server.enqueue(json("""{"event_id":"evt_1","duplicate":true,"alert":null}"""))
        val r = (api.postEvent(sms) as ApiResult.Success).value
        assertTrue(r.duplicate)
        assertNull(r.alert)
    }

    @Test fun `unknown response fields are ignored`() = runTest {
        server.enqueue(json("""{"device_id":"d","token":"t","brand_new_field":{"x":1}}"""))
        assertTrue(api.register() is ApiResult.Success)
    }

    @Test fun `401 maps to HttpError with detail`() = runTest {
        server.enqueue(json("""{"detail":"invalid token"}""", 401))
        val r = api.getAlerts() as ApiResult.HttpError
        assertEquals(401, r.status)
        assertEquals("invalid token", r.detail)
        assertTrue(r.isUnauthorized)
    }

    @Test fun `422 validation list is flattened to the messages`() = runTest {
        server.enqueue(json(
            """{"detail":[{"loc":["body","text"],"msg":"String should have at most 10000 characters","type":"string_too_long"},
               {"loc":["body","sender"],"msg":"too long","type":"x"}]}""",
            422,
        ))
        val r = api.check(sms) as ApiResult.HttpError
        assertEquals(422, r.status)
        assertEquals("String should have at most 10000 characters; too long", r.detail)
    }

    @Test fun `422 string detail and 429 are parsed`() = runTest {
        server.enqueue(json("""{"detail":"no text could be found in the image"}""", 422))
        server.enqueue(json("""{"detail":"rate limit exceeded"}""", 429))
        assertEquals("no text could be found in the image", (api.check(sms) as ApiResult.HttpError).detail)
        val limited = api.check(sms) as ApiResult.HttpError
        assertTrue(limited.isRateLimited)
        assertEquals("rate limit exceeded", limited.detail)
    }

    @Test fun `non-json error body still yields HttpError with null detail`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))
        val r = api.getMe() as ApiResult.HttpError
        assertEquals(502, r.status)
        assertNull(r.detail)
    }

    @Test fun `dropped connection becomes NetworkError and never throws`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertTrue(api.postEvent(sms) is ApiResult.NetworkError)
    }

    @Test fun `server down becomes NetworkError`() = runTest {
        server.shutdown()
        val r = api.register()
        assertTrue(r is ApiResult.NetworkError)
        // only the exception class name is kept, never message or URL
        assertFalse((r as ApiResult.NetworkError).reason.orEmpty().contains("localhost"))
    }

    @Test fun `malformed success body does not throw`() = runTest {
        server.enqueue(json("""{"nope":true}"""))
        val r = api.register() as ApiResult.HttpError
        assertEquals("malformed response", r.detail)
    }

    @Test fun `unknown tier value does not throw`() = runTest {
        server.enqueue(json("""[{"id":"a","event_id":"e","tier":"apocalyptic","headline":"h","why":[],"next_step":"n","signals":[],"created_at":"2026-10-17T10:00:00Z"}]"""))
        assertTrue(api.getAlerts() is ApiResult.HttpError)
    }

    @Test fun `multipart check has the image part, fields, auth and path`() = runTest {
        server.enqueue(json(eventResultExample))
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
        val r = api.checkImage(png, "image/png", deviceEventId = "dev-img-1", ts = "2026-10-17T10:00:00Z",
            lang = "hi", triageScore = 0.5)
        assertTrue(r is ApiResult.Success)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/check", req.path)
        assertEquals("Bearer tok-secret-123", req.getHeader("Authorization"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        val raw = req.body.readByteString().utf8()
        assertTrue(raw.contains("""name="image"; filename="screenshot.png""""))
        assertTrue(raw.contains("Content-Type: image/png"))
        assertTrue(raw.contains("""name="device_event_id""""))
        assertTrue(raw.contains("dev-img-1"))
        assertTrue(raw.contains("""name="triage_score""""))
        assertTrue(raw.contains("""name="lang""""))
    }

    @Test fun `multipart omits optional fields that are null`() = runTest {
        server.enqueue(json(eventResultExample))
        api.checkImage(byteArrayOf(1, 2, 3), "image/jpeg")
        val raw = server.takeRequest().body.readByteString().utf8()
        assertTrue(raw.contains("""filename="screenshot.jpg""""))
        assertFalse(raw.contains("device_event_id"))
        assertFalse(raw.contains("triage_score"))
    }

    @Test fun `alerts since is sent as an encoded query parameter`() = runTest {
        server.enqueue(json("[]"))
        api.getAlerts(since = "2026-10-17T10:00:00+00:00")
        assertEquals("/v1/alerts?since=2026-10-17T10%3A00%3A00%2B00%3A00", server.takeRequest().path)
    }

    @Test fun `feedback and fcm use the contract paths and bodies`() = runTest {
        repeat(2) { server.enqueue(json("""{"ok":true}""")) }
        api.postFeedback("alr_1", FeedbackLabel.Scam)
        assertEquals("/v1/alerts/alr_1/feedback", server.takeRequest().also {
            assertEquals("""{"label":"scam"}""", it.body.readUtf8())
        }.path)
        api.registerFcmToken("fcm-token")
        assertEquals("""{"token":"fcm-token"}""", server.takeRequest().body.readUtf8())
    }

    @Test fun `family endpoints`() = runTest {
        server.enqueue(json("""{"code":"K7M2QX","expires_at":"2026-10-17T10:10:00Z"}"""))
        server.enqueue(json("""{"id":"fam_123","protected_name":null}"""))
        server.enqueue(json("""{"guardians":[{"id":"fam_123","name":"Ravi","linked_at":"2026-10-17T10:05:00Z"}],"protecting":[{"id":"fam_456","linked_at":"2026-10-17T11:00:00Z"}]}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals("K7M2QX", (api.createFamilyInvite(true) as ApiResult.Success).value.code)
        assertEquals("""{"consent":true}""", server.takeRequest().body.readUtf8())
        assertEquals("fam_123", (api.acceptFamilyInvite("K7M2QX", "Ravi", "fcm") as ApiResult.Success).value.id)
        val accept = server.takeRequest()
        assertEquals("/v1/family/accept", accept.path)
        assertEquals("""{"code":"K7M2QX","name":"Ravi","fcm_token":"fcm"}""", accept.body.readUtf8())
        val contacts = (api.getFamilyContacts() as ApiResult.Success).value
        assertEquals("Ravi", contacts.guardians.single().name)
        assertEquals("fam_456", contacts.protecting.single().id)
        server.takeRequest()
        assertEquals(ApiResult.Success(Unit), api.unlinkFamily("fam_123"))
        val del = server.takeRequest()
        assertEquals("DELETE", del.method)
        assertEquals("/v1/family/contacts/fam_123", del.path)
    }

    @Test fun `consent and me and delete`() = runTest {
        server.enqueue(json("""{"source":"sms","granted":true}"""))
        server.enqueue(json("""{"id":"usr_1","consents":{"sms":true,"call":false}}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue((api.setConsent(Source.Sms, true) as ApiResult.Success).value.granted)
        assertEquals("""{"source":"sms","granted":true}""", server.takeRequest().body.readUtf8())
        assertEquals(false, (api.getMe() as ApiResult.Success).value.consents["call"])
        server.takeRequest()
        assertEquals(ApiResult.Success(Unit), api.deleteMe())
        assertEquals("DELETE", server.takeRequest().also { assertEquals("/v1/me", it.path) }.method)
    }

    @Test fun `requests without a stored token omit Authorization`() = runTest {
        token = null
        server.enqueue(json("[]"))
        api.getAlerts()
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `alerts list parses and sorts why items by time`() = runTest {
        server.enqueue(json("""[{"id":"alr_1","event_id":"e","tier":"critical","headline":"h",
            "why":[{"event_id":"b","ts":"2026-10-17T12:00:00Z","text":"later"},{"event_id":"a","ts":"2026-10-17T09:00:00Z","text":"earlier"}],
            "next_step":"n","signals":["s"],"created_at":"2026-10-17T12:00:00Z"}]"""))
        val alert = (api.getAlerts() as ApiResult.Success).value.single().toDomain()
        assertEquals(listOf("earlier", "later"), alert.why.map { it.text })
        assertTrue(alert.tier.interrupts)
    }
}
