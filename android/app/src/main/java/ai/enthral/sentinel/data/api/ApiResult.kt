package ai.enthral.sentinel.data.api

/** Outcome of every API call. The client never throws into the UI. */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>

    /** Non-2xx answer. [detail] is the server's `detail` text when present (422 lists are flattened to a short text). */
    data class HttpError(val status: Int, val detail: String?) : ApiResult<Nothing> {
        val isUnauthorized: Boolean get() = status == 401
        val isRateLimited: Boolean get() = status == 429
    }

    /** No usable HTTP answer: offline, DNS, TLS, timeout. [reason] is the exception class name only (never message/URL). */
    data class NetworkError(val reason: String? = null) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(value))
    is ApiResult.HttpError -> this
    is ApiResult.NetworkError -> this
}

fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Success)?.value
