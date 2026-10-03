package cloud.nalet.chino.mobile.data.api

import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess

/** A non-2xx answer from chino-api, thrown by the calls that must tell a
 *  failure apart from an empty result (the app's client does not throw on a
 *  status by itself). */
class ApiStatusException(val status: Int, path: String) : Exception("$path: HTTP $status")

/** The body as [T], or [ApiStatusException] for a non-2xx answer. */
internal suspend inline fun <reified T> HttpResponse.successBody(path: String): T {
    if (!status.isSuccess()) throw ApiStatusException(status.value, path)
    return body()
}

/**
 * A failed catalogue request in words a person can act on. A Ktor
 * exception's own message is the request URL and the response body, which
 * says nothing to someone looking at an empty grid. chino-api answers 502
 * when the catalog behind it fails ("catalog unavailable"), 401 when the
 * sign-in no longer holds.
 */
fun Throwable.catalogueMessage(): String {
    val status = when (this) {
        is ApiStatusException -> status
        is ResponseException -> response.status.value
        else -> return message ?: this::class.simpleName.orEmpty()
    }
    return when (status) {
        401, 403 -> "This server no longer accepts your sign-in. Sign in again."
        in 500..599 -> "The catalogue is unavailable right now (HTTP $status)."
        else -> "The server answered HTTP $status."
    }
}
