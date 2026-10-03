package cloud.nalet.chino.mobile.data.api

import io.ktor.http.encodeURLParameter

/**
 * A server artwork path — an item's poster_url / backdrop_url, a person's
 * profile_url ("/api/v1/…") — as a URL an image loader can fetch. chino-api
 * serves these from its stream-token group: an image request carries no
 * bearer header, so the long-lived stream token rides along as `?stream=`.
 * It stays the same for six hours, so the URL — and Coil's cache key — does
 * not change with every silent token renewal. Mirrors chino-web's
 * lib/artwork.ts `withStreamToken`.
 *
 * [apiBase] is the connected server's API base ("https://host/api", with or
 * without the trailing slash); server paths start at the origin ("/api/v1/…").
 * Null for a missing or blank [path]; without a token the URL goes out bare.
 */
fun artworkUrl(apiBase: String, path: String?, streamToken: String?): String? {
    val p = path?.trim().orEmpty()
    if (p.isEmpty()) return null
    val base = apiBase.trimEnd('/')
    val absolute = when {
        p.startsWith("https://") || p.startsWith("http://") -> p
        // "/api/v1/…" hangs off the origin, and the base already ends in /api.
        p.startsWith("/") -> base.removeSuffix("/api") + p
        else -> "$base/$p"
    }
    if (streamToken.isNullOrBlank()) return absolute
    val separator = if ('?' in absolute) '&' else '?'
    return "$absolute${separator}stream=${streamToken.encodeURLParameter()}"
}
