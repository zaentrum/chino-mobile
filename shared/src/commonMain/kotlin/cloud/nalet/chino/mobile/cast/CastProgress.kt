package cloud.nalet.chino.mobile.cast

import cloud.nalet.chino.mobile.data.api.ProgressBody
import cloud.nalet.chino.mobile.data.downloads.ProgressSync
import kotlinx.coroutines.CancellationException
import cloud.nalet.chino.mobile.ui.player.ProgressGuard

/*
 * The position the TV plays, written back to chino-api by the phone that cast
 * it - as the phone's own player writes it: every 10 seconds while playing, at
 * a pause, and once more as it stops (the end, another title, stop casting).
 * What counts as played follows the player's guard (ProgressGuard): nothing
 * before the TV has reached the position it was told to start at. chino-api
 * decides from the positions whether the title is finished; the phone sends
 * positions only.
 *
 * Only what the TV confirmed counts. Between the TV's statuses the Cast SDK
 * reports a position of its own reckoning, from the last status and the time
 * since; after a gap in its ticks (the phone asleep, the app frozen) or while
 * the connection is lost, that is a guess, and nothing counts until the TV
 * reports again.
 */

/** The TV's player, as its media status says. */
enum class RemotePlayerState { Unknown, Idle, Loading, Buffering, Playing, Paused }

/** Why the TV's player is idle. */
enum class RemoteIdleReason { None, Finished, Canceled, Interrupted, Error }

/** One media status of the TV. */
data class RemoteMediaStatus(
    /** The phone's marker on the media ([CastTag]); null for media no phone
     *  of this app cast, or none. */
    val tag: CastTag?,
    val state: RemotePlayerState,
    val idleReason: RemoteIdleReason = RemoteIdleReason.None,
    val positionMs: Long,
    val durationMs: Long,
)

/** One write of POST /items/{id}/progress, for the account [accountId].
 *  [final]: the last one of what the TV played, the position the phone keeps
 *  when casting stops. */
data class CastProgressWrite(
    val accountId: String,
    val itemId: String,
    val positionSec: Int,
    val durationSec: Int,
    val final: Boolean,
)

/** What [w] sends: the TV's position as it plays it now - with no age, so
 *  chino-api writes it at once, and a position the phone kept from playing
 *  the title earlier without the server, sent later with its age
 *  (played_ms_ago), never goes over it. */
fun castProgressBody(w: CastProgressWrite): ProgressBody =
    ProgressBody(positionSec = w.positionSec, durationSec = w.durationSec)

/**
 * Sends [w] through [post] (POST /items/{id}/progress for its account) and,
 * once the server has it, tells [progressSync]: a position the phone kept of
 * the title from playing it without the server is older - it goes unsent -
 * and a download of the title resumes on the phone where the TV is. True
 * when the server has it.
 */
suspend fun writeCastProgress(
    w: CastProgressWrite,
    post: suspend (itemId: String, body: ProgressBody) -> Unit,
    progressSync: ProgressSync?,
): Boolean {
    val sent = try {
        post(w.itemId, castProgressBody(w))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
    if (sent) {
        try {
            progressSync?.keptElsewhere(w.accountId, w.itemId, w.positionSec, w.durationSec)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Unit
        }
    }
    return sent
}

/** How often the position is written while the TV plays, as the phone's
 *  player writes its own. */
const val CAST_PROGRESS_INTERVAL_MS: Long = 10_000L

/** A gap this long between two things heard of the TV - a status, a tick of
 *  the SDK's, which ticks every second while it plays - and the SDK's position
 *  is its own guess: the phone was asleep, or the app frozen. */
const val CAST_TICK_GAP_MS: Long = 5_000L

/** How often the phone asks the TV for its status while it waits for one. */
const val CAST_STATUS_ASK_MS: Long = 5_000L

/**
 * Turns the TV's media status and its progress ticks into position writes.
 * Only the media this phone cast for the account signed in now is written
 * ([writer]: the [CastTag]'s owner is the phone's owner id of it), and only
 * one whose position may be written ([CastTag.writesProgress]): another
 * phone's, another account's, another sender's and an extra are left alone.
 * When the account signed in changes, what the TV plays is no longer this
 * account's to write and is let go without a write ([writerChanged]).
 */
class CastProgressReporter(
    /** Who writes now: the account signed in, and the phone's owner id of
     *  it; null while none is known. */
    private val writer: () -> CastWriter?,
    /** A monotonic clock, in ms. */
    private val now: () -> Long,
    private val write: (CastProgressWrite) -> Unit,
    /** Asks the TV for its status now. */
    private val askStatus: () -> Unit = {},
) {
    private class Tracked(val load: String, val itemId: String, val writer: CastWriter, since: Long, val floorSec: Int?) {
        val guard = ProgressGuard(writable = true)
        var durationMs = 0L
        var state = RemotePlayerState.Unknown
        var lastWriteAt = since
    }

    private class Expected(val load: String, val startMs: Long, val floorMs: Long?)

    /** The load last let go, and the last position of it the TV confirmed. */
    private var ended: Pair<String, Int>? = null

    private var tracked: Tracked? = null

    /** A load the phone sent, which the TV has not reported yet. */
    private var expected: Expected? = null

    /** When the TV was last heard of: a status, or a tick. */
    private var heardAt: Long? = null

    /** The SDK's positions are its own guess until the TV reports again. */
    private var unconfirmed = false
    private var askedAt: Long? = null

    /** The item the TV plays and this phone writes; null for none. */
    val trackedItemId: String? get() = tracked?.itemId

    /** The phone told the TV to play [itemId] from [startMs], as the load
     *  [load]: positions of it short of there are not played yet. A load
     *  that takes over from where the TV was - a recovery, links signed anew
     *  - writes nothing short of [floorMs], the position already kept. */
    fun loading(load: String, itemId: String, startMs: Long, floorMs: Long? = null) {
        expected = Expected(load, startMs, floorMs)
    }

    /** The last position the TV confirmed of the load [load], in ms: of the
     *  one tracked now, or of the one let go last; null when none was. */
    fun lastConfirmedMs(load: String): Long? {
        tracked?.takeIf { it.load == load }?.guard?.position()?.let { return it * 1000L }
        return ended?.takeIf { it.first == load }?.second?.let { it * 1000L }
    }

    /** The TV reported its media status. */
    fun onStatus(status: RemoteMediaStatus) {
        val at = now()
        heardAt = at
        unconfirmed = false
        askedAt = null
        val tag = status.tag
        val w = writer()
        val mine = tag != null && tag.writesProgress && w != null && tag.owner == w.owner
        val t = tracked
        if (t != null && (!mine || tag?.load != t.load)) {
            // The TV plays something else now - another title, the same one
            // loaded anew, someone else's - or it is no longer this
            // account's: what was tracked ends, written while it still is.
            finish(t, write = t.writer == w)
        }
        if (tag == null || w == null || !mine) return
        val current = tracked ?: run {
            val e = expected?.takeIf { it.load == tag.load }
            expected = expected?.takeUnless { it.load == tag.load }
            Tracked(tag.load, tag.itemId, w, at, floorSec = e?.floorMs?.let { (it / 1000L).toInt() }).also { started ->
                tracked = started
                e?.let { started.guard.expectSeek(it.startMs / 1000.0) }
            }
        }
        if (status.durationMs > 0) current.durationMs = status.durationMs
        val was = current.state
        current.state = status.state
        when (status.state) {
            RemotePlayerState.Playing -> current.guard.played(status.positionMs / 1000.0)
            RemotePlayerState.Paused -> if (was == RemotePlayerState.Playing) save(current, final = false)
            RemotePlayerState.Idle -> if (status.idleReason != RemoteIdleReason.None) finish(current, write = true)
            else -> Unit
        }
    }

    /** A progress tick of the TV (the Cast SDK's, every second or so). */
    fun onProgress(positionMs: Long, durationMs: Long) {
        val at = now()
        val gap = heardAt?.let { at - it }
        heardAt = at
        val t = tracked ?: return
        if (t.writer != writer()) {
            // Another account is signed in now: not its title to write.
            tracked = null
            return
        }
        if (gap != null && gap > CAST_TICK_GAP_MS) unconfirmed = true
        if (unconfirmed) {
            ask(at)
            return
        }
        if (durationMs > 0) t.durationMs = durationMs
        if (t.state != RemotePlayerState.Playing) return
        t.guard.played(positionMs / 1000.0)
        if (at - t.lastWriteAt >= CAST_PROGRESS_INTERVAL_MS) save(t, final = false)
    }

    /** The connection to the TV is lost for now: nothing counts until the
     *  TV reports again. */
    fun onSuspended() {
        unconfirmed = true
    }

    /** The session is ending - the viewer stopped casting, or it was lost:
     *  the last position the TV confirmed is the one the phone keeps. */
    fun onSessionEnding() {
        val t = tracked ?: return
        finish(t, write = t.writer == writer())
    }

    /** The account signed in changed: what the TV plays is let go, unwritten. */
    fun writerChanged() {
        tracked = null
        expected = null
        silenced = null
    }

    /** An account whose positions are written no more - the server deleted
     *  it: what the TV plays is let go unwritten, and nothing more is
     *  written for it, whatever the TV reports before casting has stopped,
     *  until another account signs in ([writerChanged]). */
    fun letGoUnwritten(accountId: String) {
        silenced = accountId
        tracked = null
        expected = null
    }

    /** The account [letGoUnwritten] named, while it is still signed in. */
    private var silenced: String? = null

    private fun ask(at: Long) {
        val last = askedAt
        if (last != null && at - last < CAST_STATUS_ASK_MS) return
        askedAt = at
        askStatus()
    }

    private fun finish(t: Tracked, write: Boolean) {
        if (write) save(t, final = true)
        t.guard.position()?.let { ended = t.load to it }
        if (tracked === t) tracked = null
    }

    private fun save(t: Tracked, final: Boolean) {
        t.lastWriteAt = now()
        if (t.writer != writer() || t.writer.accountId == silenced) return
        val pos = t.guard.position() ?: return
        // Never a position short of the one a takeover started from.
        if (t.floorSec != null && pos < t.floorSec) return
        write(CastProgressWrite(t.writer.accountId, t.itemId, pos, (t.durationMs / 1000L).toInt(), final))
    }
}

/**
 * Where a title the TV played stood as it failed, to load it again there:
 * the later of the last position the TV confirmed of it ([confirmedMs], the
 * reporter's, its ticks counted) and the position of its last status
 * ([lastStatusMs]) - the TV sends a status only when its state changes, so
 * after a long stretch of playing that one is old. Null for neither.
 */
fun castRecoveryAtMs(confirmedMs: Long?, lastStatusMs: Long?): Long? = listOfNotNull(confirmedMs, lastStatusMs).maxOrNull()
