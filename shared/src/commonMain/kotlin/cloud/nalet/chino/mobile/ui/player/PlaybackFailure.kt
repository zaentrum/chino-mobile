package cloud.nalet.chino.mobile.ui.player

import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.downloads.DeviceKind
import io.ktor.client.plugins.ResponseException
import kotlinx.io.IOException

/**
 * Why a title stopped playing for good, once the player has nothing left to
 * try - the design system's couldn't-load cases, by cause, as far as the
 * player can tell. What separates them (the engine's error code, the
 * answer's status) goes under details and into the bug report, never into
 * the sentence.
 */
enum class PlaybackFailure {
    /** No answer: the connection dropped, or the server didn't reply. */
    NoAnswer,

    /** A 5xx other than busy; a stream the server sent that can't be read. */
    ServerProblem,

    /** 429, 503. */
    Busy,

    /** Any other refusal (4xx). */
    NotAccepted,

    /** 401: the server no longer takes the sign-in. */
    SignInRefused,

    /** 403: the server doesn't let this account play it. */
    NoAccess,

    /** The master answered 404: nothing to stream yet. */
    NotYetAvailable,

    /** What came can't be played on this device: its decoders, its audio. */
    NotOnThisDevice,

    /** Something else stopped it. */
    Stopped,
}

/** The case of an answer's [status]; null for none (no answer). */
fun playbackFailureOf(status: Int?): PlaybackFailure = when {
    status == null -> PlaybackFailure.NoAnswer
    status == 401 -> PlaybackFailure.SignInRefused
    status == 403 -> PlaybackFailure.NoAccess
    status == 429 || status == 503 -> PlaybackFailure.Busy
    status >= 500 -> PlaybackFailure.ServerProblem
    status >= 400 -> PlaybackFailure.NotAccepted
    else -> PlaybackFailure.Stopped
}

/** The case of a request that failed before the player was built - the
 *  title's details, its stream token. */
fun Throwable.playbackFailure(): PlaybackFailure = when (this) {
    is ApiStatusException -> playbackFailureOf(status)
    is ResponseException -> playbackFailureOf(response.status.value)
    is IOException -> PlaybackFailure.NoAnswer
    else -> PlaybackFailure.Stopped
}

/** What the player's couldn't-play screen is headed: what couldn't happen,
 *  in its own words. */
const val COULDNT_PLAY_TITLE = "couldn't play this title"

/** The sentence under [COULDNT_PLAY_TITLE]: the reason, nothing a button
 *  beside it already offers. "this phone" or "this tablet" ([device]). */
fun PlaybackFailure.sentence(device: DeviceKind = DeviceKind.Phone): String = when (this) {
    PlaybackFailure.NoAnswer -> "Can't reach the server."
    PlaybackFailure.ServerProblem -> "The server ran into a problem."
    PlaybackFailure.Busy -> "The server is busy."
    PlaybackFailure.NotAccepted -> "The server didn't accept the request. Check for an app update."
    PlaybackFailure.SignInRefused -> "The server no longer accepts this ${device.word}'s sign-in."
    PlaybackFailure.NoAccess -> "The server doesn't let this account play it."
    PlaybackFailure.NotYetAvailable -> "This title isn't available to stream yet."
    PlaybackFailure.NotOnThisDevice -> "This ${device.word} can't play it."
    PlaybackFailure.Stopped -> "The stream stopped before it was done."
}

/** Whether "try again" can help: not for a title that isn't there, nor
 *  for what this device can't play. */
val PlaybackFailure.canTryAgain: Boolean
    get() = this != PlaybackFailure.NotYetAvailable && this != PlaybackFailure.NotOnThisDevice

/**
 * A playback that stopped for good: its [failure], and what separates the
 * cases for the people who fix things ([details]: the engine's error code,
 * the answer's status) - under "details", and in the bug report. [atMs]:
 * where it stopped, where "try again" starts; -1 before it played.
 */
data class PlaybackStop(val failure: PlaybackFailure, val details: String, val atMs: Long = -1L)

/** What separates the cases of a request that failed before the player was
 *  built, for [PlaybackStop.details]: the answer's status, or that none
 *  came. Never a message, an id or a path. */
fun Throwable.playbackDetails(): String = when (this) {
    is ApiStatusException -> "HTTP $status"
    is ResponseException -> "HTTP ${response.status.value}"
    is IOException -> "no answer"
    else -> this::class.simpleName ?: "error"
}
