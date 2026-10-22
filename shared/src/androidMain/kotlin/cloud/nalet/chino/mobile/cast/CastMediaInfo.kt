package cloud.nalet.chino.mobile.cast

import android.net.Uri
import cloud.nalet.chino.mobile.data.auth.withStreamToken
import cloud.nalet.chino.mobile.ui.player.isSdhSubtitle
import com.google.android.gms.cast.HlsSegmentFormat
import com.google.android.gms.cast.HlsVideoSegmentFormat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.common.images.WebImage
import org.json.JSONObject

/*
 * [CastMedia] in the Cast SDK's terms: the load request the phone sends the
 * Default Media Receiver. The receiver plays the master at contentUrl as HLS
 * with fMP4 (CMAF) segments, which is what chino-stream serves on every path -
 * a package, a stream copy, a live encode - as a buffered stream of the
 * title's length; the sidecars side-loaded as WebVTT text tracks; the media's
 * custom data carries the phone's marker ([CastTag]).
 */

/** Where [CastMetadata] is written: the SDK's MediaMetadata on a device. The
 *  JVM tests pass their own - MediaMetadata keeps its values in a Bundle,
 *  which the unit tests' android.jar only stubs. */
internal interface CastMetadataSink {
    fun putString(key: String, value: String)
    fun putInt(key: String, value: Int)
    fun addImage(url: String, width: Int, height: Int)
}

/** The SDK's media type of [CastMetadata.kind]: an episode is a TV show's,
 *  which the controls and the lock screen name with its series. */
internal val CastMetadata.mediaType: Int
    get() = when (kind) {
        CastMediaKind.Movie -> MediaMetadata.MEDIA_TYPE_MOVIE
        CastMediaKind.Episode -> MediaMetadata.MEDIA_TYPE_TV_SHOW
    }

/** The string keys the phone writes ([writeTo]), and the number keys. */
private val STRING_KEYS = listOf(MediaMetadata.KEY_TITLE, MediaMetadata.KEY_SUBTITLE, MediaMetadata.KEY_SERIES_TITLE)
private val INT_KEYS = listOf(MediaMetadata.KEY_SEASON_NUMBER, MediaMetadata.KEY_EPISODE_NUMBER)

/** Writes the title's line, the episode's own title, its series and numbers,
 *  and the artwork, backdrop first. */
internal fun CastMetadata.writeTo(sink: CastMetadataSink) {
    sink.putString(MediaMetadata.KEY_TITLE, title)
    subtitle?.let { sink.putString(MediaMetadata.KEY_SUBTITLE, it) }
    seriesTitle?.let { sink.putString(MediaMetadata.KEY_SERIES_TITLE, it) }
    seasonNumber?.let { sink.putInt(MediaMetadata.KEY_SEASON_NUMBER, it) }
    episodeNumber?.let { sink.putInt(MediaMetadata.KEY_EPISODE_NUMBER, it) }
    images.forEach { sink.addImage(it.url, it.width, it.height) }
}

private class MediaMetadataSink(val metadata: MediaMetadata) : CastMetadataSink {
    override fun putString(key: String, value: String) = metadata.putString(key, value)
    override fun putInt(key: String, value: Int) = metadata.putInt(key, value)
    override fun addImage(url: String, width: Int, height: Int) = metadata.addImage(WebImage(Uri.parse(url), width, height))
}

/** The SDK's MediaMetadata of [CastMetadata]. */
internal fun CastMetadata.toSdkMetadata(): MediaMetadata = MediaMetadata(mediaType).also { writeTo(MediaMetadataSink(it)) }

/** A sidecar as a side-loaded text track: WebVTT at its signed URL, named as
 *  the app names it, a forced one with the forced-subtitle role. */
internal fun CastTextTrack.toMediaTrack(): MediaTrack =
    MediaTrack.Builder(id, MediaTrack.TYPE_TEXT)
        .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
        .setName(name)
        .setLanguage(language)
        .setContentId(url)
        .setContentType(CAST_TEXT_TRACK_TYPE)
        .setRoles(listOf(if (forced) MediaTrack.ROLE_FORCED_SUBTITLE else MediaTrack.ROLE_SUBTITLE))
        .build()

/** The media of the load request, marked as [owner]'s, with [metadata] -
 *  the title's ([toSdkMetadata]), unless a JVM test passes its own. */
internal fun CastMedia.toMediaInfo(owner: String, metadata: MediaMetadata = this.metadata.toSdkMetadata()): MediaInfo =
    MediaInfo.Builder(contentId)
        .setContentUrl(contentUrl)
        .setContentType(CAST_CONTENT_TYPE)
        .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
        .setHlsSegmentFormat(HlsSegmentFormat.FMP4)
        .setHlsVideoSegmentFormat(HlsVideoSegmentFormat.FMP4)
        .setStreamDuration(durationMs ?: MediaInfo.UNKNOWN_DURATION)
        .setMetadata(metadata)
        .setMediaTracks(textTracks.map { it.toMediaTrack() })
        .setCustomData(castTagJson(tag(owner)))
        .build()

/** The receiver's content id of the media: the title, or the title's extra.
 *  The URL is its contentUrl. */
internal val CastMedia.contentId: String get() = if (extraId == null) itemId else "$itemId/$extraId"

/** The load request: the media, where the TV starts, whether it plays from
 *  there, and the side-loaded subtitle that is on. */
internal fun CastMedia.toLoadRequest(owner: String, metadata: MediaMetadata = this.metadata.toSdkMetadata()): MediaLoadRequestData =
    MediaLoadRequestData.Builder()
        .setMediaInfo(toMediaInfo(owner, metadata))
        .setAutoplay(autoplay)
        .setCurrentTime(startMs)
        .apply { activeTextTrackId?.let { setActiveTrackIds(longArrayOf(it)) } }
        .build()

/**
 * [info] - the media the TV plays, the phone's - loaded again as [again]
 * says: its master, its side-loaded subtitles and its artwork signed with
 * the new token, its marker that of the new load; where it was, playing or
 * paused, with its tracks. Null for media that is not this app's.
 */
internal fun reloadRequest(info: MediaInfo, again: CastReload): MediaLoadRequestData? {
    val tag = castTagOf(info.customData) ?: return null
    val url = info.contentUrl?.takeIf { it.isNotEmpty() } ?: return null
    val token = again.token.token
    val media = MediaInfo.Builder(info.contentId)
        .setContentUrl(withStreamToken(url, token))
        .setContentType(info.contentType ?: CAST_CONTENT_TYPE)
        .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
        .setHlsSegmentFormat(HlsSegmentFormat.FMP4)
        .setHlsVideoSegmentFormat(HlsVideoSegmentFormat.FMP4)
        .setStreamDuration(info.streamDuration)
        .apply { info.metadata?.let { setMetadata(signedAgain(it, token)) } }
        .setMediaTracks(info.mediaTracks.orEmpty().filter { it.id >= CAST_TEXT_TRACK_FIRST_ID }.map { signedAgain(it, token) })
        .setCustomData(castTagJson(tag.copy(load = again.load, linksExpireAtMs = again.token.expiresAtMs)))
        .build()
    return MediaLoadRequestData.Builder()
        .setMediaInfo(media)
        .setAutoplay(again.autoplay)
        .setCurrentTime(again.atMs.coerceAtLeast(0L))
        .apply { if (again.activeTrackIds.isNotEmpty()) setActiveTrackIds(again.activeTrackIds.toLongArray()) }
        .build()
}

/** The phone's metadata ([writeTo]'s keys), its artwork signed with [token]. */
private fun signedAgain(metadata: MediaMetadata, token: String): MediaMetadata =
    MediaMetadata(metadata.mediaType).also { out ->
        STRING_KEYS.filter { metadata.containsKey(it) }.forEach { key -> metadata.getString(key)?.let { out.putString(key, it) } }
        INT_KEYS.filter { metadata.containsKey(it) }.forEach { key -> out.putInt(key, metadata.getInt(key)) }
        metadata.images.forEach { image ->
            out.addImage(WebImage(Uri.parse(withStreamToken(image.url.toString(), token)), image.width, image.height))
        }
    }

/** One of the phone's side-loaded subtitles, signed with [token]. */
private fun signedAgain(track: MediaTrack, token: String): MediaTrack =
    MediaTrack.Builder(track.id, track.type)
        .setSubtype(track.subtype)
        .setName(track.name)
        .setLanguage(track.language)
        .setContentId(track.contentId?.let { withStreamToken(it, token) })
        .setContentType(track.contentType)
        .setRoles(track.roles)
        .build()

/** A track of the TV's media in casting's terms ([TvTrack]): forced where it
 *  says so in its role or its name. */
internal fun MediaTrack.toTvTrack(): TvTrack = TvTrack(
    id = id,
    type = when (type) {
        MediaTrack.TYPE_AUDIO -> TvTrackType.Audio
        MediaTrack.TYPE_TEXT -> TvTrackType.Text
        MediaTrack.TYPE_VIDEO -> TvTrackType.Video
        else -> TvTrackType.Other
    },
    language = language,
    name = name,
    forced = roles.orEmpty().contains(MediaTrack.ROLE_FORCED_SUBTITLE) || name.orEmpty().contains("forced", ignoreCase = true),
    sdh = isSdhSubtitle(name),
)

/** The TV's tracks of [this] media, its subtitles named as the phone names a
 *  subtitle ([tvSubtitleNames]): what the chooser of the TV's tracks lists -
 *  of two that read the same one, the one [active] where it is one of them.
 *  Nothing else of the media is in it. */
internal fun MediaInfo.withSubtitleNames(active: LongArray = LongArray(0)): MediaInfo {
    val tracks = mediaTracks.orEmpty()
    val names = tvSubtitleNames(tracks.map { it.toTvTrack() }, active.toList())
    return MediaInfo.Builder(contentId.orEmpty())
        .setStreamType(streamType)
        .setMediaTracks(
            tracks.mapNotNull { t ->
                when {
                    names[t.id] != null -> t.named(names.getValue(t.id))
                    t.type == MediaTrack.TYPE_TEXT -> null
                    else -> t
                }
            },
        )
        .build()
}

private fun MediaTrack.named(name: String): MediaTrack =
    MediaTrack.Builder(id, type)
        .setSubtype(subtype)
        .setName(name)
        .setLanguage(language)
        .setContentId(contentId)
        .setContentType(contentType)
        .setRoles(roles)
        .setCustomData(customData)
        .build()

/** [tag] as the media's custom data:
 *  `{"chino": {item, extra, owner, progress, load, exp}}`. */
internal fun castTagJson(tag: CastTag): JSONObject =
    JSONObject().put(
        CastTag.KEY,
        JSONObject()
            .put(CastTag.ITEM, tag.itemId)
            .apply { tag.extraId?.let { put(CastTag.EXTRA, it) } }
            .put(CastTag.OWNER, tag.owner)
            .put(CastTag.PROGRESS, tag.writesProgress)
            .put(CastTag.LOAD, tag.load)
            .put(CastTag.LINKS_EXPIRE, tag.linksExpireAtMs),
    )

/** The phone's marker in a media's custom data, or null where there is none
 *  - media another app or another sender cast. */
internal fun castTagOf(customData: JSONObject?): CastTag? {
    val o = customData?.optJSONObject(CastTag.KEY) ?: return null
    val item = o.optString(CastTag.ITEM).takeIf { it.isNotEmpty() } ?: return null
    val owner = o.optString(CastTag.OWNER).takeIf { it.isNotEmpty() } ?: return null
    return CastTag(
        itemId = item,
        extraId = o.optString(CastTag.EXTRA).takeIf { o.has(CastTag.EXTRA) && it.isNotEmpty() },
        owner = owner,
        writesProgress = o.optBoolean(CastTag.PROGRESS, false),
        load = o.optString(CastTag.LOAD),
        linksExpireAtMs = o.optLong(CastTag.LINKS_EXPIRE, 0L),
    )
}
