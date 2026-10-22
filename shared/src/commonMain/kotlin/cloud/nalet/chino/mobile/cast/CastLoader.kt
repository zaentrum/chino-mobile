package cloud.nalet.chino.mobile.cast

import cloud.nalet.chino.mobile.data.auth.withStreamToken
import cloud.nalet.chino.mobile.ui.player.parseHlsMaster
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * A title loaded on the TV, start to end: who it is cast for, whether the TV
 * plays it already, the links signed for the TV, the master read first, the
 * load, the audio and subtitle once the TV has it; and a load or a title that
 * fails - the phone fetches the TV's master itself for the reason, and loads
 * it once more with its links signed anew before it says the TV can't. The
 * TV and the server behind ports, so commonTest drives it all; on a device
 * they are the Cast SDK's RemoteMediaClient and the phone's HTTP client
 * (ChinoCast).
 */

/** How a load on the TV ended. */
sealed interface CastLoadResult {
    /** The TV plays it. */
    data object Playing : CastLoadResult

    /** The TV can't play it; casting stops. */
    data class Failed(val failure: CastFailure) : CastLoadResult

    /** No session to load into, or it ended first - the viewer stopped
     *  casting - or another load took its place. */
    data object Ended : CastLoadResult
}

/** How the TV answered one load request. */
sealed interface CastAnswer {
    data object Playing : CastAnswer

    /** The TV, or the phone's request, failed: [report] says what each said. */
    data class Failed(val report: CastErrorReport) : CastAnswer

    /** The session ended first, or another load took its place. */
    data object Ended : CastAnswer
}

/** The media the TV plays, loaded again: its links signed with [token], at
 *  [atMs], playing or paused as [autoplay] says, with the tracks
 *  [activeTrackIds] on - as the load [load]. */
data class CastReload(
    val token: CastToken,
    val load: String,
    val atMs: Long,
    val autoplay: Boolean,
    val activeTrackIds: List<Long>,
)

/** What the phone got fetching a master itself, as the TV fetches it: no
 *  sign-in, the stream token in the URL. */
sealed interface MasterFetch {
    data class Answered(val status: Int, val body: String) : MasterFetch

    data object NoAnswer : MasterFetch
}

/** The TV, as the loads drive it. */
internal interface CastTv {
    /** The name the device goes by ([castDeviceName]); null for none. */
    val device: String?

    /** What it plays now; null with no media. */
    fun status(): RemoteMediaStatus?

    /** The master the media it plays was loaded from. */
    fun contentUrl(): String?

    /** The tracks of the media it plays, as it lists them. */
    fun tracks(): List<TvTrack>
    fun activeTrackIds(): List<Long>

    /** Loads [media] as cast by [owner], and waits for the TV's answer. */
    suspend fun load(media: CastMedia, owner: String): CastAnswer

    /** Loads the media it plays again ([CastReload]), and waits for the answer. */
    suspend fun reload(again: CastReload): CastAnswer

    suspend fun setActiveTracks(ids: List<Long>): Boolean
    fun play()
}

/** Where a title the TV played stood as it failed. */
data class CastStoppedAt(val itemId: String, val atMs: Long, val playing: Boolean, val activeTrackIds: List<Long>)

internal class CastLoader(
    /** Who casts: the account signed in, once it is known; null when none is. */
    private val writer: suspend () -> CastWriter?,
    /** A token for the TV's links; [fresh]: minted anew, the current one
     *  turned down. */
    private val token: suspend (fresh: Boolean) -> CastToken,
    private val fetchMaster: suspend (url: String) -> MasterFetch,
    /** The wall clock, epoch ms: the links' life is told by it. */
    private val now: () -> Long,
    /** A new load's own id ([CastTag.load]). */
    private val newLoad: () -> String,
    private val serverHost: () -> String,
    /** The phone is about to tell the TV to play [itemId] from [startMs] as
     *  the load [load] (the position writes' start guard). */
    private val loading: (load: String, itemId: String, startMs: Long) -> Unit,
    /** A newer load is asked for: the one the TV is answering gives way. */
    private val superseded: () -> Unit = {},
) {
    /** One load at a time: the viewer's, a reload for the links, a retry. */
    private val mutex = Mutex()

    /** The viewer's latest load; an older one still waiting gives up. */
    private var latest = 0L

    /**
     * Plays [input] on [tv]. A title the TV plays for this phone already
     * plays on, unless the phone says where to start ([CastMediaInput.restart]).
     * Else its links are signed for the TV, its master read - for the
     * subtitles it carries itself ([sidecarsBesideTheMaster]) and for what
     * the server answers - and it is loaded; once the TV has it, the audio and
     * subtitle the phone asks for are turned on ([castActiveTracks]).
     */
    suspend fun load(tv: CastTv, input: CastMediaInput): CastLoadResult {
        val mine = ++latest
        superseded()
        return mutex.withLock { if (mine == latest) loadLocked(tv, input, mine) else CastLoadResult.Ended }
    }

    private suspend fun loadLocked(tv: CastTv, input: CastMediaInput, mine: Long): CastLoadResult {
        val owner = writer()?.owner ?: return CastLoadResult.Ended
        val status = tv.status()
        if (!input.restart && playsOnTv(status, input.itemId, input.extraId, owner)) {
            if (input.autoplay && status?.state == RemotePlayerState.Paused) tv.play()
            renewLinksLocked(tv, owner)
            return CastLoadResult.Playing
        }
        var tok = token(false)
        var fetched = fetchMaster(receiverMasterUrl(input, tok.token))
        if (fetched is MasterFetch.Answered && fetched.status == 401) {
            tok = token(true)
            fetched = fetchMaster(receiverMasterUrl(input, tok.token))
        }
        if (fetched is MasterFetch.Answered && fetched.status >= 400) {
            return failed(tv, input.itemId, CastErrorReport(probe = CastProbe.Answered(fetched.status)))
        }
        val master = (fetched as? MasterFetch.Answered)?.takeIf { it.status in 200..299 }?.body?.let(::parseHlsMaster)
        // Asked for something else while the master was read.
        if (mine != latest) return CastLoadResult.Ended
        val first = castMediaFor(input, tok, master, newLoad())
        loading(first.load, first.itemId, first.startMs)
        var answer = tv.load(first, owner)
        if (answer is CastAnswer.Failed) {
            answer = afterFailure(answer.report, first.contentUrl, urlFor = { receiverMasterUrl(input, it.token) }) { again ->
                val media = castMediaFor(input, again, master, newLoad())
                loading(media.load, media.itemId, media.startMs)
                tv.load(media, owner)
            }
        }
        return when (answer) {
            CastAnswer.Playing -> {
                pickTracks(tv, input.tracks)
                CastLoadResult.Playing
            }
            CastAnswer.Ended -> CastLoadResult.Ended
            is CastAnswer.Failed -> failed(tv, input.itemId, answer.report)
        }
    }

    /**
     * A title of this phone's failed on the TV as it played ([at]), the TV
     * saying [report]: loaded once more where it was, its links signed anew,
     * unless the phone's own fetch of the master says why it failed. Null
     * when it plays again, or when there is nothing to load it into; else why
     * the TV can't.
     */
    suspend fun recover(tv: CastTv, at: CastStoppedAt, report: CastErrorReport): CastFailure? = mutex.withLock {
        val url = tv.contentUrl()
        val answer = afterFailure(report, url, urlFor = { t -> url?.let { withStreamToken(it, t.token) } }) { again ->
            val load = newLoad()
            loading(load, at.itemId, at.atMs)
            tv.reload(CastReload(again, load, at.atMs, at.playing, at.activeTrackIds))
        }
        (answer as? CastAnswer.Failed)?.let { failure(tv, at.itemId, it.report) }
    }

    /**
     * The TV plays a title of this phone's again - after a pause, in a
     * session the app resumed: where its links stop working before it ends
     * ([linksRunOut]), it is loaded again where it is, playing or paused as
     * it is, with its tracks, its links signed with a token minted for it.
     * True when it was.
     */
    suspend fun renewLinks(tv: CastTv): Boolean = mutex.withLock {
        val owner = writer()?.owner ?: return@withLock false
        renewLinksLocked(tv, owner)
    }

    private suspend fun renewLinksLocked(tv: CastTv, owner: String): Boolean {
        val s = tv.status() ?: return false
        val tag = s.tag?.takeIf { it.owner == owner } ?: return false
        val on = s.state == RemotePlayerState.Playing || s.state == RemotePlayerState.Paused || s.state == RemotePlayerState.Buffering
        if (!on || !linksRunOut(tag.linksExpireAtMs, now(), s.positionMs, s.durationMs)) return false
        val again = token(false)
        val load = newLoad()
        loading(load, tag.itemId, s.positionMs)
        val answer = tv.reload(CastReload(again, load, s.positionMs, s.state != RemotePlayerState.Paused, tv.activeTrackIds()))
        return answer == CastAnswer.Playing
    }

    /**
     * After the TV failed at the media at [url] with [report]: where it may
     * do on a second try ([castMayRetry]), the phone fetches the master
     * itself. The server turning the TV's link down (401) - the TV tries once
     * more, its links signed with a token minted anew; another refusal, or
     * no answer, is the reason; an answer, the TV tries once more ([again]).
     * Failing again, the phone fetches its master once more for the reason.
     */
    private suspend fun afterFailure(
        report: CastErrorReport,
        url: String?,
        urlFor: (CastToken) -> String?,
        again: suspend (CastToken) -> CastAnswer,
    ): CastAnswer {
        if (!castMayRetry(report)) return CastAnswer.Failed(report)
        val probe = url?.let { fetchMaster(it) }
        when (probe) {
            is MasterFetch.Answered -> if (probe.status >= 400 && probe.status != 401) {
                return CastAnswer.Failed(report.copy(probe = CastProbe.Answered(probe.status)))
            }
            MasterFetch.NoAnswer -> return CastAnswer.Failed(report.copy(probe = CastProbe.NoAnswer))
            null -> Unit
        }
        val tok = token(probe is MasterFetch.Answered && probe.status == 401)
        val second = again(tok)
        if (second !is CastAnswer.Failed) return second
        val seen = when (val p = urlFor(tok)?.let { fetchMaster(it) }) {
            is MasterFetch.Answered -> p.status.takeIf { it >= 400 }?.let { CastProbe.Answered(it) }
            MasterFetch.NoAnswer -> CastProbe.NoAnswer
            null -> null
        }
        return CastAnswer.Failed(second.report.copy(probe = seen))
    }

    /** The audio and subtitle [wish] asks for, once the TV has numbered its
     *  tracks; nothing sent where they are on already. */
    private suspend fun pickTracks(tv: CastTv, wish: CastTrackWish) {
        val ids = castActiveTracks(tv.tracks(), tv.activeTrackIds(), wish) ?: return
        tv.setActiveTracks(ids)
    }

    private fun failed(tv: CastTv, itemId: String, report: CastErrorReport): CastLoadResult =
        CastLoadResult.Failed(failure(tv, itemId, report))

    private fun failure(tv: CastTv, itemId: String, report: CastErrorReport): CastFailure =
        CastFailure(tv.device ?: UNNAMED_CAST_DEVICE, castFailureDetails(report, serverHost()), itemId)
}
