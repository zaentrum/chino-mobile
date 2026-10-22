package cloud.nalet.chino.mobile.cast

import cloud.nalet.chino.mobile.data.api.SidecarSubtitle
import cloud.nalet.chino.mobile.data.api.artworkUrl
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.data.model.episodeCode
import cloud.nalet.chino.mobile.sha256Hex
import cloud.nalet.chino.mobile.ui.player.SubtitleKind
import cloud.nalet.chino.mobile.ui.player.SubtitleLabelInput
import cloud.nalet.chino.mobile.ui.player.autoSubtitleTrack
import cloud.nalet.chino.mobile.ui.player.languageTag
import cloud.nalet.chino.mobile.ui.player.sidecarForced
import cloud.nalet.chino.mobile.ui.player.subtitleKind
import cloud.nalet.chino.mobile.ui.player.subtitleLabels

/*
 * What a TV is told to play when the phone casts a title: the receiver's own
 * master, what it shows of the title, its subtitles, and where it starts. Pure,
 * so commonTest pins it; the Android side turns it into the Cast SDK's
 * MediaInfo (CastMediaInfo.kt).
 *
 * The receiver is Google's Default Media Receiver, a web player on the TV. It
 * fetches the master itself, with the stream token in the URL - the token is
 * accepted on the media and artwork routes without the OIDC header - and with
 * the caps of the TV, not of the phone: chino-stream decides what to serve
 * from them.
 */

/**
 * The caps a Cast receiver is served with: H.264 up to 1080 lines and AAC
 * stereo, what every Cast device plays. chino-stream serves them a title's
 * H.264 package where there is one, else an H.264 rung of the live encode,
 * so a title whose original the TV could not decode still plays.
 */
const val RECEIVER_CAPS: String = "avc:1080,aac"

/** The HLS content type the receiver is told. */
const val CAST_CONTENT_TYPE: String = "application/x-mpegURL"

/** The content type of a side-loaded subtitle: chino-api's sidecar route
 *  serves every text sidecar as WebVTT (a SubRip file too, at its .vtt URL). */
const val CAST_TEXT_TRACK_TYPE: String = "text/vtt"

/** The first id of the side-loaded subtitles. The receiver numbers the
 *  tracks it finds in the master itself, from 1; ours stay clear of them. */
const val CAST_TEXT_TRACK_FIRST_ID: Long = 1000L

/** What the TV plays: a film or one of a title's extras, or an episode. */
enum class CastMediaKind { Movie, Episode }

/** Which way an image is cut. */
enum class CastImageShape { Landscape, Portrait }

/** One image of the title, with the size the receiver is told. */
data class CastImage(val url: String, val width: Int, val height: Int, val shape: CastImageShape)

/** What the TV, the bar, the full controls and the notification show. */
data class CastMetadata(
    val kind: CastMediaKind,
    /** One line: "The Quiet Orchard · S01E03" for an episode, the title
     *  otherwise ("Sintel", "Sintel · Trailer"). */
    val title: String,
    /** The episode's own title under it; null for anything else. */
    val subtitle: String?,
    val seriesTitle: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    /** The backdrop first, then the poster: what the TV shows first. */
    val images: List<CastImage>,
)

/** A sidecar subtitle side-loaded on the TV. */
data class CastTextTrack(
    val id: Long,
    /** The signed WebVTT URL. */
    val url: String,
    /** BCP 47 ("en", "pt-BR"); "und" for a track that names no language. */
    val language: String,
    /** As the app's subtitle menus name it ("English · SDH"). */
    val name: String,
    val forced: Boolean,
    /** The sidecar's id at chino-api. */
    val sidecarId: String,
)

/**
 * The marker the phone puts on what it casts (the media's custom data). Every
 * sender on the network sees the TV's media; this says which title it is and
 * whose: the [owner] key of the account and server that cast it. Only that
 * phone, signed in as that account, writes the position the TV plays
 * ([writesProgress]: a title whose saved position could be read, not an
 * extra).
 */
data class CastTag(
    val itemId: String,
    val extraId: String?,
    val owner: String,
    val writesProgress: Boolean,
) {
    companion object {
        /** The custom data's key the tag sits under. */
        const val KEY = "chino"
        const val ITEM = "item"
        const val EXTRA = "extra"
        const val OWNER = "owner"
        const val PROGRESS = "progress"
    }
}

/** An account's owner key on a server: not the account's id itself, which
 *  every sender on the network would read. */
fun castOwnerKey(apiBase: String, accountId: String): String =
    sha256Hex(apiBase.trimEnd('/') + "|" + accountId).take(16)

/** Everything a load request carries. */
data class CastMedia(
    val itemId: String,
    val extraId: String?,
    /** The receiver's master ([receiverMasterUrl]). */
    val contentUrl: String,
    /** The title's length, null when it is not known. */
    val durationMs: Long?,
    /** Where the TV starts. */
    val startMs: Long,
    /** Whether the TV plays from there, or waits paused. */
    val autoplay: Boolean,
    val metadata: CastMetadata,
    val textTracks: List<CastTextTrack>,
    /** The subtitle that is on as the TV starts; null for none. */
    val activeTextTrackId: Long?,
    /** Whether the position the TV plays is written back ([CastTag]). */
    val writesProgress: Boolean,
) {
    /** The marker for this media, of the account [owner] ([castOwnerKey]). */
    fun tag(owner: String): CastTag = CastTag(itemId, extraId, owner, writesProgress)
}

/** Which subtitle the TV starts with. */
sealed interface CastSubtitleStart {
    /** The default rule, as the app's player starts: [autoSubtitleTrack]. */
    data class Auto(val audioLang: String?, val subtitlePref: String?, val audioPref: String?) : CastSubtitleStart

    /** The phone's player showed none. */
    data object Off : CastSubtitleStart

    /** The phone's player showed this sidecar. */
    data class Sidecar(val sidecarId: String) : CastSubtitleStart
}

/** What the title's player knows as it casts. */
data class CastMediaInput(
    /** The API base, ".../api". */
    val apiBase: String,
    /** The session's stream token, which signs the master, the sidecars and
     *  the artwork. */
    val streamToken: String,
    val itemId: String,
    /** The extra played instead of the title (its trailer); null: the title. */
    val extraId: String? = null,
    /** The phone's own master of it, at Auto ([receiverMasterUrl] makes the
     *  TV's from it). */
    val phoneMasterUrl: String,
    /** The title's detail: what it is called, its artwork, its series. */
    val item: Item?,
    val seriesTitle: String?,
    /** The heading an extra plays under ("Sintel · Trailer"); null for a title. */
    val extraHeading: String? = null,
    val durationMs: Long?,
    val startMs: Long,
    val autoplay: Boolean,
    /** The title's sidecars, signed. */
    val sidecars: List<SidecarSubtitle>,
    val subtitleStart: CastSubtitleStart,
    val writesProgress: Boolean,
    val receiverCaps: String = RECEIVER_CAPS,
)

/** The load request for [input]. */
fun castMediaFor(input: CastMediaInput): CastMedia {
    val tracks = castTextTracks(input.sidecars)
    return CastMedia(
        itemId = input.itemId,
        extraId = input.extraId,
        contentUrl = receiverMasterUrl(input.phoneMasterUrl, input.receiverCaps),
        durationMs = input.durationMs?.takeIf { it > 0 },
        startMs = input.startMs.coerceAtLeast(0L),
        autoplay = input.autoplay,
        metadata = castMetadata(input),
        textTracks = tracks,
        activeTextTrackId = activeTextTrack(tracks, input.subtitleStart)?.id,
        writesProgress = input.writesProgress,
    )
}

/**
 * The TV's master from the phone's: the same title, the same stream token,
 * with the receiver's [caps] in place of the phone's and no `q` - the master
 * then decides what the TV is served (chino-stream copies only without a `q`
 * or at "high"), as it does for the phone at Auto. Every other parameter stays
 * as it was, in its order.
 */
fun receiverMasterUrl(phoneMasterUrl: String, caps: String): String {
    val path = phoneMasterUrl.substringBefore('?')
    val query = phoneMasterUrl.substringAfter('?', missingDelimiterValue = "")
    val kept = query.split('&').filter { it.isNotEmpty() }.filter {
        val name = it.substringBefore('=')
        name != "caps" && name != "q"
    }
    val parts = if (caps.isEmpty()) kept else kept + "caps=$caps"
    return if (parts.isEmpty()) path else path + "?" + parts.joinToString("&")
}

private const val BACKDROP_WIDTH = 1280
private const val BACKDROP_HEIGHT = 720
private const val POSTER_WIDTH = 500
private const val POSTER_HEIGHT = 750

/** What the title shows on the TV and in the controls ([CastMetadata]). */
internal fun castMetadata(input: CastMediaInput): CastMetadata {
    val item = input.item
    val images = castImages(input)
    val heading = input.extraHeading
    if (heading != null || item == null || !isEpisode(item)) {
        return CastMetadata(
            kind = CastMediaKind.Movie,
            title = heading ?: item?.title ?: "",
            subtitle = null,
            seriesTitle = null,
            seasonNumber = null,
            episodeNumber = null,
            images = images,
        )
    }
    // S03E18–21 for a file of several episodes: the file plays.
    val code = episodeCode(item.seasonNumber, item.episodeNumber, item.episodeEnd)
    val series = input.seriesTitle?.takeIf { it.isNotBlank() }
    val title = when {
        series != null && code.isNotEmpty() -> "$series · $code"
        series != null -> "$series · ${item.title}"
        code.isNotEmpty() -> "$code · ${item.title}"
        else -> item.title
    }
    return CastMetadata(
        kind = CastMediaKind.Episode,
        title = title,
        // The episode's own title under the line - where the line does not
        // carry it already.
        subtitle = item.title.takeIf { series != null && code.isNotEmpty() && it.isNotBlank() },
        seriesTitle = series,
        seasonNumber = item.seasonNumber,
        episodeNumber = item.episodeNumber,
        images = images,
    )
}

private fun isEpisode(item: Item): Boolean =
    item.kind.equals("episode", ignoreCase = true) || item.seasonNumber != null || item.episodeNumber != null

/**
 * The title's backdrop and poster, signed: an episode's own still and its
 * series' poster; an extra's title's. chino-api names both on every item and
 * answers for one it has no picture of with a 404, which the receiver and the
 * controls draw as no picture.
 */
private fun castImages(input: CastMediaInput): List<CastImage> {
    val item = input.item ?: return emptyList()
    val base = input.apiBase
    val token = input.streamToken
    val backdrop = artworkUrl(base, item.backdropUrl ?: "/api/v1/items/${item.id}/backdrop", token)
    val seriesId = item.parentId?.takeIf { isEpisode(item) }
    val poster = if (seriesId != null) {
        artworkUrl(base, "/api/v1/items/$seriesId/poster", token)
    } else {
        artworkUrl(base, item.posterUrl ?: "/api/v1/items/${item.id}/poster", token)
    }
    return listOfNotNull(
        backdrop?.let { CastImage(it, BACKDROP_WIDTH, BACKDROP_HEIGHT, CastImageShape.Landscape) },
        poster?.let { CastImage(it, POSTER_WIDTH, POSTER_HEIGHT, CastImageShape.Portrait) },
    )
}

/**
 * The sidecars the TV can show: the text ones, as WebVTT, each named as the
 * app's subtitle menus name it. A picture format (PGS, VobSub, DVB) has no
 * place on the receiver and is left out.
 */
fun castTextTracks(sidecars: List<SidecarSubtitle>): List<CastTextTrack> {
    val text = sidecars.filter { it.id.isNotBlank() && it.url.isNotBlank() && subtitleKind(it.format) == SubtitleKind.Text }
    val names = subtitleLabels(text.map { SubtitleLabelInput(it.lang, it.label, sidecarForced(it)) })
    return text.mapIndexed { i, s ->
        CastTextTrack(
            id = CAST_TEXT_TRACK_FIRST_ID + i,
            url = s.url,
            language = languageTag(s.lang).ifEmpty { "und" },
            name = names[i],
            forced = sidecarForced(s),
            sidecarId = s.id,
        )
    }
}

/** The track that is on as the TV starts: the phone's sidecar, none, or the
 *  default rule's pick among the TV's tracks. */
fun activeTextTrack(tracks: List<CastTextTrack>, start: CastSubtitleStart): CastTextTrack? = when (start) {
    CastSubtitleStart.Off -> null
    is CastSubtitleStart.Sidecar -> tracks.firstOrNull { it.sidecarId == start.sidecarId }
    is CastSubtitleStart.Auto -> autoSubtitleTrack(
        tracks = tracks,
        lang = { it.language },
        forced = { it.forced },
        text = { true },
        audioLang = start.audioLang,
        subtitlePref = start.subtitlePref,
        audioPref = start.audioPref,
    )
}

/** What an image is for, in the Cast widgets. */
enum class CastArtworkUse {
    /** The bar's thumbnail: a poster, as the design's bar draws it. */
    Thumbnail,

    /** The full controls, the notification, the lock screen: the backdrop. */
    Background,
}

/** The index of the image for [use] among [images] - the shape it wants, else
 *  the first there is; null without any. */
fun pickCastImage(images: List<CastImage>, use: CastArtworkUse): Int? {
    if (images.isEmpty()) return null
    val want = if (use == CastArtworkUse.Thumbnail) CastImageShape.Portrait else CastImageShape.Landscape
    return images.indexOfFirst { it.shape == want }.takeIf { it >= 0 } ?: 0
}

/** The shape of an image of [width] x [height] (a receiver's or another
 *  sender's image, whose shape only its size tells). */
fun castImageShape(width: Int, height: Int): CastImageShape =
    if (height > width) CastImageShape.Portrait else CastImageShape.Landscape
