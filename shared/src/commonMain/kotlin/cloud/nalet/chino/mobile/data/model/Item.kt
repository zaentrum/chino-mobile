package cloud.nalet.chino.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Catalogue item returned by chino-api. Mirrors chino-androidtv's Item shape
 * field-for-field — both clients consume the same JSON. Unknown fields are
 * tolerated by the shared `Json { ignoreUnknownKeys = true }` config so a
 * new chino-api field doesn't break the older client.
 */
@Serializable
data class Item(
    val id: String,
    val title: String,
    /**
     * Catalogue type — "movie", "series", "episode", "album", "track" per
     * chino-api/internal/katalog/client.go. JSON field is `type`; the
     * Kotlin property is named `kind` so call sites read naturally.
     */
    @SerialName("type") val kind: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    val year: Int? = null,
    // chino-api emits the long synopsis as JSON field `description` (see
    // chino-api/internal/katalog/client.go:75 `Description string
    // json:"description"`). Without the SerialName mapping, kotlinx
    // serialization looks for `overview` in the payload, finds nothing,
    // and silently leaves the field null — so the HeroBanner's overview
    // Text was always hidden. chino-web reads `description` directly
    // (useHeroPool.ts) so it shows the text correctly. Kotlin property
    // stays `overview` so all existing call sites read naturally.
    @SerialName("description") val overview: String? = null,
    val rating: Double? = null,
    // RFC3339 timestamp; non-null when the current user has watched this
    // item end-to-end. Drives the green "watched" badge on posters.
    @SerialName("watched_at") val watchedAt: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val cast: List<CastMember> = emptyList(),
    val trailers: List<Trailer> = emptyList(),
    /** Free-form genre tags from katalog metadata, e.g.
     *  ["Action & Adventure", "Animation"]. Rendered as pill chips on
     *  the Detail page (web: DetailPage.tsx L160-170). */
    val genres: List<String> = emptyList(),
    /** For episodes, the parent series id. Null for movies / series-level items. */
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    // Optional short marketing line under the title. chino-api emits as
    // `tagline,omitempty`; rendered by DetailScreen below the title in
    // italic #8B949E (web: DetailPage.tsx L132-134).
    val tagline: String? = null,
    // Available subtitle tracks — rendered in the Detail footer as a
    // comma-separated list of `label || lang` (web: DetailPage.tsx L261-268).
    val subtitles: List<Subtitle> = emptyList(),
    // Per-item segment summary (intro/credits/recap markers). When
    // non-null + count>0, Detail renders the "Analyzed" footer column
    // listing which segments are present (web: DetailPage.tsx L269-278).
    val segments: SegSummary? = null,
)

@Serializable
data class Subtitle(
    val id: String,
    val lang: String,
    val label: String? = null,
    val format: String? = null,
    val default: Boolean = false,
)

@Serializable
data class SegSummary(
    @SerialName("has_intro") val hasIntro: Boolean = false,
    @SerialName("has_credits") val hasCredits: Boolean = false,
    @SerialName("has_recap") val hasRecap: Boolean = false,
    val count: Int = 0,
)

@Serializable
data class CastMember(
    val name: String,
    /** "director", "actor", or null. */
    val role: String? = null,
    // Stable katalog person id. chino-api now stamps `person_id` on each cast
    // entry so the name can deep-link to the Person/Filmography surface. Null on
    // older payloads (or unmatched credits) — the UI skips the link then.
    @SerialName("person_id") val personId: String? = null,
)

@Serializable
data class Trailer(
    val url: String,
    val site: String? = null,
    val title: String? = null,
)

@Serializable
data class ItemsPage(
    val items: List<Item> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String? = null,
)

@Serializable
data class Me(
    val sub: String,
    val email: String? = null,
    val name: String? = null,
)
