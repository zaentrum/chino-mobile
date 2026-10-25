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

/**
 * A title's one text, on its control and on its row of the downloads
 * screen alike: the status and the size its offer said, as the server
 * reports it ([size]) - "queued · 347 MB", "waiting for Wi-Fi · 347 MB",
 * "downloading 15% · 347 MB", "downloaded · 347 MB", the same number before
 * and after. One that failed keeps nothing, and says no size.
 */
fun rowLabel(status: DownloadStatus, size: Long?): String {
    val label = statusLabel(status)
    val sized = status is DownloadStatus.Queued || status is DownloadStatus.WaitingForWifi ||
        status is DownloadStatus.WaitingForConnection || status is DownloadStatus.Downloading
    return if (sized && size != null && size > 0) "$label · ${formatSize(size)}" else label
}

/** Whether "try again" can help a download that stopped for [this]: not
 *  where the server turned downloads off, the title is not the account's,
 *  or it only streams. */
val DownloadFailure.canTryAgain: Boolean
    get() = this != DownloadFailure.ServerOff && this != DownloadFailure.Gone && this != DownloadFailure.StreamOnly

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
const val CHECK_NEEDED_LABEL = "downloaded · check${NBSP}needed"
const val CHECK_NEEDED_SENTENCE =
    "Downloads are checked with the server every 30 days. This one plays again once the server can be reached."

/**
 * A season's offer, for its episodes not handled yet: "download season 1 · 6
 * episodes · 1.9 GB" only while every episode of the season can be
 * downloaded and none is on the phone or on its way ([allDownloadable],
 * [noneHandled]); else "download 4 episodes · 1.3 GB" where some only
 * stream, and "download 2 more episodes · 724 MB" once some are handled - a
 * six-episode season does not read as one of four. [episodes] counts the
 * episodes the action downloads, a file holding two counting both; the size
 * once every one's is known, else without it.
 */
fun seasonOfferLabel(season: Int, episodes: Int, bytes: Long?, noneHandled: Boolean, allDownloadable: Boolean = true): String = buildString {
    val word = if (episodes == 1) "episode" else "episodes"
    when {
        noneHandled && allDownloadable -> append("download season ").append(season).append(" · ").append(episodes).append(' ').append(word)
        noneHandled -> append("download ").append(episodes).append(' ').append(word)
        else -> append("download ").append(episodes).append(" more ").append(word)
    }
    if (bytes != null && bytes > 0) append(" · ").append(formatSize(bytes))
}

/** The page built from the phone says only what is on the phone: a
 *  series' or a season's episodes there - "2 episodes on this phone". */
fun episodesOnThePhoneLabel(count: Int, device: DeviceKind): String =
    "$count ${if (count == 1) "episode" else "episodes"} on this ${device.word}"

/** How far a series or a season is on the phone: "3 of 8 downloaded". */
fun partLabel(downloaded: Int, of: Int): String = "$downloaded of $of downloaded"

/**
 * How far a season or a series is on the phone, as its status says it (docs/
 * chino downloads, "Series"): "1 of 4 downloaded", in episodes - of those
 * that can be downloaded: a file the server says only streams ([offers]) is
 * not counted, unless it is on the phone. [files] are its files by id, each
 * with the episodes it holds; [onThePhone] the ids on the phone. Where the
 * server offers no downloads now ([offering] false), only what is on the
 * phone: "2 episodes on this phone". Null where nothing of it is on the
 * phone, or while the server hasn't said which can come ([offers] null).
 */
fun howFarLabel(
    files: List<Pair<String, Int>>,
    onThePhone: Set<String>,
    offers: Map<String, DownloadOffer>?,
    offering: Boolean,
    device: DeviceKind,
): String? {
    val downloaded = files.filter { it.first in onThePhone }.sumOf { it.second }
    if (downloaded == 0) return null
    if (!offering) return episodesOnThePhoneLabel(downloaded, device)
    val known = offers ?: return null
    return partLabel(downloaded, canComeCount(files, onThePhone, known))
}

/**
 * Whether all of a season or a series that can come along is on the phone:
 * the check beside [howFarLabel]'s words, by the same count - "4 of 4
 * downloaded", never "1 of 4". An episode the server couldn't be asked about
 * counts as one that can come, as there. False where nothing of it is on the
 * phone, while the server hasn't said which can come ([offers] null), and
 * where it offers no downloads now ([offering] false).
 */
fun allThatCanComeIsThere(
    files: List<Pair<String, Int>>,
    onThePhone: Set<String>,
    offers: Map<String, DownloadOffer>?,
    offering: Boolean,
): Boolean {
    val downloaded = files.filter { it.first in onThePhone }.sumOf { it.second }
    if (downloaded == 0 || !offering) return false
    val known = offers ?: return false
    return downloaded == canComeCount(files, onThePhone, known)
}

/** The episodes of [files] that can come along: the ones on the phone, and
 *  every one the server hasn't said only streams. */
private fun canComeCount(files: List<Pair<String, Int>>, onThePhone: Set<String>, offers: Map<String, DownloadOffer>): Int =
    files.filter { (id, _) -> id in onThePhone || offers[id] !is DownloadOffer.StreamOnly }.sumOf { it.second }

/** The downloads screen's top line: "12.4 GB used by downloads · 18 GB free
 *  on this phone"; with nothing on the phone ([anythingOnThePhone]), what
 *  is free alone: "3.9 GB free on this phone". */
fun storageLine(use: StorageUse, device: DeviceKind, anythingOnThePhone: Boolean = use.usedBytes > 0): String =
    if (anythingOnThePhone) {
        "${formatSize(use.usedBytes)} used by downloads · ${formatSize(use.freeBytes)} free on this ${device.word}"
    } else {
        "${formatSize(use.freeBytes)} free on this ${device.word}"
    }

/** Settings' name of a quality, with what an hour takes at it where enough
 *  of the server's titles say: "720p · about 1.1 GB an hour". */
fun qualityLine(q: DownloadQuality): String =
    q.bytesPerHour?.takeIf { it > 0 }?.let { "${q.name} · about ${formatSize(it)} an hour" } ?: q.name

/** "Delete all"'s question: its title, the action and what it acts on. */
const val DELETE_ALL_TITLE = "delete all downloads"

/** Its sentence, counting what is on the phone: "This removes 23
 *  downloads, 12.4 GB, from this phone." With nothing on it - only downloads
 *  that failed - the list is cleared. */
fun deleteAllSentence(downloads: Int, bytes: Long, device: DeviceKind): String =
    if (downloads == 0) {
        "This clears the list of downloads."
    } else {
        "This removes $downloads ${if (downloads == 1) "download" else "downloads"}, ${formatSize(bytes)}, from this ${device.word}."
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
        "Your download, ${formatSize(summary.bytes)}, is deleted from this ${device.word}."
    } else {
        "Your ${summary.titles} downloads, ${formatSize(summary.bytes)}, are deleted from this ${device.word}."
    }

/** Remove account: "The 23 downloads of Anna, 12.4 GB, are deleted from
 *  this phone." */
fun accountDownloadsGoSentence(summary: DownloadsSummary, accountName: String, device: DeviceKind): String =
    if (summary.titles == 1) {
        "The download of $accountName, ${formatSize(summary.bytes)}, is deleted from this ${device.word}."
    } else {
        "The ${summary.titles} downloads of $accountName, ${formatSize(summary.bytes)}, are deleted from this ${device.word}."
    }

/** Remove account, with none of its downloads on the phone: what goes, by
 *  the account's name - "This removes Anna from this phone. You'll need to
 *  sign in again to use the account." */
fun accountRemovedSentence(accountName: String, device: DeviceKind): String =
    "This removes $accountName from this ${device.word}. You'll need to sign in again to use the account."

/** The account picker's line under its title: what a tap on a tile and on
 *  its ✕ do. */
fun pickerHintLine(device: DeviceKind): String = "Tap to switch. Tap ✕ to remove an account from this ${device.word}."

/** The ✕ on an account's tile, as a screen reader names it. */
const val REMOVE_ACCOUNT_LABEL = REMOVE_ACCOUNT_TITLE

/** The download notification's title: a film's, or an episode's with its
 *  series and code - "Pioneer One · S01E01 · Earthfall". */
fun notificationTitle(title: String, seriesTitle: String?, episodeCode: String?): String =
    listOfNotNull(seriesTitle?.takeIf { it.isNotBlank() }, episodeCode?.takeIf { it.isNotBlank() }, title).joinToString(" · ")

/** The question before a sign-out or an account's removal while casting:
 *  the sentence after the downloads one. [device]: the session's device. */
fun castingStopsSentence(device: String): String = "Casting on $device stops."

/** After an account switch while casting, which asks nothing. */
fun stoppedCastingToast(device: String): String = "Stopped casting on $device."

/** A downloaded title played on the phone, not the TV, while a device is
 *  connected: the server can't be reached. */
fun playingHereToast(device: DeviceKind): String = "Playing on this ${device.word}: the server can't be reached."
