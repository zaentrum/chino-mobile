package cloud.nalet.chino.mobile.data

/**
 * Where a link an addon wrote may take the person: a page of the server the
 * app is signed in to, and nowhere else. Slot rows and notices carry an
 * addon's links; portal-api checks each where it is written, by one rule
 * (zaentrum's docs/extending/slots.md, "Where a row may lead"), and the app
 * checks it again before it opens one, as chino-web and the portal do.
 *
 * The rule, portal-api's: a path on the server ("/portal/app/example?q=x"),
 * or an absolute http(s) URL on its own origin — the scheme, host and port of
 * [apiBaseUrl] — and nothing else: nothing protocol-relative ("//host"), no
 * other scheme (javascript:, data:, mailto:), no credentials, and nothing with
 * a space, a backslash or a control character in it (a browser drops tabs and
 * newlines inside a URL and reads "\" as "/", so "/\host" is another host).
 *
 * Returns the URL to open: the server's origin as the app spells it, then the
 * link's path, query and fragment, with what a URL may not carry as it is
 * percent-encoded ([escapeForUri]). Null when the link may lead nowhere.
 */
fun ownServerHref(link: String, apiBaseUrl: String): String? {
    val server = absoluteUrl(apiBaseUrl.trim())?.origin ?: return null
    val raw = link.trim()
    if (raw.isEmpty() || raw.any { it <= ' ' || it == '\u007F' || it == '\\' }) return null
    val rest = when {
        raw.startsWith("//") -> return null
        raw.startsWith("/") -> raw
        else -> {
            val url = absoluteUrl(raw) ?: return null
            if (url.origin != server) return null
            if (url.rest.startsWith("/")) url.rest else "/" + url.rest
        }
    }
    return server.toString() + escapeForUri(rest)
}

/** An origin as two URLs are compared by it: lower-case scheme and host, and
 *  the port, the scheme's default when none is written. */
internal data class Origin(val scheme: String, val host: String, val port: Int) {
    /** As a URL starts: the default port left out. */
    override fun toString(): String =
        if (port == defaultPort(scheme)) "$scheme://$host" else "$scheme://$host:$port"
}

/** An absolute http(s) URL taken apart: its [origin], and [rest] — what
 *  follows the host and port, from the first "/", "?" or "#" on. */
internal class AbsoluteUrl(val origin: Origin, val rest: String)

/** [url] as an absolute http(s) URL, or null: another scheme, credentials
 *  ("user:secret@"), no host, or a port that is not one. */
internal fun absoluteUrl(url: String): AbsoluteUrl? {
    val scheme = SCHEME.find(url)?.groupValues?.get(1)?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val afterScheme = url.substring(scheme.length + 3)
    val end = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        .let { if (it < 0) afterScheme.length else it }
    val authority = afterScheme.substring(0, end)
    if ('@' in authority) return null
    val host: String
    val port: String
    if (authority.startsWith("[")) {
        // An IPv6 literal, its brackets kept: "[::1]:8443".
        val close = authority.indexOf(']')
        if (close < 0) return null
        val after = authority.substring(close + 1)
        if (after.isNotEmpty() && !after.startsWith(":")) return null
        host = authority.substring(0, close + 1)
        port = after.removePrefix(":")
    } else {
        host = authority.substringBefore(':')
        port = if (':' in authority) authority.substringAfter(':') else ""
    }
    if (host.isEmpty() || host == "[]") return null
    val portNumber = when {
        port.isEmpty() -> defaultPort(scheme)
        port.length <= 5 && port.all { it in '0'..'9' } -> port.toInt().takeIf { it in 1..65535 } ?: return null
        else -> return null
    }
    return AbsoluteUrl(Origin(scheme, host.lowercase(), portNumber), afterScheme.substring(end))
}

private val SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://""")

private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

/**
 * [s] — a URL's path, query and fragment — with every character a URL may
 * not carry as it is percent-encoded, as UTF-8: all but letters, digits and
 * RFC 3986's marks, a "%" that starts no escape, a "#" after the first. The
 * URL still means what it meant (a browser encodes the same), and the URL
 * parsers of both platforms take it: iOS's refuses "é", "{" or "|" as they
 * are.
 */
internal fun escapeForUri(s: String): String {
    val bytes = s.encodeToByteArray()
    val out = StringBuilder(bytes.size)
    var inFragment = false
    for (i in bytes.indices) {
        val b = bytes[i].toInt() and 0xFF
        val c = b.toChar()
        when {
            b < 0x80 && (c.isAsciiLetterOrDigit() || c in URI_MARKS) -> out.append(c)
            c == '#' && !inFragment -> {
                inFragment = true
                out.append(c)
            }
            c == '%' && i + 2 < bytes.size && isHex(bytes[i + 1]) && isHex(bytes[i + 2]) -> out.append(c)
            else -> out.percent(b)
        }
    }
    return out.toString()
}

/** RFC 3986's unreserved marks and reserved characters but "#", "[" and
 *  "]": "#" is the fragment's once, the brackets an IPv6 host's only. */
private const val URI_MARKS = "-._~:/?@!$&'()*+,;="

/**
 * JavaScript's encodeURIComponent: [s] as UTF-8, every byte but a letter, a
 * digit and - _ . ! ~ * ' ( ) percent-encoded. What a slot URL's {q} becomes,
 * so a search carries into an addon's page as chino-web carries it.
 */
internal fun encodeUriComponent(s: String): String {
    val out = StringBuilder(s.length)
    for (byte in s.encodeToByteArray()) {
        val b = byte.toInt() and 0xFF
        val c = b.toChar()
        if (b < 0x80 && (c.isAsciiLetterOrDigit() || c in COMPONENT_MARKS)) out.append(c) else out.percent(b)
    }
    return out.toString()
}

private const val COMPONENT_MARKS = "-_.!~*'()"

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

private fun isHex(byte: Byte): Boolean = byte.toInt().toChar().let { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }

private const val HEX = "0123456789ABCDEF"

private fun StringBuilder.percent(b: Int) {
    append('%').append(HEX[b shr 4]).append(HEX[b and 0xF])
}
