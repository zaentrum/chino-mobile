package cloud.nalet.chino.mobile.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import cloud.nalet.chino.mobile.data.api.TrackInfo

/** A row of the audio menu: one of Media3's audio tracks, as /play/info
 *  describes it ([collectAudioTracks]). Wraps the Media3 group + index so
 *  the apply step can rebuild the override. */
internal data class AudioTrack(
    val id: String,
    /** By its language, else its name ([audioLabels]). */
    val label: String,
    /** Its codec and channels ("E-AC-3 · 5.1", "AAC · Stereo"). */
    val detail: String?,
    val language: String?,
    /** Its rendition's NAME in the master: what a pick is kept by when the
     *  player is built again. */
    val name: String?,
    val selected: Boolean,
    /** Media3 plays it on this device: a decoder takes it, or the output its
     *  bitstream. A 5.1 companion the output stopped taking is not. */
    val playable: Boolean,
    val group: Tracks.Group,
    val trackIndex: Int,
)

/**
 * The audio menu's rows: the master's audio renditions as Media3 lists them,
 * each described by the /play/info track it is ([audioTrackFor]) — found by
 * its group and NAME, which Media3 gives the rendition as its format id
 * ([isRenditionOf]). So a client served the 5.1 companions lists each next
 * to its stereo twin, as /play/info does. Each row is labelled by the
 * language it is tagged with ("German", "No dialogue" for zxx), by its NAME
 * where it has none, two of one language told apart by their details
 * ([audioLabels]); the detail is its codec and channels ("E-AC-3 · 5.1",
 * "AAC · Stereo"), /play/info's, else Media3's.
 */
internal fun collectAudioTracks(tracks: Tracks, listed: List<TrackInfo>): List<AudioTrack> {
    val found = tracks.groups
        .filter { it.type == C.TRACK_TYPE_AUDIO }
        .flatMap { g -> (0 until g.length).map { i -> g to i } }
    val formats = found.map { (g, i) -> g.getTrackFormat(i) }
    val infos = formats.mapIndexed { k, fmt ->
        audioTrackFor(listed, place = k, count = formats.size, language = fmt.language) { isRenditionOf(fmt, it) }
    }
    val details = formats.mapIndexed { k, fmt ->
        audioDetail(
            codec = infos[k]?.codec ?: fmt.codecs ?: fmt.sampleMimeType?.let(::audioCodecOfMime),
            channels = infos[k]?.channels ?: fmt.channelCount.takeIf { it > 0 },
        )
    }
    val labels = audioLabels(
        formats.mapIndexed { k, fmt ->
            AudioLabelInput(fmt.language ?: infos[k]?.language, infos[k]?.displayName ?: fmt.label, details[k])
        },
    )
    return found.mapIndexed { k, (g, i) ->
        AudioTrack(
            id = "${g.mediaTrackGroup.id}#$i",
            label = labels[k],
            detail = details[k],
            language = formats[k].language ?: infos[k]?.language,
            name = formats[k].label ?: infos[k]?.displayName,
            selected = g.isTrackSelected(i),
            playable = g.isTrackSupported(i, /* allowExceedsCapabilities = */ true),
            group = g,
            trackIndex = i,
        )
    }
}

/** Whether Media3's [format] is the rendition /play/info lists as [track]:
 *  Media3 gives an HLS rendition the id "group:name" — after the place of
 *  its source where the sidecars are merged in, "0:group:name" — and its NAME
 *  as the label. */
private fun isRenditionOf(format: Format, track: TrackInfo): Boolean {
    val name = track.displayName ?: return false
    val id = track.group?.let { "$it:$name" }
    return (id != null && (format.id == id || format.id?.endsWith(":$id") == true)) ||
        (track.group == null && format.label == name)
}

/** The codec a Media3 sample MIME type is, as [audioCodecName] reads it. */
private fun audioCodecOfMime(mime: String): String? = when (mime) {
    MimeTypes.AUDIO_AAC -> "aac"
    MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "eac3"
    MimeTypes.AUDIO_AC3 -> "ac3"
    MimeTypes.AUDIO_OPUS -> "opus"
    MimeTypes.AUDIO_MPEG -> "mp3"
    else -> null
}
