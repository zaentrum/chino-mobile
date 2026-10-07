package cloud.nalet.chino.mobile.ui.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import cloud.nalet.chino.mobile.data.api.TrackInfo

/**
 * The master as Media3 reads it, each audio rendition with the codec
 * /play/info gives it ([withAudioCodecs]).
 *
 * A client that decodes the 5.1 companions (`eac3`) is served one audio group
 * with E-AC-3 and AAC renditions side by side, and each variant's CODECS
 * names both ("hvc1…,ec-3,mp4a.40.2"). Media3 cannot tell from that which
 * rendition is which, so it prepares from the media: it loads every audio
 * rendition's playlist and the start of its first segment before it selects
 * a track, and only then plays. Sintel, one companion, showed its first frame
 * 70 ms later that way on the emulator over a link of 10 Mbit/s and 100 ms
 * round trip, and every language of a title adds two renditions to load.
 *
 * /play/info lists the same renditions, by group and name, each with its
 * codec. Read with those, the master names one codec per rendition, and Media3
 * prepares from the master alone, as it does for the stereo group, and loads
 * nothing of a rendition the device cannot play.
 */
internal class KnownAudioCodecs(private val tracks: List<TrackInfo>) : HlsPlaylistParserFactory {
    private val parsers = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> {
        val parser = parsers.createPlaylistParser()
        return ParsingLoadable.Parser { uri, input ->
            when (val playlist = parser.parse(uri, input)) {
                is HlsMultivariantPlaylist -> playlist.withAudioCodecs(tracks)
                else -> playlist
            }
        }
    }

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> = parsers.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist)
}

/**
 * This master with the audio of each group whose variants name more than one
 * audio codec read as [tracks] (/play/info's) describe it: each rendition with
 * the codec of the track of its group and NAME, and those variants naming one
 * audio codec, the one of the rendition they start on (the group's DEFAULT,
 * else its first) — Media3 then needs to load none of them to know which is
 * which. A group with a rendition /play/info gives no codec Media3 knows is
 * left as it is served, and so is a master without such a group.
 */
internal fun HlsMultivariantPlaylist.withAudioCodecs(tracks: List<TrackInfo>): HlsMultivariantPlaylist {
    val mixed = variants.filter { audioCodecs(it.format.codecs).size > 1 }.mapNotNullTo(HashSet()) { it.audioGroupId }
    if (mixed.isEmpty() || tracks.isEmpty()) return this
    val codecs = HashMap<HlsMultivariantPlaylist.Rendition, String>()
    val known = mixed.filterTo(HashSet()) { group ->
        val members = audios.filter { it.groupId == group }
        members.isNotEmpty() && members.all { r ->
            val codec = mediaCodec(tracks.firstOrNull { describes(it, r) }?.codec)
            if (codec != null) codecs[r] = codec
            codec != null
        }
    }
    if (known.isEmpty()) return this
    val audios = audios.map { r ->
        val codec = codecs[r]?.takeIf { r.groupId in known } ?: return@map r
        val format = r.format.buildUpon().setCodecs(codec).setSampleMimeType(MimeTypes.getMediaMimeType(codec)).build()
        HlsMultivariantPlaylist.Rendition(r.url, format, r.groupId, r.name)
    }
    val variants = variants.map { v ->
        val group = v.audioGroupId?.takeIf { it in known } ?: return@map v
        val members = audios.filter { it.groupId == group }
        val start = members.firstOrNull { (it.format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0 } ?: members.first()
        val others = Util.splitCodecs(v.format.codecs).filter { MimeTypes.getTrackTypeOfCodec(it) != C.TRACK_TYPE_AUDIO }
        v.copyWithFormat(v.format.buildUpon().setCodecs((others + listOfNotNull(start.format.codecs)).joinToString(",")).build())
    }
    return HlsMultivariantPlaylist(
        baseUri, tags, variants, videos, audios, subtitles, closedCaptions,
        muxedAudioFormat, muxedCaptionFormats, hasIndependentSegments, variableDefinitions, sessionKeyDrmInitData,
    )
}

/** The audio codecs a CODECS attribute names. */
private fun audioCodecs(codecs: String?): List<String> =
    Util.splitCodecs(codecs).filter { MimeTypes.getTrackTypeOfCodec(it) == C.TRACK_TYPE_AUDIO }

/** Whether /play/info's [track] is the rendition [r]: of its group, where
 *  /play/info names the group, and of its NAME. */
private fun describes(track: TrackInfo, r: HlsMultivariantPlaylist.Rendition): Boolean =
    (track.group == null || track.group == r.groupId) && track.displayName == r.name

/** /play/info's codec ("ec-3", "eac3", "mp4a.40.2", "aac") as the codec
 *  string Media3 reads in a master; null for one this does not know. */
internal fun mediaCodec(codec: String?): String? {
    val c = codec?.trim()?.lowercase().orEmpty()
    return when {
        c == "ec-3" || c == "eac3" || c == "ec3" -> "ec-3"
        c == "ac-3" || c == "ac3" -> "ac-3"
        c == "aac" || c == "mp4a" -> "mp4a.40.2"
        c.startsWith("mp4a.") -> c
        c == "opus" -> "opus"
        c == "mp3" -> "mp4a.40.34"
        else -> null
    }
}
