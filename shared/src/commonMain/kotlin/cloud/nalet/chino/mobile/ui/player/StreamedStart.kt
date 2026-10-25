package cloud.nalet.chino.mobile.ui.player

import kotlinx.coroutines.CancellationException

/**
 * The saved position a streamed title starts from - the one a TV starts
 * from too, a title cast as it opens. A title of the phone's played on it
 * without the server has a newer position the server hasn't had yet
 * ([hasUnsent]): it is sent first ([flush]), so the server and the TV start
 * where it was; where it can't go yet, the phone's own ([lastPlayedSec]), as
 * a download plays. Else the server's ([serverSec]); null where it couldn't
 * be read.
 */
suspend fun savedPositionToStream(
    hasUnsent: suspend () -> Boolean,
    flush: suspend () -> Unit,
    lastPlayedSec: suspend () -> Int?,
    serverSec: suspend () -> Int?,
): Int? {
    if (hasUnsent()) {
        try {
            flush()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        if (hasUnsent()) return lastPlayedSec()
    }
    return serverSec()
}
