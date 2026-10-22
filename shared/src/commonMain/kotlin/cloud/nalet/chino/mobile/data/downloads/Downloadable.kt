package cloud.nalet.chino.mobile.data.downloads

import cloud.nalet.chino.mobile.data.api.PlayInfo

/** How a title comes onto the phone. */
enum class DownloadSource {
    /** Its package: the renditions the server keeps on disk, fetched as a
     *  stream of them is. */
    Package,
}

/** Whether a title can be downloaded - the one answer every screen asks
 *  for, [downloadability]. */
sealed interface Downloadability {
    /** It can, from [source]. */
    data class Yes(val source: DownloadSource) : Downloadability

    /** It plays only as a stream for now: the server plays it from the
     *  original file, as a copy or an encode made while it plays. */
    data object StreamOnly : Downloadability

    /** Not known: its play info could not be read. */
    data object Unknown : Downloadability
}

/**
 * Whether the title [info] describes can be downloaded. A title the server
 * plays from a package can (`mode` packaged): its renditions are files the
 * download keeps as they are. Any other mode is a stream made from the
 * original - a copy at its bit rate or an encode made while it plays - and
 * cannot, yet. The one rule: a later source (an encode made for the
 * download) is a [DownloadSource] here, and every screen follows.
 */
fun downloadability(info: PlayInfo?): Downloadability = when {
    info == null -> Downloadability.Unknown
    info.mode.equals("packaged", ignoreCase = true) -> Downloadability.Yes(DownloadSource.Package)
    else -> Downloadability.StreamOnly
}

/** The kinds of title that have a download control: the ones that play. A
 *  series downloads by its seasons and episodes. */
fun hasDownloadControl(kind: String?): Boolean = kind == "movie" || kind == "episode"
