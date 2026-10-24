package ai.enthral.sentinel.data.api

import ai.enthral.sentinel.domain.FeedbackLabel
import ai.enthral.sentinel.domain.Source
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Shared JSON config: tolerant of new server fields, omits null optionals (omitted == "unknown" per the contract). */
val SentinelJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

/**
 * OkHttp + kotlinx.serialization implementation. [tokenProvider] is read per request so a re-registered token
 * is picked up immediately. The token is only ever placed in the Authorization header, never in URLs or logs.
 */
class OkHttpSentinelApi(
    baseUrl: String,
    private val client: OkHttpClient,
    private val tokenProvider: suspend () -> String?,
    private val json: Json = SentinelJson,
) : SentinelApi {

    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override suspend fun register() = execute<DeviceRegistered>("POST", "v1/devices/register", auth = false,
        body = "".toRequestBody(null))

    override suspend fun registerFcmToken(token: String) = send<FcmIn, Ok>("POST", "v1/devices/fcm", FcmIn(token))

    override suspend fun postEvent(event: EventIn) = send<EventIn, EventResult>("POST", "v1/events", event)
    override suspend fun check(event: EventIn) = send<EventIn, EventResult>("POST", "v1/check", event)

    override suspend fun checkImage(
        image: ByteArray,
        mimeType: String,
        deviceEventId: String?,
        ts: String?,
        lang: String?,
        triageScore: Double?,
    ): ApiResult<EventResult> {
        val mediaType = runCatching { mimeType.toMediaType() }.getOrNull()
            ?: return ApiResult.HttpError(415, "unsupported image type")
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", "screenshot.${extensionFor(mimeType)}", image.toRequestBody(mediaType))
        deviceEventId?.let { form.addFormDataPart("device_event_id", it) }
        ts?.let { form.addFormDataPart("ts", it) }
        lang?.let { form.addFormDataPart("lang", it) }
        triageScore?.let { form.addFormDataPart("triage_score", it.toString()) }
        return execute("POST", "v1/check", body = form.build())
    }

    override suspend fun getAlerts(since: String?) =
        execute<List<AlertOut>>("GET", "v1/alerts", query = since?.let { mapOf("since" to it) } ?: emptyMap())

    override suspend fun postFeedback(alertId: String, label: FeedbackLabel) =
        send<FeedbackIn, Ok>("POST", "v1/alerts/$alertId/feedback", FeedbackIn(label))

    override suspend fun createFamilyInvite(consent: Boolean) =
        send<FamilyInviteIn, FamilyInviteOut>("POST", "v1/family/invites", FamilyInviteIn(consent))

    override suspend fun acceptFamilyInvite(code: String, name: String, fcmToken: String) =
        send<FamilyAcceptIn, FamilyLinkOut>("POST", "v1/family/accept", FamilyAcceptIn(code, name, fcmToken))

    override suspend fun getFamilyContacts() = execute<FamilyContacts>("GET", "v1/family/contacts")

    override suspend fun unlinkFamily(linkId: String) = executeUnit("DELETE", "v1/family/contacts/$linkId")

    override suspend fun getMe() = execute<Me>("GET", "v1/me")

    override suspend fun setConsent(source: Source, granted: Boolean) =
        send<ConsentIn, ConsentOut>("POST", "v1/me/consents", ConsentIn(source, granted))

    override suspend fun deleteMe() = executeUnit("DELETE", "v1/me")

    // --- plumbing -------------------------------------------------------------------------------------------

    private suspend inline fun <reified B : Any, reified T> send(method: String, path: String, payload: B) =
        execute<T>(method, path, body = json.encodeToString(serializer<B>(), payload).toRequestBody(jsonType))

    private suspend inline fun <reified T> execute(
        method: String,
        path: String,
        auth: Boolean = true,
        query: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
    ): ApiResult<T> = when (val raw = call(method, path, auth, query, body)) {
        is Raw.Failure -> raw.result
        is Raw.Ok -> try {
            ApiResult.Success(json.decodeFromString(serializer<T>(), raw.body))
        } catch (_: SerializationException) {
            ApiResult.HttpError(raw.status, "malformed response")
        } catch (_: IllegalArgumentException) {
            ApiResult.HttpError(raw.status, "malformed response")
        }
    }

    private suspend fun executeUnit(method: String, path: String): ApiResult<Unit> =
        when (val raw = call(method, path, auth = true, query = emptyMap(), body = null)) {
            is Raw.Failure -> raw.result
            is Raw.Ok -> ApiResult.Success(Unit)
        }

    private sealed interface Raw {
        data class Ok(val status: Int, val body: String) : Raw
        data class Failure(val result: ApiResult<Nothing>) : Raw
    }

    private suspend fun call(
        method: String,
        path: String,
        auth: Boolean,
        query: Map<String, String>,
        body: RequestBody?,
    ): Raw {
        val url = base.newBuilder().addPathSegments(path).apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        val request = Request.Builder().url(url).method(method, body ?: if (method == "DELETE" || method == "GET") null else "".toRequestBody(null))
            .header("Accept", "application/json")
            .apply { if (auth) tokenProvider()?.let { header("Authorization", "Bearer $it") } }
            .build()
        return try {
            client.newCall(request).await().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) Raw.Ok(response.code, text)
                else Raw.Failure(ApiResult.HttpError(response.code, parseDetail(text)))
            }
        } catch (e: IOException) {
            Raw.Failure(ApiResult.NetworkError(e::class.java.simpleName))
        }
    }

    private fun parseDetail(body: String): String? {
        if (body.isBlank()) return null
        val detail = try {
            json.decodeFromString(serializer<ErrorBody>(), body).detail
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return when (detail) {
            null -> null
            is JsonPrimitive -> detail.contentOrNull
            // 422: [{"loc": [...], "msg": "...", "type": "..."}]  ->  "msg; msg"
            is JsonArray -> detail.mapNotNull { (it as? JsonObject)?.get("msg")?.jsonPrimitive?.contentOrNull }
                .joinToString("; ").ifEmpty { null }
            else -> null
        }
    }

    private fun extensionFor(mime: String) = when (mime.lowercase().substringBefore(';')) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "jpg"
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (!cont.isActive) response.close() else cont.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWith(Result.failure(e))
        }
    })
}
