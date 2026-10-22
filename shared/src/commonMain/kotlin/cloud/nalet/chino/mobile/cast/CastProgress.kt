package cloud.nalet.chino.mobile.cast

import cloud.nalet.chino.mobile.ui.player.ProgressGuard

/*
 * The position the TV plays, written back to chino-api by the phone that cast
 * it - as the phone's own player writes it: every 10 seconds while playing, at
 * a pause, and once more as it stops (the end, another title, stop casting).
 * What counts as played follows the player's guard (ProgressGuard): nothing
 * before the TV has reached the position it was told to start at. chino-api
 * decides from the positions whether the title is finished; the phone sends
 * positions only.
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

/** One write of POST /items/{id}/progress. [final]: the last one of what the
 *  TV played, the position the phone keeps when casting stops. */
data class CastProgressWrite(val itemId: String, val positionSec: Int, val durationSec: Int, val final: Boolean)

/** How often the position is written while the TV plays, as the phone's
 *  player writes its own. */
const val CAST_PROGRESS_INTERVAL_MS: Long = 10_000L

/**
 * Turns the TV's media status and its progress ticks into position writes.
 * Only the media this phone's signed-in account cast ([owner], the
 * [CastTag]'s key) is written, and only one whose position may be written
 * ([CastTag.writesProgress]): another account's, another sender's and an
 * extra are left alone. When the account signed in changes, what the TV plays
 * is no longer this account's to write and is let go without a write.
 */
class CastProgressReporter(
    /** The owner key of the account signed in now ([castOwnerKey]). */
    private val owner: () -> String?,
    /** A monotonic clock, in ms. */
    private val now: () -> Long,
    private val write: (CastProgressWrite) -> Unit,
) {
    private class Tracked(val itemId: String, val owner: String, val since: Long) {
        val guard = ProgressGuard(writable = true)
        var durationMs = 0L
        var state = RemotePlayerState.Unknown
        var lastWriteAt = since
    }

    private var tracked: Tracked? = null

    /** A load the phone sent, which the TV has not reported yet. */
    private var expected: Pair<String, Long>? = null

    /** The item the TV plays and this phone writes; null for none. */
    val trackedItemId: String? get() = tracked?.itemId

    /** The phone told the TV to play [itemId] from [startMs]: positions
     *  short of it are not played yet. */
    fun loading(itemId: String, startMs: Long) {
        val t = tracked
        if (t != null && t.itemId == itemId) {
            t.guard.expectSeek(startMs / 1000.0)
        } else {
            expected = itemId to startMs
        }
    }

    /** The TV reported its media status. */
    fun onStatus(status: RemoteMediaStatus) {
        val tag = status.tag
        val me = owner()
        val mine = tag != null && tag.writesProgress && me != null && tag.owner == me
        val t = tracked
        if (t != null && (!mine || tag?.itemId != t.itemId)) {
            // The TV plays something else now, or it is no longer this
            // account's: what was tracked ends - written when it still is.
            finish(t, write = t.owner == me)
        }
        if (tag == null || !mine) return
        val current = tracked ?: Tracked(tag.itemId, tag.owner, now()).also { started ->
            tracked = started
            expected?.takeIf { it.first == tag.itemId }?.let { (_, startMs) -> started.guard.expectSeek(startMs / 1000.0) }
            expected = null
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
        val t = tracked ?: return
        if (durationMs > 0) t.durationMs = durationMs
        if (t.state != RemotePlayerState.Playing) return
        t.guard.played(positionMs / 1000.0)
        if (now() - t.lastWriteAt >= CAST_PROGRESS_INTERVAL_MS) save(t, final = false)
    }

    /** The session is ending - the viewer stopped casting, or it was lost:
     *  the last position, [positionMs] where the TV was playing, is the one
     *  the phone keeps. */
    fun onSessionEnding(positionMs: Long?) {
        val t = tracked ?: return
        if (t.state == RemotePlayerState.Playing && positionMs != null) t.guard.played(positionMs / 1000.0)
        finish(t, write = t.owner == owner())
    }

    private fun finish(t: Tracked, write: Boolean) {
        if (write) save(t, final = true)
        if (tracked === t) tracked = null
    }

    private fun save(t: Tracked, final: Boolean) {
        t.lastWriteAt = now()
        val pos = t.guard.position() ?: return
        write(CastProgressWrite(t.itemId, pos, (t.durationMs / 1000L).toInt(), final))
    }
}
