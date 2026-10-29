package ai.enthral.sentinel.capture

import ai.enthral.sentinel.data.api.AlertOut
import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.api.ConsentOut
import ai.enthral.sentinel.data.api.DeviceRegistered
import ai.enthral.sentinel.data.api.EventIn
import ai.enthral.sentinel.data.api.EventResult
import ai.enthral.sentinel.data.api.FamilyContacts
import ai.enthral.sentinel.data.api.FamilyInviteOut
import ai.enthral.sentinel.data.api.FamilyLinkOut
import ai.enthral.sentinel.data.api.Me
import ai.enthral.sentinel.data.api.Ok
import ai.enthral.sentinel.data.api.SentinelApi
import ai.enthral.sentinel.data.store.TokenCipher
import ai.enthral.sentinel.domain.FeedbackLabel
import ai.enthral.sentinel.domain.Source
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.sync.SyncScheduler

/** Reversible, obviously-not-plaintext cipher (XOR + marker) so tests can assert nothing readable hits disk. */
class XorCipher : TokenCipher {
    var failDecrypt = false
    override fun encrypt(plain: ByteArray) = byteArrayOf(0x53, 0x45) + plain.map { (it.toInt() xor 0x5A).toByte() }
    override fun decrypt(blob: ByteArray): ByteArray? {
        if (failDecrypt || blob.size < 2 || blob[0] != 0x53.toByte() || blob[1] != 0x45.toByte()) return null
        return blob.copyOfRange(2, blob.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}

class MemoryStats : CaptureStats, CursorStore {
    val counts = mutableMapOf<Counter, Long>()
    var lastCaptured: Long? = null
    var lastSent: Long? = null
    val cursors = mutableMapOf<String, Long>()
    override val snapshot = kotlinx.coroutines.flow.flowOf(StatsSnapshot())
    override suspend fun add(counter: Counter, n: Long) { counts[counter] = (counts[counter] ?: 0) + n }
    override suspend fun markCaptured(at: Long) { lastCaptured = at }
    override suspend fun markSent(at: Long) { lastSent = at }
    override suspend fun clear() { counts.clear(); cursors.clear() }
    override suspend fun get(conversationKey: String) = cursors[conversationKey]
    override suspend fun put(conversationKey: String, ts: Long) {
        if ((cursors[conversationKey] ?: Long.MIN_VALUE) < ts) cursors[conversationKey] = ts
    }
    operator fun get(c: Counter) = counts[c] ?: 0L
}

class RecordingScheduler : SyncScheduler {
    val syncs = mutableListOf<Long>()
    val followUps = mutableListOf<List<Long>>()
    override fun scheduleSync(delayMs: Long) { syncs += delayMs }
    override fun scheduleFollowUps(delaysMs: List<Long>) { followUps += delaysMs }
}

/** Contact checker with explicit answers; null = permission missing. */
class FakeContacts(
    var granted: Boolean = true,
    /** uri -> the contact _ID it resolves to. */
    val uris: Map<String, Long> = emptyMap(),
    val numbers: Set<String> = emptySet(),
    val names: Set<String> = emptySet(),
) : ContactChecker {
    val uriQueries = mutableListOf<String>()
    var throwOnQuery = false
    override fun isSavedContactUri(uri: String, expectedId: Long): Boolean? {
        if (throwOnQuery) throw IllegalStateException("provider died")
        uriQueries += uri
        return if (!granted) null else uris[uri] == expectedId
    }
    override fun isSavedNumber(number: String): Boolean? = if (!granted) null else
        numbers.any { PhoneNormalizer.sameNumber(it, number, "IN") }
    override fun isContactName(name: String): Boolean? = if (!granted) null else names.any { it.equals(name.trim(), true) }
}

fun alertOut(id: String, tier: Tier, createdAt: String = "2026-10-29T10:00:00Z") = AlertOut(
    id = id, eventId = "evt_$id", tier = tier, headline = "Headline $id", why = emptyList(),
    nextStep = "Do not pay", signals = emptyList(), createdAt = createdAt,
)

/** Scriptable SentinelApi: postEvent answers come from [eventAnswers] in order (last one repeats). */
class FakeApi : SentinelApi {
    val posted = mutableListOf<EventIn>()
    val eventAnswers = ArrayDeque<(EventIn) -> ApiResult<EventResult>>()
    var alertsAnswer: ApiResult<List<AlertOut>> = ApiResult.Success(emptyList())
    val alertSinces = mutableListOf<String?>()
    var registerCalls = 0
    var hang = false

    fun answer(vararg a: (EventIn) -> ApiResult<EventResult>) { eventAnswers.addAll(a) }

    override suspend fun register(): ApiResult<DeviceRegistered> { registerCalls++; return ApiResult.Success(DeviceRegistered("d1", "tok-$registerCalls")) }
    override suspend fun registerFcmToken(token: String) = ApiResult.Success(Ok(true))
    override suspend fun postEvent(event: EventIn): ApiResult<EventResult> {
        if (hang) kotlinx.coroutines.awaitCancellation()
        posted += event
        val f = if (eventAnswers.size > 1) eventAnswers.removeFirst() else eventAnswers.firstOrNull()
        return f?.invoke(event) ?: ApiResult.Success(EventResult(event.deviceEventId, false, null))
    }
    override suspend fun check(event: EventIn) = postEvent(event)
    override suspend fun checkImage(image: ByteArray, mimeType: String, deviceEventId: String?, ts: String?, lang: String?, triageScore: Double?) =
        ApiResult.HttpError(500, null)
    override suspend fun getAlerts(since: String?): ApiResult<List<AlertOut>> { alertSinces += since; return alertsAnswer }
    override suspend fun postFeedback(alertId: String, label: FeedbackLabel) = ApiResult.Success(Ok(true))
    override suspend fun createFamilyInvite(consent: Boolean): ApiResult<FamilyInviteOut> = ApiResult.HttpError(500, null)
    override suspend fun acceptFamilyInvite(code: String, name: String, fcmToken: String): ApiResult<FamilyLinkOut> = ApiResult.HttpError(500, null)
    override suspend fun getFamilyContacts(): ApiResult<FamilyContacts> = ApiResult.HttpError(500, null)
    override suspend fun unlinkFamily(linkId: String): ApiResult<Unit> = ApiResult.HttpError(500, null)
    override suspend fun getMe(): ApiResult<Me> = ApiResult.HttpError(500, null)
    override suspend fun setConsent(source: Source, granted: Boolean): ApiResult<ConsentOut> = ApiResult.Success(ConsentOut(source, granted))
    override suspend fun deleteMe(): ApiResult<Unit> = ApiResult.Success(Unit)
}

fun ok(alert: AlertOut? = null): (EventIn) -> ApiResult<EventResult> = { ApiResult.Success(EventResult("srv_" + it.deviceEventId.take(6), false, alert)) }
fun http(status: Int): (EventIn) -> ApiResult<EventResult> = { ApiResult.HttpError(status, null) }
val offline: (EventIn) -> ApiResult<EventResult> = { ApiResult.NetworkError("UnknownHostException") }

fun event(id: String, text: String = "masked $id") = EventIn(
    deviceEventId = id, source = Source.Sms, ts = "2026-10-29T10:00:00Z", sender = "VM-HDFCBK", text = text, triageScore = 0.0,
)
