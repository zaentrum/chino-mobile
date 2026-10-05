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
    // chino-api synthesises both on every item ("/api/v1/items/{id}/poster",
    // its artwork proxy); there is no `artwork_url`. Server-relative — resolve
    // with [cloud.nalet.chino.mobile.data.api.artworkUrl] before loading.
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
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
    /** The title's links to online videos (detail only). A trailer this
     *  server plays is never here: it is one of [extras]. */
    val trailers: List<Trailer> = emptyList(),
    /** The title's extras that play from this server — its trailers,
     *  teasers, featurettes, … — in the order a viewer sees them (detail
     *  only; chino-api leaves it out when none plays). */
    val extras: List<Extra> = emptyList(),
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
    /** On a person's filmography (GET /v1/people/{id}) only: that person's
     *  roles on this title, in the catalog's credit order (["actor",
     *  "director"]). Named by [formatRoles]. */
    val roles: List<String> = emptyList(),
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

/**
 * One credit, as chino-api passes katalog-api's cast through: role by role
 * (actor, creator, director, writer, producer, composer, cinematographer,
 * editor, then any other role), billing order within a role, at most 20
 * actors and 10 people of every other role. Every optional field is omitted
 * when unknown. [groupCredits] turns the list into the detail page's blocks.
 */
@Serializable
data class CastMember(
    val name: String,
    /** An open token: actor, creator, director, writer, producer, composer,
     *  cinematographer, editor, or any other ([roleOf] — none is "actor"). */
    val role: String? = null,
    // Stable katalog person id. chino-api now stamps `person_id` on each cast
    // entry so the name can deep-link to the Person/Filmography surface. Null on
    // older payloads (or unmatched credits) — the UI skips the link then.
    @SerialName("person_id") val personId: String? = null,
    /** The job within the role ("Screenplay"). */
    val job: String? = null,
    /** The part an actor plays. */
    val character: String? = null,
    /** Billing order within the role, 0 first. */
    val order: Int? = null,
    /** How many episodes of a series the credit covers. */
    @SerialName("episode_count") val episodeCount: Int? = null,
)

@Serializable
data class Trailer(
    val url: String,
    val site: String? = null,
    @SerialName("external_id") val externalId: String? = null,
    val title: String? = null,
)

/**
 * One of a movie's or a series' extras, as chino-api lists it with the
 * title's detail: a trailer, a teaser, a featurette, … — a file of its own,
 * packaged for streaming apart from the title. Every field has a default, so
 * an extra short of one never fails the whole item; [playable] says whether
 * it can be played. An extra has no progress, watched, segments, trickplay
 * or play info of its own.
 */
@Serializable
data class Extra(
    val id: String = "",
    /** trailer, teaser, featurette, behind-the-scenes, making-of,
     *  deleted-scene, interview, gag-reel, short or other; a kind the app
     *  does not know is skipped. */
    val kind: String = "",
    val title: String = "",
    /** BCP 47, when known ("en"). */
    val language: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    /** Set on a series' extra of one season (0 the specials). */
    @SerialName("season_number") val seasonNumber: Int? = null,
    /** It plays from this server — chino-api always says so. */
    val local: Boolean = false,
    /** The extra's HLS master from the server root
     *  ("/api/v1/items/{id}/extras/{extraId}/play/master.m3u8"), asked for
     *  as a title's master is: `?stream=<token>&caps=<caps>`. Resolve with
     *  [cloud.nalet.chino.mobile.data.api.artworkUrl]. */
    @SerialName("play_path") val playPath: String = "",
) {
    /** It can be played here: it has an id and a master, from this server. */
    val playable: Boolean get() = id.isNotBlank() && local && playPath.isNotBlank()
}

/** The `{ items }` envelope of chino-api's item lists (/v1/items,
 *  /v1/items/{id}/similar, /v1/me/watched). There is no page token: lists
 *  page by `offset` ([cloud.nalet.chino.mobile.data.paging.Paged]). */
@Serializable
data class ItemsPage(
    val items: List<Item> = emptyList(),
)

/** GET /v1/me echoes the caller's OIDC subject and nothing else; the
 *  account's name and email come from the IdP's userinfo, not from here. */
@Serializable
data class Me(
    val sub: String,
)
