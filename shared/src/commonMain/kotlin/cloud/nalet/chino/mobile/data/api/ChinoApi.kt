package cloud.nalet.chino.mobile.data.api

import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.data.model.ItemsPage
import cloud.nalet.chino.mobile.data.model.Me
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Mirrors chino-api/internal/http/router.go and chino-androidtv's ChinoApi
 * interface field-for-field. If chino-api gets codegen later, drop this in
 * favour of the generated client.
 */
class ChinoApi(private val http: HttpClient) {
    suspend fun listItems(
        pageToken: String? = null,
        limit: Int? = null,
        q: String? = null,
        type: String? = null,
        genre: String? = null,
        yearMin: Int? = null,
        yearMax: Int? = null,
        ratingMin: Double? = null,
        sort: String? = null,
        unwatched: Boolean = false,
    ): ItemsPage = http.get("v1/items") {
        pageToken?.let { parameter("page_token", it) }
        limit?.let { parameter("limit", it) }
        q?.let { parameter("q", it) }
        type?.let { parameter("type", it) }
        genre?.let { parameter("genre", it) }
        yearMin?.let { parameter("year_min", it) }
        yearMax?.let { parameter("year_max", it) }
        ratingMin?.let { parameter("rating_min", it) }
        sort?.let { parameter("sort", it) }
        // #189: Home rails/hero request unwatched=true so finished titles drop
        // out and the rail backfills from later pages. Browse + Search leave
        // this false so watched titles stay findable for a rewatch.
        if (unwatched) parameter("unwatched", "true")
    }.body()

    suspend fun listGenres(): GenresResponse = http.get("v1/genres").body()

    suspend fun getItem(id: String): Item = http.get("v1/items/$id").body()

    /** People (cast & crew) name search. Accent/case-insensitive on the
     *  server; the client renders the server order as-is (no re-ranking).
     *  Mirrors chino-web's usePeople — GET /v1/people?q=&limit=. */
    suspend fun searchPeople(q: String, limit: Int? = null): PeopleResponse =
        http.get("v1/people") {
            parameter("q", q)
            limit?.let { parameter("limit", it) }
        }.body()

    /** A single person's header + filmography. The items carry the standard
     *  poster/backdrop + watched_at so the existing MediaCard renders them.
     *  GET /v1/people/{id}?limit= → 404 when the id is unknown (surfaces as a
     *  thrown Ktor exception the caller maps to a "not found" state). */
    suspend fun getPerson(id: String, limit: Int? = null): PersonDetail =
        http.get("v1/people/$id") {
            limit?.let { parameter("limit", it) }
        }.body()

    /** Full Item objects the current user has watched end-to-end (each carries
     *  watched_at). Zap dedups its candidate pool against these ids. Mirrors
     *  chino-web GET /v1/me/watched ({ items: [Item] }); reuses ItemsPage. */
    suspend fun watched(limit: Int? = null): ItemsPage =
        http.get("v1/me/watched") {
            limit?.let { parameter("limit", it) }
        }.body()

    /** Ids of items with a finished CMAF package (instant first segment, no
     *  cold transcode). Zap filters its candidate pool to these so the muted
     *  channel-surf teaser never stalls on a 0:00 ffmpeg cold-start. chino-api
     *  proxies this to chino-stream (router.go GET /v1/play/packaged-ids). */
    suspend fun packagedIds(): PackagedIdsResponse =
        http.get("v1/play/packaged-ids").body()

    /** Fire-and-forget warm of the next Zap card's transcode pipeline. chino-
     *  stream returns 202 immediately and warms window 0 on its warm-only
     *  ffmpeg pool. Mirrors chino-web's ZapSection prewarm POST; best-effort —
     *  a 404 (older chino-stream without the endpoint) is swallowed by the
     *  caller. caps + q must match the card's actual play URL so the warm
     *  primes the right rung. */
    suspend fun prewarm(id: String, caps: String? = null, quality: String? = null, seekSec: Int? = null) {
        http.post("v1/items/$id/play/prewarm") {
            caps?.let { parameter("caps", it) }
            quality?.let { parameter("q", it) }
            // Seek hint so chino-stream warms the segment the player will
            // actually start on (Zap seeks to a random mid-scene point), not
            // just segment 0.
            seekSec?.takeIf { it > 0 }?.let { parameter("t", it.toString()) }
        }
    }

    suspend fun me(): Me = http.get("v1/me").body()

    /** Mints a 6-hour HMAC-signed token used as `?stream=<token>` on playback
     *  URLs. The OIDC bearer rotates on silent renew; the stream token
     *  doesn't, so the master URL stays stable across renews. */
    suspend fun mintStreamToken(): StreamTokenResponse =
        http.post("v1/me/stream-token").body()

    /** Returns `{ position_sec }`, 0 if never watched. */
    suspend fun getProgress(id: String): ProgressResponse =
        http.get("v1/items/$id/progress").body()

    suspend fun continueWatching(): ContinueWatchingResponse =
        http.get("v1/me/continue-watching").body()

    /** Upserts resume position. Called every ~10s + once on player dispose. */
    suspend fun postProgress(id: String, body: ProgressBody) {
        http.post("v1/items/$id/progress") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    /** Marks the item as fully watched — drives the green "watched" badge on
     *  posters + lets continue-watching substitute the next episode. Mirrors
     *  chino-web's useWatchedToggle POST branch. */
    suspend fun postWatched(id: String) {
        http.post("v1/me/items/$id/watched")
    }

    /** Clears the fully-watched flag (un-watch). Mirrors chino-web's
     *  useWatchedToggle DELETE branch — the detail "watched" toggle and the
     *  per-episode pip both flip back through here. */
    suspend fun deleteWatched(id: String) {
        http.delete("v1/me/items/$id/watched")
    }

    /** Batched playback telemetry — chino-api logs each event for observability. */
    suspend fun postTelemetry(body: TelemetryBatch) {
        http.post("v1/play/events") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    suspend fun getWatchlist(): UserFlagList = http.get("v1/me/watchlist").body()
    suspend fun addToWatchlist(id: String) { http.put("v1/me/watchlist/$id") }
    suspend fun removeFromWatchlist(id: String) { http.delete("v1/me/watchlist/$id") }

    // ---- Multiple named watchlists ----------------------------------------
    // The default list ("Watchlist", isDefault=true) is created lazily by the
    // server on first access and always sorts FIRST in the lists response. The
    // getWatchlist()/addToWatchlist()/removeFromWatchlist() calls above are the
    // back-compat default-list routes (unchanged); the calls below drive the
    // lists-aware surface + the add-to-list picker. Mirrors the frozen contract
    // shared with chino-web/chino-androidtv field-for-field.

    /** Default list first, then the rest by createdAt asc. */
    suspend fun listWatchlists(): WatchlistsResponse = http.get("v1/me/watchlists").body()

    /** 201 + the created list. Server validates: trim, 1..60 chars, max 50
     *  lists/user (409 "too many lists"), case-insensitive duplicate name
     *  (409 "name exists"), empty (400). The caller surfaces a non-2xx as a
     *  thrown Ktor exception and keeps the dialog open. */
    suspend fun createWatchlist(name: String): Watchlist =
        http.post("v1/me/watchlists") {
            contentType(ContentType.Application.Json)
            setBody(WatchlistNameBody(name))
        }.body()

    /** Rename; same validation as create. Renaming the default keeps
     *  isDefault=true. 404 when not the caller's list. */
    suspend fun renameWatchlist(listId: String, name: String): Watchlist =
        http.patch("v1/me/watchlists/$listId") {
            contentType(ContentType.Application.Json)
            setBody(WatchlistNameBody(name))
        }.body()

    /** Cascades its items. Deleting the default list -> 409 "cannot delete
     *  default"; 404 when not the caller's. */
    suspend fun deleteWatchlist(listId: String) { http.delete("v1/me/watchlists/$listId") }

    /** A single list's header + its item ids (newest-added first). */
    suspend fun getWatchlist(listId: String): WatchlistDetail =
        http.get("v1/me/watchlists/$listId").body()

    /** Idempotent add. 404 when the list isn't the caller's. */
    suspend fun addItemToList(listId: String, itemId: String) {
        http.put("v1/me/watchlists/$listId/items/$itemId")
    }

    /** Idempotent remove. */
    suspend fun removeItemFromList(listId: String, itemId: String) {
        http.delete("v1/me/watchlists/$listId/items/$itemId")
    }

    /** Which of the caller's lists each requested item belongs to. Powers the
     *  add-to-list picker checkmarks + the "saved" badge on cards. Items in no
     *  list may be omitted from the map (or map to []). */
    suspend fun getMemberships(ids: List<String>): MembershipsResponse {
        if (ids.isEmpty()) return MembershipsResponse()
        return http.get("v1/me/watchlists/memberships") {
            parameter("ids", ids.joinToString(","))
        }.body()
    }

    suspend fun getLikes(): UserFlagList = http.get("v1/me/likes").body()
    suspend fun addLike(id: String) { http.put("v1/me/likes/$id") }
    suspend fun removeLike(id: String) { http.delete("v1/me/likes/$id") }

    suspend fun seriesEpisodes(id: String): SeriesEpisodes =
        http.get("v1/series/$id/episodes").body()

    suspend fun nextEpisode(id: String): NextEpisode =
        http.get("v1/series/$id/next-episode").body()

    /** "More like this" — chino-api proxies katalog's similarity search.
     *  Empty list when chino-api can't find recommendations. */
    suspend fun similar(id: String, limit: Int = 12): ItemsPage =
        http.get("v1/items/$id/similar") {
            parameter("limit", limit)
        }.body()

    suspend fun itemSegments(id: String): SegmentsResponse =
        http.get("v1/items/$id/segments").body()

    suspend fun itemSubtitles(id: String): SubtitlesResponse =
        http.get("v1/items/$id/subtitles").body()

    /** Pre-playback probe: chino-stream's transcode decision + container/codec
     *  metadata + the ladder of qualities the server can serve. */
    suspend fun playInfo(id: String, caps: String? = null): PlayInfo =
        http.get("v1/items/$id/play/info") {
            caps?.let { parameter("caps", it) }
        }.body()

    /** Raw WebVTT trickplay (scrub-preview thumbnail) cue file for a
     *  packaged item. chino-api proxies this through chino-stream
     *  (router.go GET /items/{id}/play/trickplay/thumbnails.vtt), which
     *  authenticates via the same `?stream=<token>` the master.m3u8 uses.
     *  Returns the body verbatim (no JSON decode) so [parseTrickplayVtt]
     *  can run on it; the caller swallows a 404 (non-packaged items have
     *  no sprites) and degrades to time-only scrubbing. The sprite JPGs
     *  the cues reference live at the sibling
     *  `…/play/trickplay/{sprite}?stream=<token>` path, loaded by Coil. */
    suspend fun trickplayVtt(id: String, streamToken: String): String =
        http.get("v1/items/$id/play/trickplay/thumbnails.vtt") {
            parameter("stream", streamToken)
        }.bodyAsText()

    /** Files a bug report — chino-api opens (or dedup-appends to) a
     *  ticket on the connected server's issue system and answers 201 (new) / 200 (duplicate, comment
     *  appended). Multipart: "report" JSON part + optional "screenshot"
     *  image part (server rejects > 3 MB). Non-2xx surfaces as
     *  [FeedbackSubmitException] so BugReporter can swallow auto-report
     *  failures silently while the manual dialog maps 429/503 onto
     *  plain-language errors. */
    suspend fun submitFeedback(
        report: FeedbackReport,
        screenshot: ByteArray? = null,
        screenshotMime: String = "image/jpeg",
    ): FeedbackResponse {
        val resp = http.post("v1/feedback") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "report",
                            feedbackJson.encodeToString(FeedbackReport.serializer(), report),
                            Headers.build {
                                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            },
                        )
                        if (screenshot != null) {
                            val ext = if (screenshotMime == "image/png") "png" else "jpg"
                            append(
                                "screenshot",
                                screenshot,
                                Headers.build {
                                    append(HttpHeaders.ContentType, screenshotMime)
                                    append(HttpHeaders.ContentDisposition, "filename=\"screenshot.$ext\"")
                                },
                            )
                        }
                    },
                ),
            )
        }
        if (!resp.status.isSuccess()) throw FeedbackSubmitException(resp.status.value)
        return resp.body()
    }
}

/** Serializes the feedback "report" part by hand (it's a multipart part, not
 *  a request body, so ContentNegotiation doesn't apply). explicitNulls=false
 *  omits the optional title/fingerprint instead of sending JSON nulls. */
private val feedbackJson = Json { explicitNulls = false }

@Serializable
data class Episode(
    val id: String,
    val title: String,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("parent_id") val parentId: String? = null,
    // RFC3339; non-null when the current user has watched this episode
    // end-to-end. Drives the green watched-pip in EpisodesList.
    @SerialName("watched_at") val watchedAt: String? = null,
    // katalog emits the synopsis as `description` (matching the main Item
    // model); a bare `overview` here never bound and left every episode
    // blank. duration_ms feeds the right-aligned runtime, same as web.
    @SerialName("description") val overview: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val year: Int? = null,
)

@Serializable
data class Season(
    val season: Int,
    val episodes: List<Episode> = emptyList(),
)

@Serializable
data class SeriesEpisodes(
    val seasons: List<Season> = emptyList(),
)

@Serializable
data class NextEpisode(
    val id: String? = null,
    val title: String? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
)

@Serializable
data class Segment(
    val kind: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val label: String? = null,
)

@Serializable
data class SegmentsResponse(
    val segments: List<Segment> = emptyList(),
)

@Serializable
data class SidecarSubtitle(
    val id: String,
    val label: String,
    val lang: String,
    val url: String,
    val default: Boolean? = null,
    // `format` selects the renderer. `webvtt`/`srt` flow through the
    // native text-track pipeline; `pgs`/`vobsub`/`dvb` bind a Media3
    // image-subtitle decoder (PgsDecoder for `pgs`). Optional for
    // backward compatibility with older manager-api builds that
    // didn't emit the field — those rows are assumed webvtt.
    val format: String? = null,
)

@Serializable
data class SubtitlesResponse(
    val subtitles: List<SidecarSubtitle> = emptyList(),
)

@Serializable
data class QualityRung(
    /** "high" / "medium" / "low" — matches chino-stream's ?q= parameter. */
    val name: String,
    val label: String,
)

/** Per-stream metadata for embedded audio/subtitle tracks. Shape mirrors
 *  chino-stream/internal/play/ffprobe.go `TrackInfo`. */
@Serializable
data class TrackInfo(
    // Defaulted: the server omits `index` on subtitle_tracks, and a missing
    // required field aborts the whole PlayInfo deserialization (coerceInputValues
    // only rescues nulls, not missing fields). index isn't used for selection.
    val index: Int = 0,
    val codec: String? = null,
    val language: String? = null,
    val title: String? = null,
    val default: Boolean = false,
    val forced: Boolean = false,
    val channels: Int? = null,
)

@Serializable
data class PlayInfo(
    val filename: String? = null,
    val container: String? = null,
    @SerialName("video_codec") val videoCodec: String? = null,
    @SerialName("audio_codec") val audioCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** ffprobe-derived authoritative duration. */
    @SerialName("duration_ms") val durationMs: Long? = null,
    /** "passthrough" / "remux" / "transcode" / "packaged". */
    val mode: String? = null,
    val reason: String? = null,
    /** "libx264" / "h264_nvenc" when mode=transcode; null otherwise. */
    val encoder: String? = null,
    val qualities: List<QualityRung> = emptyList(),
    @SerialName("default_quality") val defaultQuality: String? = null,
    /** Embedded audio tracks (drives the language chip in the player
     *  chrome). chino-api emits as `audio_tracks`. */
    @SerialName("audio_tracks") val audioTracks: List<TrackInfo> = emptyList(),
    /** Embedded subtitle tracks (drives the captions menu). */
    @SerialName("subtitle_tracks") val subtitleTracks: List<TrackInfo> = emptyList(),
)

/** One row of the People (cast & crew) search results. `credits` is the
 *  number of titles this person is credited on — rendered as "· N titles".
 *  Mirrors chino-web's PersonSummary field-for-field. */
@Serializable
data class Person(
    val id: String,
    val name: String,
    val credits: Int = 0,
)

@Serializable
data class PeopleResponse(
    val people: List<Person> = emptyList(),
    val total: Int = 0,
)

/** GET /v1/people/{id}: the person's name + their filmography as standard
 *  catalogue items (poster/backdrop/watched_at). Mirrors chino-web's
 *  PersonDetail. */
@Serializable
data class PersonDetail(
    val id: String,
    val name: String,
    val items: List<Item> = emptyList(),
)

@Serializable
data class UserFlagList(val items: List<String> = emptyList())

/** A named watchlist header. The default list is named "Watchlist" with
 *  isDefault=true and always exists (created lazily server-side). */
@Serializable
data class Watchlist(
    val id: String,
    val name: String,
    val itemCount: Int = 0,
    val isDefault: Boolean = false,
    val createdAt: String? = null,
)

@Serializable
data class WatchlistsResponse(val lists: List<Watchlist> = emptyList())

/** GET /me/watchlists/{listId}: the list header + its item ids (newest-added
 *  first). itemCount isn't echoed here — the caller derives it from items. */
@Serializable
data class WatchlistDetail(
    val id: String,
    val name: String,
    val isDefault: Boolean = false,
    val items: List<String> = emptyList(),
)

@Serializable
private data class WatchlistNameBody(val name: String)

/** GET /me/watchlists/memberships: itemId -> [listId, ...] for the caller's
 *  lists only. Items in no list may be omitted. */
@Serializable
data class MembershipsResponse(
    val memberships: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class PackagedIdsResponse(val ids: List<String> = emptyList())

@Serializable
data class GenresResponse(val genres: List<String> = emptyList())

@Serializable
data class StreamTokenResponse(
    @SerialName("stream_token") val token: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class ProgressResponse(
    @SerialName("position_sec") val positionSec: Int = 0,
)

@Serializable
data class ProgressBody(
    @SerialName("position_sec") val positionSec: Int,
    @SerialName("duration_sec") val durationSec: Int,
)

@Serializable
data class TelemetryEvent(
    val ts: Long,
    val kind: String,
    val itemId: String? = null,
    val payload: Map<String, String> = emptyMap(),
)

@Serializable
data class TelemetryBatch(
    val sessionId: String,
    val events: List<TelemetryEvent>,
)

@Serializable
data class ContinueWatchingItem(
    val id: String,
    val title: String,
    @SerialName("position_sec") val positionSec: Int = 0,
    @SerialName("duration_sec") val durationSec: Int = 0,
    /** True when chino-api substituted the next episode after one finished. */
    @SerialName("up_next") val upNext: Boolean = false,
    @SerialName("series_title") val seriesTitle: String? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    val type: String? = null,
    // The CW feed embeds the full catalogue item, so year/rating are
    // already on the wire; parsed for the movie meta line ("year • rating")
    // to match chino-web's CW cards.
    val year: Int? = null,
    val rating: Double? = null,
    // RFC3339; non-null once the user has finished this item end-to-end.
    // The CW feed only returns in-progress rows so this is normally null,
    // but it's stamped on the payload (matching chino-web's
    // ContinueWatchingEntry) so the model stays field-for-field with the
    // catalogue Item / Episode shapes.
    @SerialName("watched_at") val watchedAt: String? = null,
)

@Serializable
data class ContinueWatchingResponse(
    val items: List<ContinueWatchingItem> = emptyList(),
)

/** The "report" JSON part of POST /v1/feedback. Shape matches the contract
 *  shared with chino-web/chino-androidtv field-for-field: source is
 *  "mobile" here, kind is "manual" | "error" | "crash" | "player",
 *  fingerprint is the lowercase sha-256 of the normalized error signature
 *  (omitted for manual reports), context is a flat string map. */
@Serializable
data class FeedbackReport(
    val source: String,
    val kind: String,
    val title: String? = null,
    val description: String,
    val fingerprint: String? = null,
    val context: Map<String, String> = emptyMap(),
)

@Serializable
data class FeedbackResponse(
    /** Server-side bug ticket id. */
    val id: Long,
    val url: String,
    /** True when the server deduplicated onto an existing ticket (HTTP 200)
     *  instead of opening a new one (HTTP 201). */
    val duplicate: Boolean = false,
)

/** Non-2xx from POST /v1/feedback. 429 = per-user rate limit, 503 = the
 *  server has no issue-system integration configured; the manual dialog maps both
 *  onto plain-language messages, auto reports swallow everything. */
class FeedbackSubmitException(val status: Int) :
    Exception("feedback submit failed with HTTP $status")
