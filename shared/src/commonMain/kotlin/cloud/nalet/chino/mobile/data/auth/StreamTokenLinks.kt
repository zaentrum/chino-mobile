package cloud.nalet.chino.mobile.data.auth

/*
 * Links signed with the stream token (`?stream=`): the app's images and its
 * media, which carry the token in the URL because no header can be put on
 * them. Pure, so commonTest pins them.
 */

private const val STREAM_PARAM = "stream"

/** The stream token [url] is signed with, or null when it carries none. */
fun streamTokenOf(url: String): String? =
    queryOf(url)?.split('&')?.firstNotNullOfOrNull { part ->
        if (part.substringBefore('=') == STREAM_PARAM && '=' in part) part.substringAfter('=') else null
    }

/** [url] signed with [token]: its `stream` parameter set to it - added when
 *  it has none - and every other part as it was, in its order. */
fun withStreamToken(url: String, token: String): String {
    val path = url.substringBefore('?')
    val parts = queryOf(url)?.split('&').orEmpty()
    var signed = false
    val query = parts.map { part ->
        if (part.substringBefore('=') == STREAM_PARAM) {
            signed = true
            "$STREAM_PARAM=$token"
        } else {
            part
        }
    } + if (signed) emptyList() else listOf("$STREAM_PARAM=$token")
    return path + "?" + query.joinToString("&")
}

/** [url] without its `stream` parameter: what the link names, whatever
 *  token signs it. */
fun withoutStreamToken(url: String): String {
    val path = url.substringBefore('?')
    val rest = queryOf(url)?.split('&').orEmpty().filter { it.substringBefore('=') != STREAM_PARAM }
    return if (rest.isEmpty()) path else path + "?" + rest.joinToString("&")
}

private fun queryOf(url: String): String? = url.substringAfter('?', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
