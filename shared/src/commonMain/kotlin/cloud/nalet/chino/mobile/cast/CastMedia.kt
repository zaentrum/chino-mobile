package cloud.nalet.chino.mobile.cast

import cloud.nalet.chino.mobile.data.api.SidecarSubtitle
import cloud.nalet.chino.mobile.data.api.artworkUrl
import cloud.nalet.chino.mobile.data.auth.withStreamToken
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.data.model.episodeCode
import cloud.nalet.chino.mobile.ui.player.HlsMaster
import cloud.nalet.chino.mobile.ui.player.SubtitleKind
import cloud.nalet.chino.mobile.ui.player.SubtitleLabelInput
import cloud.nalet.chino.mobile.ui.player.isSdhSubtitle
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
 * from them. Every link the TV gets is signed with a token minted for it
 * ([CastToken]), not with the one the phone's player started with.
 */

/**
 * The caps a Cast receiver is served with: H.264 up to 1080 lines and AAC
 * stereo, what every Cast device with a screen plays. chino-stream serves
 * them a title's H.264 package where there is one, else an H.264 rung of the
 * live encode, so a title whose original the TV could not decode still plays.
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
    /** For the deaf and hard of hearing ([isSdhSubtitle]): told apart from a
     *  plain one of its language. */
    val sdh: Boolean = false,
)

/**
 * The marker the phone puts on what it casts (the media's custom data). Every
 * sender on the network sees the TV's media; this says which title it is and
 * whose: the [owner] id of the phone and account that cast it (castOwnerId),
 * which says nothing of the account itself. Only that phone, signed in as
 * that account, writes the position the TV plays ([writesProgress]: a title
 * whose saved position could be read, not an extra).
 */
data class CastTag(
    val itemId: String,
    val extraId: String?,
    val owner: String,
    val writesProgress: Boolean,
    /** This load's own id: the same title loaded again - from the start, or
     *  its links signed anew - is new media to the position writes. */
    val load: String,
    /** The earliest the TV's links stop working, by the phone's clock
     *  (epoch ms; [CastToken.expiresAtMs]); 0 where it is not known. */
    val linksExpireAtMs: Long,
) {
    companion object {
        /** The custom data's key the tag sits under. */
        const val KEY = "chino"
        const val ITEM = "item"
        const val EXTRA = "extra"
        const val OWNER = "owner"
        const val PROGRESS = "progress"
        const val LOAD = "load"
        const val LINKS_EXPIRE = "exp"
    }
}

/** A stream token for the TV's links, and the earliest it stops working, by
 *  the phone's clock (epoch ms). */
data class CastToken(val token: String, val expiresAtMs: Long)

/** Everything a load request carries. */
data class CastMedia(
    val itemId: String,
    val extraId: String?,
    /** The receiver's master ([receiverMasterUrl]), signed for the TV. */
    val contentUrl: String,
    /** The title's length, null when it is not known. */
    val durationMs: Long?,
    /** Where the TV starts. */
    val startMs: Long,
    /** Whether the TV plays from there, or waits paused. */
    val autoplay: Boolean,
    val metadata: CastMetadata,
    val textTracks: List<CastTextTrack>,
    /** The side-loaded subtitle that is on as the TV starts; null for none,
     *  or for one of the master's, turned on once the TV has loaded. */
    val activeTextTrackId: Long?,
    /** Whether the position the TV plays is written back ([CastTag]). */
    val writesProgress: Boolean,
    /** This load's own id ([CastTag.load]). */
    val load: String,
    /** When its links stop working at the earliest ([CastToken.expiresAtMs]). */
    val linksExpireAtMs: Long,
) {
    /** The marker for this media, cast by the phone and account [owner]. */
    fun tag(owner: String): CastTag = CastTag(itemId, extraId, owner, writesProgress, load, linksExpireAtMs)
}

/** Which subtitle the TV starts with. */
sealed interface CastSubtitleStart {
    /** The default rule, as the app's player starts (autoSubtitleTrack), for
     *  the audio the TV plays. */
    data class Auto(val subtitlePref: String?, val audioPref: String?) : CastSubtitleStart

    /** The phone's player showed none. */
    data object Off : CastSubtitleStart

    /** The phone's player showed this sidecar - in [lang], forced or not,
     *  for the deaf and hard of hearing or not, by which the TV's rendition
     *  of it is found where the master carries it. */
    data class Sidecar(val sidecarId: String, val lang: String?, val forced: Boolean, val sdh: Boolean = false) : CastSubtitleStart
}

/** What the title's player knows as it casts. */
data class CastMediaInput(
    /** The API base, ".../api". */
    val apiBase: String,
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
    /** The title's sidecars, as the phone's player has them; the TV's are
     *  signed anew. */
    val sidecars: List<SidecarSubtitle>,
    /** The language of the audio the phone played, or starts the title with;
     *  null when it is not known. */
    val audioLang: String? = null,
    /** Settings' audio language ("orig": the title's own). */
    val audioPref: String? = null,
    val subtitleStart: CastSubtitleStart,
    val writesProgress: Boolean,
    /** Load even where the TV plays this title already: Play from start, or
     *  the phone's player handing over where it is. Without it, a title the
     *  TV plays for this phone plays on. */
    val restart: Boolean = false,
    val receiverCaps: String = RECEIVER_CAPS,
) {
    /** What the TV is to play with. */
    val tracks: CastTrackWish get() = CastTrackWish(audioLang, audioPref, subtitleStart)
}

/** The TV's master of [input], signed with [token]. */
fun receiverMasterUrl(input: CastMediaInput, token: String): String =
    withStreamToken(receiverMasterUrl(input.phoneMasterUrl, input.receiverCaps), token)

/**
 * The load request for [input] - load [load] - its links signed with
 * [token]: the master, the sidecars the master does not carry itself
 * ([sidecarsBesideTheMaster]; [master] as the phone read it, null where it
 * could not), the artwork.
 */
internal fun castMediaFor(input: CastMediaInput, token: CastToken, master: HlsMaster?, load: String): CastMedia {
    val sidecars = sidecarsBesideTheMaster(
        input.sidecars.map { it.copy(url = withStreamToken(it.url, token.token)) },
        master?.subtitles.orEmpty(),
    )
    val tracks = castTextTracks(sidecars)
    return CastMedia(
        itemId = input.itemId,
        extraId = input.extraId,
        contentUrl = receiverMasterUrl(input, token.token),
        durationMs = input.durationMs?.takeIf { it > 0 },
        startMs = input.startMs.coerceAtLeast(0L),
        autoplay = input.autoplay,
        metadata = castMetadata(input, token.token),
        textTracks = tracks,
        activeTextTrackId = firstTextTrack(tracks, master, input.tracks),
        writesProgress = input.writesProgress,
        load = load,
        linksExpireAtMs = token.expiresAtMs,
    )
}

/**
 * The TV's master from the phone's: the same title, with the receiver's
 * [caps] in place of the phone's and no `q` - the master then decides what
 * the TV is served (chino-stream copies only without a `q` or at "high"), as
 * it does for the phone at Auto. Every other parameter stays as it was, in
 * its order.
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

/** What the title shows on the TV and in the controls ([CastMetadata]), its
 *  artwork signed with [token]. */
internal fun castMetadata(input: CastMediaInput, token: String): CastMetadata {
    val item = input.item
    val images = castImages(input, token)
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
private fun castImages(input: CastMediaInput, token: String): List<CastImage> {
    val item = input.item ?: return emptyList()
    val base = input.apiBase
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
            sdh = isSdhSubtitle(s.label),
        )
    }
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
