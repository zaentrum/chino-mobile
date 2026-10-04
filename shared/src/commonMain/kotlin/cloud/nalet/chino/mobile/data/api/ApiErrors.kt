package cloud.nalet.chino.mobile.data.api

import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A non-2xx answer from chino-api. The app's chino-api client throws it for
 * every one ([chinoApiClient]), so a refusal reads as a failure with its
 * reason rather than as a body that failed to decode — or, worse, decoded
 * into defaults: a JSON error read as a page of no items, a progress of 0.
 *
 * [path] is the request's path without its query (the stream token rides
 * there); [serverMessage] what the server said, when it said something
 * readable.
 */
class ApiStatusException(
    val status: Int,
    val path: String,
    /** chino-api's plain-text error line (http.Error: "name exists"), or the
     *  `error` of a JSON one (writeJSON: "catalog unavailable"); null for an
     *  empty body, an HTML page, a JSON body without `error`. */
    val serverMessage: String? = null,
) : Exception(
    buildString {
        append(path).append(": HTTP ").append(status)
        if (!serverMessage.isNullOrBlank()) append(" — ").append(serverMessage)
    },
)

/** Longest server message kept: one line of an error, not a page. */
private const val MAX_SERVER_MESSAGE = 200

/** This answer as an [ApiStatusException], with the server's message. */
internal suspend fun HttpResponse.apiStatusException(): ApiStatusException =
    ApiStatusException(status.value, call.request.url.encodedPath, serverMessage())

/** What a non-2xx body says, in one line — see [ApiStatusException.serverMessage]. */
internal suspend fun HttpResponse.serverMessage(): String? {
    val text = runCatching { bodyAsText() }.getOrNull()?.trim().orEmpty()
    if (text.isEmpty() || text.startsWith("<")) return null
    val message = if (text.startsWith("{")) {
        runCatching { ChinoJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
    } else {
        text.lineSequence().first()
    }
    return message?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_SERVER_MESSAGE)
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

/**
 * Why chino-api refused to create or rename a list, in words, or null when
 * it is not a refusal it explains (a network failure, a 5xx): a 409 says
 * the name exists or the 50 lists are reached ("name exists" / "too many
 * lists"), a 400 that the name is empty or longer than 60.
 */
fun Throwable.watchlistRefusal(): String? {
    val e = this as? ApiStatusException ?: return null
    return when {
        e.status == 409 && e.serverMessage?.contains("name", ignoreCase = true) == true ->
            "A list with that name already exists."
        e.status == 409 -> "You've reached the maximum number of lists."
        e.status == 400 -> "Enter a name between 1 and 60 characters."
        else -> null
    }
}
