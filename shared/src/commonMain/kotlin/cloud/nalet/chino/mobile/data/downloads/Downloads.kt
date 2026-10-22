package cloud.nalet.chino.mobile.data.downloads

import cloud.nalet.chino.mobile.data.api.PlayInfo
import cloud.nalet.chino.mobile.data.api.Segment
import cloud.nalet.chino.mobile.data.model.Item
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Who a download is for: an account, by its subject - the sign-in's `sub`,
 * the same however often it signs in again - on a server, by its API base.
 * Never its token: a sign-in that expired or was refused hides nothing and
 * deletes nothing, and signing in again as the same subject brings it all
 * back.
 */
data class Owner(val server: String, val subject: String)

/**
 * One account's claim on a download. Several accounts on one phone may
 * download the same title of the same server: it is fetched once, and goes
 * from the phone with its last claim.
 */
@Serializable
data class OwnerClaim(
    /** The account's subject. */
    val subject: String,
    /** When the account asked for it, ms since the epoch. */
    @SerialName("added_at") val addedAtMs: Long,
    /** Where the server had the account as it asked: where it resumes
     *  without the server before it is played on this phone. */
    @SerialName("saved_position_sec") val savedPositionSec: Int? = null,
    /** When the server last said the account may watch it
     *  (POST /me/available), or when it was asked for. */
    @SerialName("checked_at") val checkedAtMs: Long,
    /** When the server first said the account may no longer watch it, since
     *  it last said it may; null for none. A second such answer, an hour or
     *  more later, deletes it ([applyAvailability]). */
    @SerialName("gone_at") val goneAtMs: Long? = null,
)

/**
 * The key of one server's title on the phone: its item id and a digest of
 * the server - Media3's download id, and the name of its folder. Two servers
 * may hold the same item id; one server's title is one download, however
 * many accounts claim it.
 */
fun downloadKey(server: String, itemId: String): String = "${itemId.filter { it.isLetterOrDigit() || it == '-' }}~${serverDigest(server)}"

/** A server's API base as 16 hex digits: FNV-1a over its normalised form. */
internal fun serverDigest(server: String): String {
    var h = 0xcbf29ce484222325uL
    for (b in server.trim().trimEnd('/').lowercase().encodeToByteArray()) {
        h = h xor b.toUByte().toULong()
        h *= 0x100000001b3uL
    }
    return h.toString(16).padStart(16, '0')
}

/**
 * A title kept on the phone: what the detail page, the downloads screen and
 * the player need of it without the server, written beside its media when
 * the download starts (title.json). Platform-neutral; the media itself is
 * the platform's (Media3's cache on Android).
 */
@Serializable
data class DownloadedTitle(
    @SerialName("item_id") val itemId: String,
    /** The title's detail as the server gave it: title, overview, runtime,
     *  year, the series and the numbers of an episode. */
    val item: Item,
    /** An episode's series, by its title. */
    @SerialName("series_title") val seriesTitle: String? = null,
    /** What the player read of the title as the download started: its
     *  length, its mode, its audio tracks. */
    val info: PlayInfo? = null,
    /** Intro, recap and credits, for the skip buttons. */
    val segments: List<Segment> = emptyList(),
    /** The quality it was downloaded at, as the player's menu names it. */
    val quality: String,
    /** What the server's playlists said it would take, before it started;
     *  null where they did not say. */
    @SerialName("estimated_bytes") val estimatedBytes: Long? = null,
    /** The languages of the audio kept, the viewer's first. */
    @SerialName("audio_languages") val audioLanguages: List<String?> = emptyList(),
    /** The sidecar subtitles kept, each a file beside the media. */
    val subtitles: List<DownloadedSubtitle> = emptyList(),
    /** File names, in the title's folder: its poster, its backdrop (an
     *  episode's still). Null where the server had none. */
    val poster: String? = null,
    val backdrop: String? = null,
    /** The server it came from (its API base): only that server's accounts
     *  see it, and only that server is asked for it. */
    val server: String,
    /** The accounts it is for. Empty: nobody's, and it goes. */
    val owners: List<OwnerClaim> = emptyList(),
    /** Its subtitles and artwork are on the phone. */
    @SerialName("extras_done") val extrasDone: Boolean = false,
    /** Why it stopped, the last time it did; null while it goes on. */
    val failure: DownloadFailure? = null,
) {
    val key: String get() = downloadKey(server, itemId)

    val isEpisode: Boolean get() = item.kind == "episode" || item.parentId != null

    fun claimOf(subject: String): OwnerClaim? = owners.firstOrNull { it.subject == subject }

    fun ownedBy(owner: Owner): Boolean = server == owner.server && owners.any { it.subject == owner.subject }

    /** When the first account asked for it. */
    val firstAddedAtMs: Long get() = owners.minOfOrNull { it.addedAtMs } ?: 0L
}

/** One sidecar subtitle kept with a title. */
@Serializable
data class DownloadedSubtitle(
    val id: String,
    val lang: String,
    val label: String? = null,
    val format: String? = null,
    val forced: Boolean = false,
    /** Its file name in the title's subtitles folder. */
    val file: String,
    /** Where the server serves it ("/api/v1/play/subs/{id}.vtt"). */
    val source: String? = null,
)

/**
 * Where a download is - the download manager's own states, in the words the
 * control shows (ui/downloads). A title not on the phone has none.
 */
sealed interface DownloadStatus {
    /** Waiting its turn: "queued". */
    data object Queued : DownloadStatus

    /** Downloads are set to Wi-Fi only and the phone has none: "waiting for
     *  Wi-Fi". It goes on by itself on Wi-Fi. */
    data object WaitingForWifi : DownloadStatus

    /** Mobile data is allowed and the phone has no connection at all. */
    data object WaitingForConnection : DownloadStatus

    /** On its way, [percent] in whole percent. */
    data class Downloading(val percent: Int) : DownloadStatus

    /** On the phone, [bytes] of it. */
    data class Downloaded(val bytes: Long) : DownloadStatus

    /** On the phone, but not checked with the server for [CHECK_EVERY_MS]
     *  while it cannot be reached: it does not play until it is. With the
     *  server the check runs by itself and this never shows. */
    data class CheckNeeded(val bytes: Long) : DownloadStatus

    /** It stopped for [reason]: "couldn't download". */
    data class Failed(val reason: DownloadFailure) : DownloadStatus
}

/** Why a download stopped: a reason, never an instruction - the control
 *  offers "try again" itself ([sentence]). */
@Serializable
enum class DownloadFailure {
    NoSpace,
    NoAnswer,
    ServerProblem,
    Refused,
    Gone,
    StreamOnly,
    Stopped,

    /** The server turned downloads off (features.downloads false). */
    ServerOff,
}

/** A title on the phone or on its way, as the account it is shown to has
 *  it: where it is, what of it is on the phone ([onPhoneBytes]), and that
 *  account's claim on it. */
data class DownloadEntry(
    val title: DownloadedTitle,
    val status: DownloadStatus,
    /** What of it is on the phone now - its media and its folder, the one
     *  figure the downloads screen's line, "delete all" and every question
     *  sum from the bytes (the platform's, counted once); 0 for one that
     *  failed, which keeps nothing of its media. A title's own row and
     *  control say its size as the server reported it ([size]). */
    val onPhoneBytes: Long,
    val claim: OwnerClaim,
    /** What of its media is on the phone now - the manager's count, without
     *  its folder: a title queued with only its artwork there has none. */
    val mediaBytes: Long = 0L,
) {
    val itemId: String get() = title.itemId

    val addedAtMs: Long get() = claim.addedAtMs

    /** The size a row shows: the size its offer said, as the server
     *  reported it - the same number before and after (the status carries
     *  it once it is on the phone); for one that failed, what it keeps,
     *  which is nothing but where its media came in whole and the rest of it
     *  did not. */
    val size: Long?
        get() = when (status) {
            is DownloadStatus.Downloaded -> status.bytes
            is DownloadStatus.CheckNeeded -> status.bytes
            is DownloadStatus.Failed -> onPhoneBytes.takeIf { it > 0 }
            else -> title.estimatedBytes ?: onPhoneBytes.takeIf { it > 0 }
        }

    /** Whether some of it is on the phone: what the storage line, "delete
     *  all" and the questions before a sign-out count. Some of its media - a
     *  title partly downloaded counts; one queued, with nothing but its
     *  artwork there, doesn't (docs/chino downloads). */
    val onPhone: Boolean get() = mediaBytes > 0 || status is DownloadStatus.Downloaded || status is DownloadStatus.CheckNeeded
}

/**
 * What of [entries] is on the phone - the one figure the downloads screen's
 * line, "delete all" and the questions before a sign-out compute: the titles
 * with something of their media there ([DownloadEntry.onPhone]) and, of the
 * same titles, what they take ([DownloadEntry.onPhoneBytes]). The number and
 * the size count the same titles.
 */
fun onThePhone(entries: List<DownloadEntry>): DownloadsSummary {
    val there = entries.filter { it.onPhone }
    return DownloadsSummary(there.size, there.sumOf { it.onPhoneBytes })
}

/** What downloads take on the phone, and what is left there. */
data class StorageUse(val usedBytes: Long, val freeBytes: Long)

/** An account's downloads on the phone, as a question before they go names
 *  them: how many, and what they take. */
data class DownloadsSummary(val titles: Int, val bytes: Long) {
    val isEmpty: Boolean get() = titles == 0
}

/** Titles the server's decision took off the phone (POST /me/available
 *  left them out), named once on the downloads screen. */
data class DeletedNotice(val titles: List<String>)

/** The download preferences: the quality, as a size class ([sizeClass]),
 *  and whether mobile data may be used. */
data class DownloadPreferences(
    val quality: Int = DEFAULT_DOWNLOAD_QUALITY,
    val wifiOnly: Boolean = true,
)

/** One of the qualities Settings offers, with what an hour of video takes at
 *  it as the server's playlists say; null where too few titles say it. */
data class DownloadQuality(val sizeClass: Int, val bytesPerHour: Long?) {
    /** As the player's quality menu names it: "Medium (720p)". */
    val name: String get() = cloud.nalet.chino.mobile.ui.player.qualityTitle(sizeClass)
}

/** What a check with the server came to, for the account signed in now. */
sealed interface AvailabilityCheck {
    /** The server answered: [deleted] titles went from the phone - its
     *  second answer that they are not the account's - and [marked] ones
     *  have its first such answer only, which counts as no answer for what
     *  else follows from a check (a subtitle it no longer serves, a download
     *  it refused). */
    data class Done(val deleted: List<DownloadedTitle>, val marked: List<DownloadedTitle> = emptyList()) : AvailabilityCheck

    /** No answer, or not for this account (it changed meanwhile): nothing
     *  changed. */
    data object NotAnswered : AvailabilityCheck
}

/**
 * Downloads on this platform: Media3's download manager on Android. The
 * screens read and act through this, always for the account signed in now
 * on the connected server (the active owner); a platform without downloads
 * has none (AppContainer.downloads is null) and shows no download control.
 */
interface Downloads {
    /** The active owner's titles on the phone or on their way, newest
     *  first. Empty while nobody is signed in. */
    val entries: StateFlow<List<DownloadEntry>>

    val preferences: StateFlow<DownloadPreferences>

    /** Titles the server took off the phone for the active owner that the
     *  downloads screen has not shown yet; null for none. */
    val notice: StateFlow<DeletedNotice?>

    suspend fun setQuality(sizeClass: Int)

    suspend fun setWifiOnly(wifiOnly: Boolean)

    /** Starts the download [plan] describes, for the active owner; it goes
     *  through the platform's download manager from here, and shows in
     *  [entries] at once. A title another account downloaded already is
     *  that account's and this one's from now on. */
    suspend fun start(plan: DownloadPlan)

    /** Stops a download on its way: the active owner's claim goes, and its
     *  media with the last claim. */
    fun cancel(itemId: String)

    /** Deletes a title from the active owner's downloads, at once and
     *  without asking; its media goes with the last claim. */
    fun delete(itemId: String)

    /** Deletes all of the active owner's downloads. */
    fun deleteAll()

    /** Starts a failed download again, where it stopped. */
    fun retry(itemId: String)

    /** What the active owner's downloads take and what is free, as the
     *  phone counts them now - a walk of the disk, off the main thread. */
    suspend fun storage(): StorageUse

    /** Whether [owner] has [itemId] on the phone, or on its way. */
    fun isOnPhone(owner: Owner, itemId: String): Boolean

    /** [owner]'s downloads on the phone, for the question before they go. */
    fun summaryOf(owner: Owner): DownloadsSummary

    /** All downloads of [server] on the phone, whoever they are for. */
    fun summaryOfServer(server: String): DownloadsSummary

    /** An explicit sign-out or removal of [owner]'s account on this phone:
     *  its claims go, and the media whose last claim it was. */
    suspend fun dropOwner(owner: Owner)

    /** A server change: every download of [server] goes. */
    suspend fun dropServer(server: String)

    /** The notice's [titles] were shown: they are not told again. */
    fun noticeSeen(titles: List<String>)

    /** Asks the server which of the active owner's downloads - [itemIds],
     *  or all of them - it may watch now, in one request, and deletes the
     *  others, as the library decides. */
    suspend fun checkAvailability(itemIds: List<String>? = null): AvailabilityCheck

    /** Asks the person once whether downloads may show their progress in a
     *  notification, where the platform asks that. */
    fun askToNotify() {}

    /** A file of [itemId]'s folder - its poster, its backdrop - as a URL an
     *  image loader reads; null for none. */
    fun fileUrl(itemId: String, file: String?): String?
}

/**
 * What a download of a title takes, worked out before it starts
 * ([DownloadPlanner]): what plays, what is kept and what it comes to.
 */
data class DownloadPlan(
    val item: Item,
    val seriesTitle: String?,
    val info: PlayInfo,
    val segments: List<Segment>,
    /** The master the download reads, without its stream token. */
    val masterUrl: String,
    /** The master as it was read: its text, which the platform parses again
     *  to find [variant] and [audios] by their URIs, and what it says. */
    val masterText: String,
    val master: HlsMaster,
    val variant: HlsVariant,
    val audios: List<HlsRendition>,
    val subtitles: List<cloud.nalet.chino.mobile.data.api.SidecarSubtitle>,
    val quality: Int,
    val estimatedBytes: Long?,
    /** The server it comes from: the connected one's API base. */
    val server: String,
    /** The viewer's saved position, as the server had it (0: none, or
     *  finished); null where it could not be read. */
    val savedPositionSec: Int? = null,
)
