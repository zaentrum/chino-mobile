package cloud.nalet.chino.mobile.data.api

import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.io.IOException
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
 * readable; [personMessage] what it wrote for the person, when it did.
 */
class ApiStatusException(
    val status: Int,
    val path: String,
    /** chino-api's plain-text error line (http.Error: "name exists"), or the
     *  `error` of a JSON one (writeJSON: "catalog unavailable"); null for an
     *  empty body, an HTML page, a JSON body without `error`. */
    val serverMessage: String? = null,
    /** The `message` of a JSON answer — the sentence chino-api writes for
     *  the person (deleting an account: why it was refused); null when the
     *  body carries none. */
    val personMessage: String? = null,
) : Exception(
    buildString {
        append(path).append(": HTTP ").append(status)
        if (!serverMessage.isNullOrBlank()) append(" — ").append(serverMessage)
    },
)

/** Longest server message kept: one line of an error, not a page. */
private const val MAX_SERVER_MESSAGE = 200

/** Longest message for the person kept: a sentence or two, not a page. */
private const val MAX_PERSON_MESSAGE = 300

/** This answer as an [ApiStatusException], with the server's messages. */
internal suspend fun HttpResponse.apiStatusException(): ApiStatusException {
    val text = runCatching { bodyAsText() }.getOrNull()?.trim().orEmpty()
    return ApiStatusException(status.value, call.request.url.encodedPath, serverMessage(text), personMessage(text))
}

/** What a non-2xx body says, in one line — see [ApiStatusException.serverMessage]. */
internal fun serverMessage(text: String): String? {
    if (text.isEmpty() || text.startsWith("<")) return null
    val message = if (text.startsWith("{")) {
        jsonField(text, "error")
    } else {
        text.lineSequence().first()
    }
    return message?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_SERVER_MESSAGE)
}

/** The `message` of a JSON body — see [ApiStatusException.personMessage]. */
internal fun personMessage(text: String): String? {
    if (!text.startsWith("{")) return null
    return jsonField(text, "message")?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_PERSON_MESSAGE)
}

private fun jsonField(text: String, name: String): String? =
    runCatching { ChinoJson.parseToJsonElement(text).jsonObject[name]?.jsonPrimitive?.contentOrNull }.getOrNull()

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
        // No answer - no connection, the server down or too slow: what to
        // check, not the request it was.
        is IOException -> return NO_ANSWER_SENTENCE
        else -> return message ?: this::class.simpleName.orEmpty()
    }
    return when {
        signInNotForThisServer -> "This server does not accept the sign-in the app used. Sign in again: " +
            "the app asks the server for its sign-in anew. If this stays, the server's operator has to set its " +
            "sign-in up for the app."
        status == 401 || status == 403 -> "This server no longer accepts your sign-in. Sign in again."
        status in 500..599 -> "The catalogue is unavailable right now (HTTP $status)."
        else -> "The server answered HTTP $status."
    }
}

/** What a screen that needs the server says when no answer came: the
 *  design system's "couldn't load" sentence for it. */
const val NO_ANSWER_SENTENCE = "Check the connection to the server, then try again."

/**
 * Why a screen couldn't load, as the design system's table tells the cases
 * apart: by the answer's status, never by its message. [singleThing]: the
 * screen is about one thing - a title - where a 404 or 410 says it was
 * removed; on a list they say the app and the server don't match.
 */
enum class LoadFailure {
    /** No answer: the connection dropped, or the server didn't reply. */
    NoAnswer,
    ServerProblem,

    /** 429, 503. */
    Busy,

    /** Any other refusal; a 404 or 410 on a list; an answer the app could
     *  not read. */
    NotAccepted,

    /** 404 or 410 on a page about one thing. */
    Removed,

    /** 403. */
    NoAccess,

    /** 401, after the app's own renewal: no empty state - the sign-in. */
    SignedOut,
}

fun Throwable.loadFailure(singleThing: Boolean): LoadFailure {
    val status = when (this) {
        is ApiStatusException -> status
        is ResponseException -> response.status.value
        is IOException -> return LoadFailure.NoAnswer
        else -> return LoadFailure.NotAccepted
    }
    return when {
        status == 401 -> LoadFailure.SignedOut
        status == 403 -> LoadFailure.NoAccess
        (status == 404 || status == 410) && singleThing -> LoadFailure.Removed
        status == 429 || status == 503 -> LoadFailure.Busy
        status >= 500 -> LoadFailure.ServerProblem
        else -> LoadFailure.NotAccepted
    }
}

/** The sentence under a couldn't-load title, where the case has one. */
fun LoadFailure.sentence(): String? = when (this) {
    LoadFailure.NoAnswer -> NO_ANSWER_SENTENCE
    LoadFailure.ServerProblem -> "The server ran into a problem. Try again in a moment."
    LoadFailure.Busy -> "The server is busy. Try again in a moment."
    LoadFailure.NotAccepted -> "The server didn't accept the request. Check for an app update, then try again."
    LoadFailure.Removed, LoadFailure.NoAccess, LoadFailure.SignedOut -> null
}

/** What separates the cases, for a couldn't-load screen's details: the
 *  answer's status, or that none came. Never a message, an id or a path. */
fun Throwable.loadDetails(): String = when (this) {
    is ApiStatusException -> "HTTP $status"
    is ResponseException -> "HTTP ${response.status.value}"
    is IOException -> "no answer"
    else -> "an answer the app couldn't read"
}

/** Whether the server answered that a title is not there (404, 410): it
 *  was removed from the library. */
val Throwable.titleGone: Boolean
    get() = this is ApiStatusException && (status == 404 || status == 410)

/**
 * The server refuses the person's sign-in: a 401 or 403 of chino-api, which a
 * renewed token did not mend. Signing in again or connecting to another
 * server is what is left to do.
 */
val Throwable.signInRefused: Boolean
    get() = when (this) {
        is ApiStatusException -> status == 401 || status == 403
        is ResponseException -> response.status.value == 401 || response.status.value == 403
        else -> false
    }

/**
 * A 401 for a token made for someone else: chino-api names the audience it
 * expects and the one the token carries ("invalid token: oidc: expected
 * audience ..."). The server's /api/config sent the app to a sign-in client
 * whose tokens the server itself does not take. Signing in again asks the
 * server for its sign-in anew (refreshServerConfig), which mends it once the
 * operator has named the right client; until then it is the operator's to fix.
 */
val Throwable.signInNotForThisServer: Boolean
    get() = this is ApiStatusException && status == 401 &&
        serverMessage?.contains("audience", ignoreCase = true) == true

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
