package cloud.nalet.chino.mobile.cast

import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.downloads.SERVER_DOWN
import io.ktor.client.plugins.ResponseException

/*
 * When the TV can't play a title: one line with the device's name, the reason
 * under details, and casting stops (the design system's casting pattern).
 *
 * Google's receiver reports what failed as a code, never the HTTP status of
 * a request it made. So where it fails, the phone fetches the TV's master
 * itself, as the TV did, and the status it gets back says why - the design
 * system's mapping of a status to a cause; where the server answers, the
 * receiver's code says what the TV could not do. Pure, so commonTest pins
 * every case.
 */

/** A title the TV could not play. */
data class CastFailure(
    /** The device's own name, from the network ("Living Room TV"), else the
     *  words for one without ([UNNAMED_CAST_DEVICE]). */
    val device: String,
    /** The reason, under details: what the TV or the phone found. */
    val details: String,
    /** The title it was asked to play, when the phone knows it. */
    val itemId: String? = null,
    /** The server did not answer the phone ([CastErrorReport.serverUnreachable]):
     *  a title on the phone plays there instead. */
    val serverUnreachable: Boolean = false,
) {
    /** The one line. */
    val headline: String get() = "$device can't play this title."
}

/** The words for a device that names itself nothing, neither a name of its
 *  own nor its model's: it starts the line. */
const val UNNAMED_CAST_DEVICE: String = "The device"

/**
 * The name a device goes by in casting's words: the name it was given on the
 * network ("Living Room TV"), else the model it says it is - both from the
 * device itself - else null.
 */
fun castDeviceName(friendlyName: String?, modelName: String?): String? =
    friendlyName?.trim()?.takeIf { it.isNotEmpty() } ?: modelName?.trim()?.takeIf { it.isNotEmpty() }

/** A device within a line of ours: its name ([castDeviceName]), "the
 *  device" for one that names itself nothing. */
fun castDeviceInLine(device: String?): String = device?.trim()?.takeIf { it.isNotEmpty() } ?: "the device"

/** The bar's second line, in our words: "playing on Living Room TV". */
fun castDeviceLine(device: String?): String = "playing on " + castDeviceInLine(device)

/** What the phone got fetching the TV's master itself. */
sealed interface CastProbe {
    /** The server answered with [status]. */
    data class Answered(val status: Int) : CastProbe

    /** No answer: the server could not be reached, or took too long. */
    data object NoAnswer : CastProbe
}

/** What the receiver - or the phone's Cast SDK - reported of a failure, and
 *  what the phone found. */
data class CastErrorReport(
    /** The receiver's detailed error code (MediaError.DetailedErrorCode). */
    val detailedCode: Int? = null,
    /** The receiver's reason (MediaError's ERROR_REASON_*), a code word. */
    val reason: String? = null,
    /** The status of the phone's load request (CastStatusCodes), where the
     *  phone, not the TV, saw it fail. */
    val senderStatus: Int? = null,
    /** The phone's fetch of the TV's master; null where it made none. */
    val probe: CastProbe? = null,
    /** The phone's request for the TV's links - a stream token - where that
     *  failed: before the TV was asked anything. */
    val mint: CastProbe? = null,
    /** The phone's own code failed as it sent the title: neither the TV nor
     *  the server said anything. */
    val onThePhone: Boolean = false,
) {
    /** The server did not answer the phone - its links for the TV, or its
     *  own fetch of the TV's master - or a proxy in front of it answered
     *  that it is down (502-504, the app's one rule: [SERVER_DOWN]). */
    val serverUnreachable: Boolean get() = mint.saysServerDown() || probe.saysServerDown()
}

private fun CastProbe?.saysServerDown(): Boolean =
    this == CastProbe.NoAnswer || (this is CastProbe.Answered && status in SERVER_DOWN)

/**
 * Whether a request that failed with [e] says the server can't be reached:
 * no answer of the server's - no connection, too slow, nothing readable -
 * or a proxy's answer for a server behind it that is down (502-504,
 * [SERVER_DOWN]). A title on the phone then plays there, even while a TV is
 * connected (docs/chino casting, "Downloads without the server").
 */
fun serverDidNotAnswer(e: Throwable): Boolean = when (e) {
    is ApiStatusException -> e.status in SERVER_DOWN
    is ResponseException -> e.response.status.value in SERVER_DOWN
    else -> true
}

/** The receiver's reason when a device without a screen was asked to play
 *  a video (MediaError.ERROR_REASON_VIDEO_DEVICE_REQUIRED). */
const val CAST_REASON_VIDEO_DEVICE_REQUIRED: String = "VIDEO_DEVICE_REQUIRED"

// The receiver's detailed error codes (MediaError.DetailedErrorCode), as
// their families: what the receiver's player failed at.
private const val MEDIA_DECODE = 102
private const val MEDIA_NETWORK = 103
private const val MEDIA_SRC_NOT_SUPPORTED = 104
private const val SOURCE_BUFFER_FAILURE = 110
private const val MEDIAKEYS_UNSUPPORTED = 202
private const val HLS_NETWORK_INVALID_SEGMENT = 315
private const val HLS_SEGMENT_PARSING = 316
private const val TEXT_UNKNOWN = 600

// The phone's load request (CastStatusCodes).
private const val SENDER_NETWORK_ERROR = 7
private const val SENDER_TIMEOUT = 15
private const val SENDER_CONNECTION_SUSPENDED = 2016

/**
 * Whether the TV failed at something it may do on a second try - a request
 * it made, a segment it read - not at the title itself: media it does not
 * decode, a device without a screen. Only then does the phone load the title
 * once more, its links signed anew, before it says the TV can't.
 */
fun castMayRetry(report: CastErrorReport): Boolean {
    if (report.reason == CAST_REASON_VIDEO_DEVICE_REQUIRED) return false
    return when (report.detailedCode) {
        MEDIA_DECODE, MEDIA_SRC_NOT_SUPPORTED, SOURCE_BUFFER_FAILURE, MEDIAKEYS_UNSUPPORTED -> false
        else -> true
    }
}

/**
 * The reason under details, in the design system's words for each cause.
 * Where the phone could not get the TV its links (a stream token): the
 * phone's own sign-in refused (401), the account not let play it (403), the
 * server's problem, or no answer. Where the phone's own code failed as it
 * sent the title, that. Where the phone's own fetch of the TV's master
 * failed, its status:
 * 5xx the server's problem, 429 busy, 404 and 410 no stream, 403 no access,
 * 401 the device's link turned down - the viewer is still signed in, the
 * link the device was given is what the server refused - 408 or no answer at
 * all, the connection, any other 4xx not accepted. Else what the receiver
 * said: media it does not play, a device without a screen, a stream it could
 * not load from [serverHost] or not read, subtitles it could not load; and
 * where the phone saw its request fail, the connection or the time it took.
 * The device goes by its name ([device]), else "the device" - the TV, never
 * this phone, which is "this phone" or "this tablet" ([thisDevice]); the
 * status or code itself ends the line, for whoever maintains the app.
 */
fun castFailureDetails(report: CastErrorReport, serverHost: String, device: String? = null, thisDevice: String = "phone"): String {
    val host = serverHost.ifBlank { "the server" }
    val tv = castDeviceInLine(device)
    when (val mint = report.mint) {
        is CastProbe.Answered -> return when (mint.status) {
            401 -> "the server no longer accepts this $thisDevice's sign-in (HTTP 401)"
            403 -> "the server doesn't let this account play it (HTTP 403)"
            else -> "${statusCause(mint.status, host)} (HTTP ${mint.status})"
        }
        CastProbe.NoAnswer -> return "no answer from $host"
        null -> Unit
    }
    if (report.onThePhone) return "this $thisDevice ran into a problem sending the title"
    if (report.reason == CAST_REASON_VIDEO_DEVICE_REQUIRED) return "the device plays sound only, not video"
    when (val probe = report.probe) {
        is CastProbe.Answered -> if (probe.status >= 400) return "${statusCause(probe.status, host)} (HTTP ${probe.status})"
        CastProbe.NoAnswer -> return "no answer from $host"
        null -> Unit
    }
    report.detailedCode?.let { c ->
        val cause = when (c) {
            MEDIA_DECODE, MEDIA_SRC_NOT_SUPPORTED, SOURCE_BUFFER_FAILURE, MEDIAKEYS_UNSUPPORTED -> "unsupported media"
            HLS_NETWORK_INVALID_SEGMENT, HLS_SEGMENT_PARSING -> "couldn't read the stream"
            MEDIA_NETWORK, in 300..399 -> "couldn't load the stream from $host"
            in 400..499 -> "couldn't read the stream"
            TEXT_UNKNOWN -> "couldn't load the subtitles"
            else -> "playback error"
        }
        return "$tv reported: $cause (error $c)"
    }
    return when (report.senderStatus) {
        SENDER_TIMEOUT -> "no answer from $tv in time"
        SENDER_NETWORK_ERROR, SENDER_CONNECTION_SUSPENDED -> "the connection to $tv dropped"
        null -> "$tv reported an error"
        else -> "$tv reported an error (status ${report.senderStatus})"
    }
}

private fun statusCause(status: Int, host: String): String = when {
    status >= 500 -> "the server ran into a problem"
    status == 429 -> "the server is busy"
    status == 404 || status == 410 -> "the server has no stream of this title"
    status == 403 -> "no access to this title"
    status == 401 -> "the server turned down the device's link to the stream"
    status == 408 -> "no answer from $host"
    else -> "the server didn't accept the request"
}
