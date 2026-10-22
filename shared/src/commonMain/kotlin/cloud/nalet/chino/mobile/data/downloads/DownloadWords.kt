package cloud.nalet.chino.mobile.data.downloads

/*
 * What the download control, the downloads screen and the questions before
 * deleting say, in one place: the design system's words for downloads
 * ("downloads", "on this phone"), its control labels and dialog titles
 * lowercase as monospace labels are (names and units keep their case:
 * Wi-Fi, GB), its sentences in sentence case. A failure's sentence is a
 * reason, never an instruction: the control offers "try again" itself.
 * Counts and sizes come from the data. Pure, so the tests read every one.
 */

/** What the app calls the device it runs on: "phone", "tablet" from 600 dp
 *  of its shorter side up - everywhere "on this phone" appears. */
enum class DeviceKind(val word: String) { Phone("phone"), Tablet("tablet") }

/** The control's label for a title not on the phone: "download · 1.4 GB",
 *  or "download" where the size is not known. */
fun offerLabel(bytes: Long?): String = if (bytes != null && bytes > 0) "download · ${formatSize(bytes)}" else "download"

/** The control's label for a title with a download. */
fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.Queued -> "queued"
    DownloadStatus.WaitingForWifi -> "waiting for Wi-Fi"
    DownloadStatus.WaitingForConnection -> "waiting for a connection"
    is DownloadStatus.Downloading -> "downloading ${status.percent.coerceIn(0, 100)}%"
    is DownloadStatus.Downloaded -> "downloaded · ${formatSize(status.bytes)}"
    is DownloadStatus.CheckNeeded -> CHECK_NEEDED_LABEL
    is DownloadStatus.Failed -> "couldn't download"
}

/** The sentence under the label, where one says why: a failure's reason, or
 *  why a download waits for the server before it plays. */
fun statusSentence(status: DownloadStatus, device: DeviceKind): String? = when (status) {
    is DownloadStatus.Failed -> status.reason.sentence(device)
    is DownloadStatus.CheckNeeded -> CHECK_NEEDED_SENTENCE
    else -> null
}

/** Why a download stopped, in a sentence. */
fun DownloadFailure.sentence(device: DeviceKind): String = when (this) {
    DownloadFailure.NoSpace -> "Not enough space on this ${device.word}."
    DownloadFailure.NoAnswer -> "Can't reach the server."
    DownloadFailure.ServerProblem -> "The server ran into a problem."
    DownloadFailure.Refused -> "The server didn't accept the download."
    DownloadFailure.Gone -> "This title isn't available to this account anymore."
    DownloadFailure.StreamOnly -> CANT_DOWNLOAD_SENTENCE
    DownloadFailure.Stopped -> "The download stopped before it was done."
    DownloadFailure.ServerOff -> "The server has turned downloads off."
}

/** The control's label for a title that plays only as a stream. */
const val CANT_DOWNLOAD_LABEL = "can't be downloaded"

/** Its sentence. */
const val CANT_DOWNLOAD_SENTENCE = "This title can only be streamed."

/** A download not checked with the server for [CHECK_EVERY_MS], while the
 *  server cannot be reached: its label, and the reason play waits. */
const val CHECK_NEEDED_LABEL = "downloaded · check needed"
const val CHECK_NEEDED_SENTENCE =
    "Downloads are checked with the server every 30 days. This one plays again once the server can be reached."

/**
 * A season's offer, for the episodes not handled yet: "download season 1 ·
 * 8 episodes · 9.6 GB" while none of the season is on the phone or on its
 * way, else "download 2 more episodes · 724 MB" - a six-episode season does
 * not read as one of two. The size once every episode's is known, else
 * without it. [episodes] counts what the action downloads.
 */
fun seasonOfferLabel(season: Int, episodes: Int, bytes: Long?, noneHandled: Boolean): String = buildString {
    if (noneHandled) {
        append("download season ").append(season).append(" · ").append(episodes)
        append(if (episodes == 1) " episode" else " episodes")
    } else {
        append("download ").append(episodes).append(if (episodes == 1) " more episode" else " more episodes")
    }
    if (bytes != null && bytes > 0) append(" · ").append(formatSize(bytes))
}

/** How far a series or a season is on the phone: "3 of 8 downloaded". */
fun partLabel(downloaded: Int, of: Int): String = "$downloaded of $of downloaded"

/** The downloads screen's top line: "12.4 GB used by downloads · 18 GB free
 *  on this phone". */
fun storageLine(use: StorageUse, device: DeviceKind): String =
    "${formatSize(use.usedBytes)} used by downloads · ${formatSize(use.freeBytes)} free on this ${device.word}"

/** Settings' name of a quality, with what an hour takes at it where enough
 *  of the server's titles say: "720p · about 1.1 GB an hour". */
fun qualityLine(q: DownloadQuality): String =
    q.bytesPerHour?.takeIf { it > 0 }?.let { "${q.name} · about ${formatSize(it)} an hour" } ?: q.name

/** "Delete all"'s question: its title, the action and what it acts on. */
const val DELETE_ALL_TITLE = "delete all downloads"

/** Its sentence, counting what is on the phone: "This removes 6 titles,
 *  474 MB, from this phone." With nothing on it - only downloads that
 *  stopped - the list is cleared. */
fun deleteAllSentence(titles: Int, bytes: Long, device: DeviceKind): String =
    if (titles == 0) {
        "This clears the list of downloads."
    } else {
        "This removes $titles ${if (titles == 1) "title" else "titles"}, ${formatSize(bytes)}, from this ${device.word}."
    }

/** The banner while the server cannot be reached: the downloads play
 *  without it; with none on the phone, only that it can't be reached. */
const val UNREACHABLE_BANNER = "Can't reach the server. Your downloads still play."
const val UNREACHABLE_BANNER_NOTHING = "Can't reach the server."

/** The downloads screen with nothing on it: its title and its sentence. */
const val EMPTY_TITLE = "nothing downloaded yet"
const val EMPTY_SENTENCE = "Titles you download show up here, ready to watch without a connection."

/** A title the server's decision took off the phone - it left the library,
 *  or the account's cap no longer allows it: one title. */
fun deletedOneSentence(device: DeviceKind): String =
    "This title isn't available to this account anymore, so it was deleted from this ${device.word}."

/** Several, in a banner shown once on the downloads screen, the titles
 *  under its details. */
fun deletedManySentence(count: Int, device: DeviceKind): String =
    "$count downloads were deleted from this ${device.word}: they aren't available to this account anymore."

/** The questions before what deletes downloads: their titles, which the
 *  confirming danger button repeats. */
const val SIGN_OUT_TITLE = "sign out"
const val REMOVE_ACCOUNT_TITLE = "remove account"
const val CHANGE_SERVER_TITLE = "change server"

/** Sign out and change server: "Your 23 downloads, 12.4 GB, are deleted
 *  from this phone." */
fun yourDownloadsGoSentence(summary: DownloadsSummary, device: DeviceKind): String =
    if (summary.titles == 1) {
        "Your 1 download, ${formatSize(summary.bytes)}, is deleted from this ${device.word}."
    } else {
        "Your ${summary.titles} downloads, ${formatSize(summary.bytes)}, are deleted from this ${device.word}."
    }

/** Remove account: "The 23 downloads of Anna, 12.4 GB, are deleted from
 *  this phone." */
fun accountDownloadsGoSentence(summary: DownloadsSummary, accountName: String, device: DeviceKind): String =
    if (summary.titles == 1) {
        "The 1 download of $accountName, ${formatSize(summary.bytes)}, is deleted from this ${device.word}."
    } else {
        "The ${summary.titles} downloads of $accountName, ${formatSize(summary.bytes)}, are deleted from this ${device.word}."
    }

/** The download notification's title: a film's, or an episode's with its
 *  series and code - "Pioneer One · S01E01 · Earthfall". */
fun notificationTitle(title: String, seriesTitle: String?, episodeCode: String?): String =
    listOfNotNull(seriesTitle?.takeIf { it.isNotBlank() }, episodeCode?.takeIf { it.isNotBlank() }, title).joinToString(" · ")
