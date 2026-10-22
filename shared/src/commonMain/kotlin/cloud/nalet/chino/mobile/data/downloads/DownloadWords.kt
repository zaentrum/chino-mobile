package cloud.nalet.chino.mobile.data.downloads

/*
 * What the download control and the downloads screen say, in one place: the
 * design system's words for downloads ("downloads", "on this phone"), its
 * control labels lowercase as monospace labels are (names and units keep
 * their case: Wi-Fi, GB), its sentences in sentence case. Pure, so the
 * tests read every one.
 */

/** The control's label for a title not on the phone: "download · 1.4 GB",
 *  or "download" where the size is not known. */
fun offerLabel(bytes: Long?): String = if (bytes != null && bytes > 0) "download · ${formatSize(bytes)}" else "download"

/** The control's label for a title with a download. */
fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.Queued -> "queued"
    DownloadStatus.WaitingForWifi -> "waiting for Wi-Fi"
    DownloadStatus.WaitingForConnection -> "waiting for a connection"
    DownloadStatus.WaitingForBattery -> "waiting · battery low"
    is DownloadStatus.Downloading -> "downloading ${status.percent.coerceIn(0, 100)}%"
    is DownloadStatus.Downloaded -> "downloaded · ${formatSize(status.bytes)}"
    is DownloadStatus.Failed -> "couldn't download"
    DownloadStatus.Removing -> "deleting"
}

/** The sentence under the label, where one says why: a failure's reason. */
fun statusSentence(status: DownloadStatus): String? = (status as? DownloadStatus.Failed)?.reason?.sentence

/** The control's label for a title that plays only as a stream. */
const val CANT_DOWNLOAD_LABEL = "can't be downloaded yet"

/** Its sentence. */
const val CANT_DOWNLOAD_SENTENCE = "This title can only be streamed for now."

/** A season's offer: "download season 1 · 8 episodes · 9.6 GB" - the size
 *  once every episode's is known, else without it. [episodes] counts what
 *  the action downloads. */
fun seasonOfferLabel(season: Int, episodes: Int, bytes: Long?): String = buildString {
    append("download season ").append(season).append(" · ").append(episodes)
    append(if (episodes == 1) " episode" else " episodes")
    if (bytes != null && bytes > 0) append(" · ").append(formatSize(bytes))
}

/** How far a series or a season is on the phone: "3 of 8 downloaded". */
fun partLabel(downloaded: Int, of: Int): String = "$downloaded of $of downloaded"

/** The downloads screen's top line: "12.4 GB used by downloads · 18.0 GB
 *  free on this phone". */
fun storageLine(use: StorageUse): String =
    "${formatSize(use.usedBytes)} used by downloads · ${formatSize(use.freeBytes)} free on this phone"

/** Settings' name of a quality, with what an hour takes at it where the
 *  server's playlists say: "720p · about 1.1 GB an hour". */
fun qualityLine(q: DownloadQuality): String =
    q.bytesPerHour?.takeIf { it > 0 }?.let { "${q.name} · about ${formatSize(it)} an hour" } ?: q.name

/** "Delete all"'s question, naming what goes. */
const val DELETE_ALL_TITLE = "Delete all downloads?"

/** Its sentence: "This removes 23 titles, 12.4 GB, from this phone." */
fun deleteAllSentence(titles: Int, bytes: Long): String =
    "This removes $titles ${if (titles == 1) "title" else "titles"}, ${formatSize(bytes)}, from this phone."

/** The banner while the server cannot be reached. */
const val UNREACHABLE_BANNER = "Can't reach the server. Your downloads still play."

/** The downloads screen with nothing on it: its title and its sentence. */
const val EMPTY_TITLE = "nothing downloaded yet"
const val EMPTY_SENTENCE = "Titles you download show up here, ready to watch without a connection."
