package cloud.nalet.chino.mobile.cast

/*
 * When the TV can't play a title: one line with the device's name, the reason
 * under details, and casting stops (the design system's casting pattern). The
 * reason is read off what the receiver reported: its HTTP status where it
 * names one - the design system's mapping of a status to a cause - else its
 * detailed error code. Pure, so commonTest pins every case.
 */

/** A title the TV could not play. */
data class CastFailure(
    /** The device's own name, from the network ("Living Room TV"). */
    val device: String,
    /** The reason, under details: what the TV or the phone reported. */
    val details: String,
    /** The title it was asked to play, when the phone knows it. */
    val itemId: String? = null,
) {
    /** The one line. */
    val headline: String get() = "$device can't play this title."
}

/** The words for a device that names itself nothing. */
const val UNNAMED_CAST_DEVICE: String = "The TV"

/** The bar's second line, in our words: "playing on Living Room TV", the
 *  device's own name from the network - "the TV" for one that names itself
 *  nothing. */
fun castDeviceLine(device: String?): String =
    "playing on " + (device?.trim()?.takeIf { it.isNotEmpty() } ?: "the TV")

/** What the receiver - or the phone's Cast SDK - reported of a failure. */
data class CastErrorReport(
    /** The HTTP status of the request that failed, where the receiver names
     *  one ([httpStatusOf]). */
    val httpStatus: Int? = null,
    /** The receiver's detailed error code (MediaError.DetailedErrorCode). */
    val detailedCode: Int? = null,
    /** The status of the phone's load request (CastStatusCodes), where the
     *  phone, not the TV, saw it fail. */
    val senderStatus: Int? = null,
)

// The receiver's detailed error codes (MediaError.DetailedErrorCode), as
// their families: what the receiver's player failed at.
private const val MEDIA_DECODE = 102
private const val MEDIA_NETWORK = 103
private const val MEDIA_SRC_NOT_SUPPORTED = 104
private const val SOURCE_BUFFER_FAILURE = 110
private const val MEDIAKEYS_UNSUPPORTED = 202
private const val TEXT_UNKNOWN = 600

// The phone's load request (CastStatusCodes).
private const val SENDER_NETWORK_ERROR = 7
private const val SENDER_TIMEOUT = 15
private const val SENDER_CONNECTION_SUSPENDED = 2016

/**
 * The reason under details, in the design system's words for each cause:
 * HTTP 5xx the server's problem, 429 busy, 404 and 410 gone, 403 no access,
 * 401 a sign-in refused, 408 no answer, any other 4xx not accepted; without a
 * status, what the receiver's code says - media it does not play, a stream it
 * could not load from [serverHost] or not read, subtitles it could not load;
 * and where the phone saw the request fail, the connection or the time it
 * took. The status or code itself ends the line, for whoever maintains the
 * app.
 */
fun castFailureDetails(report: CastErrorReport, serverHost: String): String {
    val host = serverHost.ifBlank { "the server" }
    report.httpStatus?.let { s ->
        val cause = when {
            s >= 500 -> "the server ran into a problem"
            s == 429 -> "the server is busy"
            s == 404 || s == 410 -> "the server has no stream of this title"
            s == 403 -> "no access to this title"
            s == 401 -> "the server didn't accept the sign-in"
            s == 408 -> "no answer from $host"
            else -> "the server didn't accept the request"
        }
        return "the TV reported: $cause (HTTP $s)"
    }
    report.detailedCode?.let { c ->
        val cause = when (c) {
            MEDIA_DECODE, MEDIA_SRC_NOT_SUPPORTED, SOURCE_BUFFER_FAILURE, MEDIAKEYS_UNSUPPORTED -> "unsupported media"
            MEDIA_NETWORK, in 300..399 -> "couldn't load the stream from $host"
            in 400..499 -> "couldn't read the stream"
            TEXT_UNKNOWN -> "couldn't load the subtitles"
            else -> "playback error"
        }
        return "the TV reported: $cause (error $c)"
    }
    return when (report.senderStatus) {
        SENDER_TIMEOUT -> "no answer from the TV in time"
        SENDER_NETWORK_ERROR, SENDER_CONNECTION_SUSPENDED -> "the connection to the TV dropped"
        null -> "the TV reported an error"
        else -> "the TV reported an error (status ${report.senderStatus})"
    }
}

private val HTTP_STATUS_KEYS = listOf("httpStatus", "httpStatusCode", "statusCode", "status", "httpResponseCode")
private val HTTP_STATUS_IN_TEXT = Regex("""(?i)\b(?:HTTP|status)[ :=/]*([1-5][0-9]{2})\b""")

/**
 * The HTTP status a receiver's error names, if any: in its custom data under a
 * key that says so (flattened to [customData], key to value), or in its reason
 * text ("HTTP 503", "status 404"). Receivers differ in where they put it, and
 * the default receiver often reports none at all; then there is none.
 */
fun httpStatusOf(customData: Map<String, String>, reason: String?): Int? {
    for (key in HTTP_STATUS_KEYS) {
        val v = customData.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.trim()?.toIntOrNull()
        if (v != null && v in 100..599) return v
    }
    val text = reason ?: return null
    return HTTP_STATUS_IN_TEXT.find(text)?.groupValues?.get(1)?.toIntOrNull()
}
