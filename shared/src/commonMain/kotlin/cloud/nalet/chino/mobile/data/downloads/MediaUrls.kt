package cloud.nalet.chino.mobile.data.downloads

import cloud.nalet.chino.mobile.data.auth.withStreamToken
import cloud.nalet.chino.mobile.data.auth.withoutStreamToken

/*
 * The URLs a download fetches. chino-stream writes the stream token into
 * every URI of its playlists, so the same rendition comes with a new token
 * each time a playlist is read: a download names each piece without it (its
 * key on the phone), and signs each request with the token valid then.
 */

/**
 * [ref] - a URI of a playlist - resolved against the playlist's own
 * [base], as a player resolves it (RFC 3986): an absolute one as it is, one
 * from "/" on the base's origin, any other beside the base's last segment,
 * "." and ".." segments taken out. Its query is its own.
 */
fun resolveUrl(base: String, ref: String): String {
    if (ref.contains("://")) return ref
    val schemeEnd = base.indexOf("://")
    if (schemeEnd < 0) return ref
    val authorityEnd = base.indexOf('/', schemeEnd + 3).let { if (it < 0) base.length else it }
    val origin = base.substring(0, authorityEnd)
    if (ref.startsWith("//")) return base.substring(0, schemeEnd + 1) + ref
    val (refPath, refRest) = splitPath(ref)
    val path = when {
        refPath.startsWith("/") -> refPath
        refPath.isEmpty() -> splitPath(base.substring(authorityEnd)).first
        else -> splitPath(base.substring(authorityEnd)).first.substringBeforeLast('/', "") + "/" + refPath
    }
    val query = if (refPath.isEmpty() && refRest.isEmpty()) splitPath(base.substring(authorityEnd)).second else refRest
    return origin + removeDotSegments(path) + query
}

/** A path and what follows it ("?query#fragment"). */
private fun splitPath(s: String): Pair<String, String> {
    val cut = s.indexOfFirst { it == '?' || it == '#' }
    return if (cut < 0) s to "" else s.substring(0, cut) to s.substring(cut)
}

private fun removeDotSegments(path: String): String {
    val out = ArrayList<String>()
    val segments = path.split('/')
    for ((i, seg) in segments.withIndex()) {
        when (seg) {
            "." -> if (i == segments.lastIndex) out += ""
            ".." -> {
                if (out.size > 1) out.removeAt(out.lastIndex)
                if (i == segments.lastIndex) out += ""
            }
            else -> out += seg
        }
    }
    return out.joinToString("/").let { if (it.startsWith("/")) it else "/$it" }
}

/** The key a downloaded piece is kept under: its URL without the stream
 *  token that signed it. */
fun pieceKey(url: String): String = withoutStreamToken(url)

/** Whether two URLs name the same piece, whatever token signs them. */
fun samePiece(a: String, b: String): Boolean = pieceKey(a) == pieceKey(b)

/**
 * [url] as a download requests it now: signed with [token], the one valid
 * now, in place of the token it was written with - a download resumed hours
 * later asks with a token the server takes. Without a token to sign with
 * (none could be minted: no connection) the URL goes as it is.
 */
fun signedNow(url: String, token: String?): String = if (token.isNullOrBlank()) url else withStreamToken(url, token)
