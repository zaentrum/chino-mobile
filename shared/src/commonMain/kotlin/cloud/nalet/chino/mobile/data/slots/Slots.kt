package cloud.nalet.chino.mobile.data.slots

import cloud.nalet.chino.mobile.data.encodeUriComponent
import cloud.nalet.chino.mobile.data.ownServerHref
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/*
 * What an addon's slot row may make the app do — chino-web's
 * lib/extensions.ts, ported. Addons contribute rows to named slots in
 * portal-api's registry; chino-api serves a slot's rows (GET
 * /api/v1/extensions?slot=) and the app draws each as one of its own buttons
 * (ui/slots). The rows come from elsewhere, so a row is drawn only when what
 * it points at is safe behind such a button, every field checked before it
 * is used:
 *  - a `link` opens a page of this server in the system browser
 *    ([ownServerHref]);
 *  - an `action` is a POST, with the person's bearer, to the portal's app
 *    proxy on this server (/api/portal/apps/<key>/…) — and nowhere else.
 * A row of another kind, or one that points anywhere else, draws nothing, as
 * does a slot no addon contributes to. What a button says is the addon's.
 */

/** The slot a search that found no titles and no people draws. */
const val SLOT_SEARCH_EMPTY = "search.empty"

/** The two kinds a row may be. */
enum class SlotKind { Link, Action }

/** A row that passed: what the slot draws, with its address resolved. */
data class SlotButton(
    val key: String,
    val kind: SlotKind,
    val label: String,
    /** One of [SLOT_ICON_NAMES]. */
    val icon: String,
    /** The page a link opens, or the URL an action POSTs to. */
    val href: String,
)

/** The icons a row may name: the portal's palette (zaentrum-portal
 *  src/lib/icons.tsx), so a row shows the same glyph on the launchpad, in
 *  chino-web and here. Any other name is the puzzle, the portal's icon for an
 *  installed addon. */
val SLOT_ICON_NAMES: List<String> = listOf(
    "library", "radar", "download", "tv", "music", "clapperboard", "settings", "layout-grid", "server", "boxes",
    "globe", "wrench", "file-text", "image", "list-video", "users", "gauge", "database", "puzzle",
)

/** The portal's app proxy: /api/portal/apps/<key>/… reaches an addon's own
 *  API, forwarding the viewer's bearer for the addon to authorise. */
const val PORTAL_APP_PROXY = "/api/portal/apps/"

/** {var} tokens in a slot URL, replaced by the value encoded as a URL
 *  component; an unknown one by nothing. */
fun substitute(url: String, vars: Map<String, String>): String =
    PLACEHOLDER.replace(url) { match -> vars[match.groupValues[1]]?.let(::encodeUriComponent).orEmpty() }

private val PLACEHOLDER = Regex("""\{([A-Za-z0-9_]+)\}""")

/** A row's icon as a palette name: kebab or lower case as the portal stores
 *  it ("list-video"), or as lucide spells the export ("ListVideo"). */
fun slotIconName(raw: String?): String {
    val name = raw?.trim()
        ?.replace(CASE_CHANGE, "\$1-\$2")
        ?.replace(SPACES, "-")
        ?.lowercase()
    return if (name != null && name in SLOT_ICON_NAMES) name else "puzzle"
}

private val CASE_CHANGE = Regex("([a-z0-9])([A-Z])")
private val SPACES = Regex("""[\s_]+""")

/** The two kinds the app draws; anything else — an unknown kind, an empty
 *  one — is nothing it knows how to show. */
fun slotKind(raw: String?): SlotKind? = when (raw?.trim()?.lowercase()) {
    "link" -> SlotKind.Link
    "action" -> SlotKind.Action
    else -> null
}

/** The page a `link` row may open, or null: a page of this server
 *  ([ownServerHref]), its {q} filled in before it is checked — so a query
 *  cannot turn it into another host or a script. */
fun slotLinkHref(raw: String?, apiBaseUrl: String, vars: Map<String, String> = emptyMap()): String? {
    if (raw.isNullOrBlank()) return null
    return ownServerHref(substitute(raw.trim(), vars), apiBaseUrl)
}

/**
 * The URL an `action` row may POST to, or null. An action carries the
 * viewer's bearer, so it goes only to the portal's app proxy on this server
 * (/api/portal/apps/<key>/…) and only as a POST (no method means POST): the
 * token never leaves the server, and a row cannot spend it on chino-api's or
 * the portal's own endpoints. A path that would leave the proxy once a
 * browser or a proxy resolved it — a "." or ".." segment, spelled out or
 * encoded, an encoded "/" or "\" — is refused as it is written.
 */
fun slotActionUrl(raw: String?, method: String?, apiBaseUrl: String, vars: Map<String, String> = emptyMap()): String? {
    if (!method.isNullOrEmpty() && method.trim().uppercase() != "POST") return null
    val href = slotLinkHref(raw, apiBaseUrl, vars) ?: return null
    val afterScheme = href.substringAfter("://")
    val path = afterScheme.substring(afterScheme.indexOf('/')).substringBefore('?').substringBefore('#')
    if (path.contains("%2f", ignoreCase = true) || path.contains("%5c", ignoreCase = true)) return null
    if (path.split('/').any { it.replace(ENCODED_DOT, ".") in DOT_SEGMENTS }) return null
    return href.takeIf { ACTION_PATH.matches(path) }
}

private val ENCODED_DOT = Regex("%2e", RegexOption.IGNORE_CASE)
private val DOT_SEGMENTS = setOf(".", "..")
/** [PORTAL_APP_PROXY], an addon's key, and anything under it. */
private val ACTION_PATH = Regex("""^/api/portal/apps/[^/]+(/.*)?$""")

/** The rows of a slot the app can draw, in the order chino-api gave them:
 *  enabled, labelled, of a kind it knows, pointing where that kind may. */
fun slotButtons(rows: JsonElement?, apiBaseUrl: String, vars: Map<String, String> = emptyMap()): List<SlotButton> {
    if (rows !is JsonArray) return emptyList()
    return rows.mapIndexedNotNull { index, row -> slotButton(row, index, apiBaseUrl, vars) }
}

private fun slotButton(row: JsonElement, index: Int, apiBaseUrl: String, vars: Map<String, String>): SlotButton? {
    if (row !is JsonObject || row.boolean("enabled") != true) return null
    val label = row.string("label")?.trim().orEmpty()
    if (label.isEmpty()) return null
    val kind = slotKind(row.string("kind")) ?: return null
    val href = when (kind) {
        SlotKind.Link -> slotLinkHref(row.string("url"), apiBaseUrl, vars)
        SlotKind.Action -> {
            // A method that is there but is no string is no method.
            val method = row["method"]
            if (method != null && method !is JsonNull && row.string("method") == null) return null
            slotActionUrl(row.string("url"), row.string("method"), apiBaseUrl, vars)
        }
    } ?: return null
    return SlotButton(
        key = row.string("key")?.takeIf { it.isNotEmpty() } ?: "row-$index",
        kind = kind,
        label = label,
        icon = slotIconName(row.string("icon")),
        href = href,
    )
}

/** A field that is a JSON string, or null — a number or an object is none. */
private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A field that is a JSON boolean, or null — the string "true" is none. */
private fun JsonObject.boolean(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
