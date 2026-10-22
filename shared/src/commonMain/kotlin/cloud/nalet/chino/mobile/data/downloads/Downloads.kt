package cloud.nalet.chino.mobile.data.downloads

import cloud.nalet.chino.mobile.data.api.PlayInfo
import cloud.nalet.chino.mobile.data.api.Segment
import cloud.nalet.chino.mobile.data.model.Item
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    /** When the download was asked for, ms since the epoch. */
    @SerialName("added_at") val addedAtMs: Long,
    /** The server it came from (its API base): progress made with it goes
     *  to that server only. */
    val server: String,
    /** The server answered 404 or 410 for it on a later look: it is no
     *  longer in the library. The download stays until it is deleted. */
    @SerialName("gone_from_library") val goneFromLibrary: Boolean = false,
    /** Its subtitles and artwork are on the phone. */
    @SerialName("extras_done") val extrasDone: Boolean = false,
    /** Why it stopped, the last time it did; null while it goes on. */
    val failure: DownloadFailure? = null,
    /** Where the server had the viewer as the download was asked for: where
     *  it resumes without the server before it is played on this phone. */
    @SerialName("saved_position_sec") val savedPositionSec: Int? = null,
) {
    val isEpisode: Boolean get() = item.kind == "episode" || item.parentId != null
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
     *  Wi-Fi". It starts by itself on Wi-Fi. */
    data object WaitingForWifi : DownloadStatus

    /** Mobile data is allowed and the phone has no connection at all. */
    data object WaitingForConnection : DownloadStatus

    /** The battery is low: it carries on once the phone charges. */
    data object WaitingForBattery : DownloadStatus

    /** On its way, [percent] in whole percent. */
    data class Downloading(val percent: Int) : DownloadStatus

    /** On the phone, [bytes] of it. */
    data class Downloaded(val bytes: Long) : DownloadStatus

    /** It stopped for [reason]: "couldn't download". */
    data class Failed(val reason: DownloadFailure) : DownloadStatus

    /** Being deleted. */
    data object Removing : DownloadStatus
}

/** Why a download stopped, each one sentence the control shows. */
@Serializable
enum class DownloadFailure(val sentence: String) {
    NoSpace("Not enough space on this phone."),
    NoAnswer("Check the connection to the server, then try again."),
    ServerProblem("The server ran into a problem. Try again in a moment."),
    Refused("The server didn't accept the download."),
    Gone("This title isn't in the library anymore."),
    StreamOnly("This title can only be streamed for now."),
    Stopped("The download stopped before it was done. Try again."),
}

/** A title on the phone or on its way, and where it is. [bytes]: what is on
 *  the phone of it so far. */
data class DownloadEntry(
    val title: DownloadedTitle,
    val status: DownloadStatus,
    val bytes: Long,
) {
    val itemId: String get() = title.itemId

    /** The size the downloads screen shows: what it took once it is on the
     *  phone, what the server said it would take before; null when neither
     *  is known. */
    val size: Long?
        get() = if (status is DownloadStatus.Downloaded) status.bytes else title.estimatedBytes ?: bytes.takeIf { it > 0 }
}

/** What downloads take on the phone, and what is left there. */
data class StorageUse(val usedBytes: Long, val freeBytes: Long)

/** The download preferences: the quality, as a size class ([sizeClass]),
 *  and whether mobile data may be used. */
data class DownloadPreferences(
    val quality: Int = DEFAULT_DOWNLOAD_QUALITY,
    val wifiOnly: Boolean = true,
)

/** One of the qualities Settings offers, with what an hour of video takes at
 *  it as the server's playlists say; null where none said. */
data class DownloadQuality(val sizeClass: Int, val bytesPerHour: Long?) {
    val name: String get() = qualityName(sizeClass)
}

/**
 * Downloads on this platform: Media3's download manager on Android. The
 * screens read and act through this; a platform without downloads has none
 * (AppContainer.downloads is null) and shows no download control.
 */
interface Downloads {
    /** Everything on the phone or on its way, newest first. */
    val entries: StateFlow<List<DownloadEntry>>

    val preferences: StateFlow<DownloadPreferences>

    suspend fun setQuality(sizeClass: Int)

    suspend fun setWifiOnly(wifiOnly: Boolean)

    /** Starts the download [plan] describes; it goes through the platform's
     *  download manager from here, and shows in [entries] at once. */
    suspend fun start(plan: DownloadPlan)

    /** Stops a download on its way and deletes what it fetched. */
    fun cancel(itemId: String)

    /** Deletes a title from the phone, at once and without asking. */
    fun delete(itemId: String)

    /** Deletes every title from the phone. */
    fun deleteAll()

    /** Starts a failed download again, where it stopped. */
    fun retry(itemId: String)

    /** What downloads take and what is free, as the phone counts them now -
     *  a walk of the disk, off the main thread. */
    suspend fun storage(): StorageUse

    /** Records whether the server still has [itemId] in its library. */
    suspend fun setInLibrary(itemId: String, inLibrary: Boolean)

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
