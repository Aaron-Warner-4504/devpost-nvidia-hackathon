package ai.enthral.sentinel

import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.api.OkHttpSentinelApi
import ai.enthral.sentinel.data.demo.DemoKey
import ai.enthral.sentinel.data.demo.DemoOutcome
import ai.enthral.sentinel.data.demo.DemoTimeline
import ai.enthral.sentinel.data.demo.NetworkDemoRepository
import ai.enthral.sentinel.data.repo.AlertsRepository
import ai.enthral.sentinel.data.repo.ApiDeviceSession
import ai.enthral.sentinel.data.repo.NetworkAccountRepository
import ai.enthral.sentinel.data.repo.NetworkAlertsRepository
import ai.enthral.sentinel.data.repo.NetworkCheckRepository
import ai.enthral.sentinel.data.store.SecureTokenStore
import ai.enthral.sentinel.domain.ProtectionStatus
import ai.enthral.sentinel.domain.SourceStatus
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.domain.computeProtection
import ai.enthral.sentinel.testing.FakeAlerts
import ai.enthral.sentinel.testing.FakeNotifier
import ai.enthral.sentinel.testing.FakeSettings
import ai.enthral.sentinel.testing.Samples
import ai.enthral.sentinel.testing.testTokenStore
import ai.enthral.sentinel.ui.common.RelativeTime
import ai.enthral.sentinel.ui.common.relativeTime
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun alertJson(id: String, event: String, tier: String) =
    """{"id":"$id","event_id":"$event","tier":"$tier","headline":"h $id","why":[],"next_step":"n","signals":["urgency"],"created_at":"2026-10-24T09:00:00Z"}"""

private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject
private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

class RepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var server: MockWebServer
    private lateinit var tokens: SecureTokenStore
    private lateinit var api: OkHttpSentinelApi
    private lateinit var session: ApiDeviceSession
    private val settings = FakeSettings()
    private val notifier = FakeNotifier()

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        tokens = testTokenStore(File(tmp.newFolder(), "t.preferences_pb"), scope)
        api = OkHttpSentinelApi(server.url("/").toString(), OkHttpClient(), tokenProvider = { tokens.token() })
        session = ApiDeviceSession(api, tokens)
    }

    @After fun tearDown() { server.shutdown(); scope.cancel() }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val registered = """{"device_id":"d1","token":"tok-new"}"""

    // --- session -------------------------------------------------------------------------------------
    @Test fun `authed registers once on first use and reuses the token`() = runTest {
        server.enqueue(json(registered))
        server.enqueue(json("[]"))
        server.enqueue(json("[]"))
        assertTrue(session.authed { api.getAlerts() } is ApiResult.Success)
        assertTrue(session.authed { api.getAlerts() } is ApiResult.Success)
        assertEquals(3, server.requestCount)
        assertEquals("/v1/devices/register", server.takeRequest().path)
        assertEquals("Bearer tok-new", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `a 401 re-registers exactly once and retries the call`() = runTest {
        tokens.save("stale")
        server.enqueue(json("""{"detail":"invalid token"}""", 401))
        server.enqueue(json(registered))
        server.enqueue(json("[]"))
        assertTrue(session.authed { api.getAlerts() } is ApiResult.Success)
        assertEquals(listOf("/v1/alerts", "/v1/devices/register", "/v1/alerts"), (1..3).map { server.takeRequest().path })
        assertEquals("tok-new", tokens.token())
    }

    @Test fun `a second 401 is returned instead of looping`() = runTest {
        tokens.save("stale")
        server.enqueue(json("{}", 401)); server.enqueue(json(registered)); server.enqueue(json("{}", 401))
        val r = session.authed { api.getAlerts() }
        assertTrue(r is ApiResult.HttpError && r.status == 401)
        assertEquals(3, server.requestCount)
    }

    // --- alerts + notifications -------------------------------------------------------------------------
    @Test fun `first refresh is a silent baseline, later refreshes notify each new interrupting alert once`() = runTest {
        tokens.save("t")
        val repo: AlertsRepository = NetworkAlertsRepository(api, session, settings, notifier)
        server.enqueue(json("[${alertJson("old", "e0", "critical")}]"))
        repo.refresh()
        assertTrue("history is not announced", notifier.notified.isEmpty())

        val next = "[${alertJson("a2", "e2", "critical")},${alertJson("a3", "e3", "caution")},${alertJson("a4", "e4", "high")},${alertJson("old", "e0", "critical")}]"
        server.enqueue(json(next)); server.enqueue(json(next))
        repo.refresh()
        assertEquals(listOf("a4", "a2").sorted(), notifier.notified.map { it.id }.sorted())
        assertTrue("caution never interrupts", notifier.notified.none { it.tier == Tier.Caution })
        repo.refresh()
        assertEquals("no repeat for already notified alerts", 2, notifier.notified.size)
    }

    @Test fun `get falls back to one refresh and finds remembered check results`() = runTest {
        tokens.save("t")
        val repo = NetworkAlertsRepository(api, session, settings, notifier)
        server.enqueue(json("[${alertJson("a1", "e1", "high")}]"))
        assertEquals("a1", repo.get("a1")?.id)
        assertNull(repo.get("zzz").also { })
        repo.remember(Samples.alert("info_1", Tier.Info))
        assertEquals("info_1", repo.get("info_1")?.id)
    }

    @Test fun `a check result is never announced later as a notification`() = runTest {
        tokens.save("t")
        val repo = NetworkAlertsRepository(api, session, settings, notifier)
        server.enqueue(json("[]"))
        repo.refresh() // baseline
        repo.remember(Samples.alert("chk_high", Tier.High)) // user-initiated check
        val list = "[${alertJson("chk_high", "e1", "high")}]"
        server.enqueue(json(list))
        repo.refresh()
        assertTrue("the user already saw this one", notifier.notified.isEmpty())
    }

    // --- check ---------------------------------------------------------------------------------------
    @Test fun `check always returns the alert and remembers it for the detail screen`() = runTest {
        tokens.save("t")
        val alerts = FakeAlerts()
        val repo = NetworkCheckRepository(api, session, alerts, clock = { Samples.now }, newId = { "chk-fixed" })
        server.enqueue(json("""{"event_id":"e","duplicate":false,"alert":${alertJson("c1", "e", "info")}}"""))
        val r = repo.checkText("hello", "  ", "en") as ApiResult.Success
        assertEquals(Tier.Info, r.value.tier)
        assertEquals("c1", alerts.remembered.single().id)
        val body = obj(server.takeRequest().body.readUtf8())
        assertEquals("chk-fixed", body.str("device_event_id"))
        assertEquals("share", body.str("source"))
        assertTrue("blank sender is omitted", "sender" !in body)
        assertEquals("en", body.str("lang"))
    }

    // --- account -------------------------------------------------------------------------------------
    @Test fun `delete everything calls the API then wipes local state`() = runTest {
        tokens.save("t")
        settings.done.value = true
        val alerts = FakeAlerts(listOf(Samples.alert()))
        server.enqueue(MockResponse().setResponseCode(204))
        val r = NetworkAccountRepository(api, session, settings, alerts).deleteEverything()
        assertTrue(r is ApiResult.Success)
        assertEquals("DELETE", server.takeRequest().method)
        assertNull(tokens.token())
        assertEquals(1, settings.cleared)
        assertTrue(alerts.cleared)
    }

    @Test fun `delete everything treats an already-deleted account as success`() = runTest {
        tokens.save("t")
        server.enqueue(json("""{"detail":"invalid token"}""", 401))
        assertTrue(NetworkAccountRepository(api, session, settings, FakeAlerts()).deleteEverything() is ApiResult.Success)
        assertEquals(1, settings.cleared)
    }

    @Test fun `an offline delete changes nothing so it can be retried`() = runTest {
        tokens.save("t")
        server.shutdown()
        val r = NetworkAccountRepository(api, session, settings, FakeAlerts()).deleteEverything()
        assertTrue(r is ApiResult.NetworkError)
        assertEquals("t", tokens.token())
        assertEquals(0, settings.cleared)
    }

    @Test fun `a never-registered install deletes locally without calling the server`() = runTest {
        NetworkAccountRepository(api, session, settings, FakeAlerts()).deleteEverything()
        assertEquals(0, server.requestCount)
        assertEquals(1, settings.cleared)
    }

    // --- demo ----------------------------------------------------------------------------------------
    @Test fun `demo uses stable ids and is idempotent on a re-run`() = runTest {
        tokens.save("t")
        val seen = mutableSetOf<String>()
        val posted = mutableListOf<JsonObject>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/v1/events" -> {
                    val b = obj(request.body.readUtf8()); posted += b
                    val id = b.str("device_event_id")
                    val first = seen.add(id)
                    val tier = if (id.endsWith("whatsapp")) "critical" else "caution"
                    json("""{"event_id":"evt-$id","duplicate":${!first},"alert":${if (first) alertJson("alr-$id", "evt-$id", tier) else "null"}}""")
                }
                request.path!!.startsWith("/v1/alerts") ->
                    json("[${seen.joinToString(",") { alertJson("alr-$it", "evt-$it", if (it.endsWith("whatsapp")) "critical" else "caution") }}]")
                else -> json("{}", 404)
            }
        }
        val alerts = NetworkAlertsRepository(api, session, settings, notifier)
        val demo = NetworkDemoRepository(api, session, alerts, clock = { Samples.now })

        val first = demo.run().toList().last()
        val second = demo.run().toList().last()

        assertEquals(listOf("demo-d1-courier-sms", "demo-d2-call", "demo-d3-whatsapp"), posted.take(3).map { it.str("device_event_id") })
        assertEquals(posted.take(3).map { it.str("device_event_id") }, posted.drop(3).map { it.str("device_event_id") })
        assertEquals(listOf(DemoKey.CourierSms, DemoKey.UnknownCall, DemoKey.WhatsappUpi), first.map { it.key })
        assertEquals(Tier.Critical, ((first[2].outcome) as DemoOutcome.Flagged).alert.tier)
        // re-run: the server answers duplicate/no alert, yet the stepper still shows each tier from history
        assertEquals(Tier.Critical, ((second[2].outcome) as DemoOutcome.Flagged).alert.tier)
        assertEquals(Tier.Caution, ((second[0].outcome) as DemoOutcome.Flagged).alert.tier)
    }

    @Test fun `demo timestamps are 09 00 UTC on today minus 3, 2 and 1 days`() {
        val events = DemoTimeline.events(Instant.parse("2026-10-24T20:15:00Z"))
        assertEquals(listOf("2026-10-21T09:00:00Z", "2026-10-22T09:00:00Z", "2026-10-23T09:00:00Z"), events.map { it.ts })
        assertEquals("+919876543210", events[2].sender)
        assertEquals(240, events[1].call!!.durationS)
    }

    @Test fun `demo stops at the first failing step`() = runTest {
        tokens.save("t")
        server.enqueue(json("""{"detail":"boom"}""", 500))
        val demo = NetworkDemoRepository(api, session, NetworkAlertsRepository(api, session, settings, notifier), clock = { Samples.now })
        val last = demo.run().toList().last()
        assertTrue(last[0].outcome is DemoOutcome.Failed)
        assertEquals(DemoOutcome.Pending, last[1].outcome)
        assertEquals(1, server.requestCount)
    }
}

class DomainRulesTest {
    private val on = SourceStatus.On; private val off = SourceStatus.Off; private val needs = SourceStatus.NeedsPermission

    @Test fun `protection status rules`() {
        assertEquals(ProtectionStatus.SetupIncomplete, computeProtection(off, off, paused = false, offline = false))
        assertEquals(ProtectionStatus.SetupIncomplete, computeProtection(needs, off, false, false))
        assertEquals(ProtectionStatus.Protected, computeProtection(on, off, false, false))
        assertEquals(ProtectionStatus.Protected, computeProtection(on, on, false, false))
        assertEquals(ProtectionStatus.Limited, computeProtection(on, needs, false, false))
        assertEquals(ProtectionStatus.Paused, computeProtection(on, on, paused = true, offline = false))
        assertEquals(ProtectionStatus.Offline, computeProtection(on, on, false, offline = true))
        assertEquals(ProtectionStatus.Paused, computeProtection(on, needs, paused = true, offline = true))
    }

    @Test fun `relative time buckets`() {
        val now = Samples.now
        assertEquals(RelativeTime.JustNow, relativeTime(now.minusSeconds(20), now))
        assertEquals(RelativeTime.MinutesAgo(5), relativeTime(now.minusSeconds(5 * 60 + 3), now))
        assertEquals(RelativeTime.HoursAgo(3), relativeTime(now.minusSeconds(3 * 3600 + 60), now))
        assertEquals(RelativeTime.DaysAgo(2), relativeTime(now.minusSeconds(2 * 86400 + 5), now))
        assertEquals(RelativeTime.JustNow, relativeTime(now.plusSeconds(60), now))
    }
}
