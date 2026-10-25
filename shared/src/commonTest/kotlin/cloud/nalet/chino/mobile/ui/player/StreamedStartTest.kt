package cloud.nalet.chino.mobile.ui.player

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A downloaded title streamed - and cast - after it was played offline
 *  starts where the phone left it, never at the server's older position. */
class StreamedStartTest {
    @Test
    fun theNewerOfflinePositionIsSentFirstAndTheServerStartsFromIt() = runTest {
        // Played offline from 10:00 to 40:00; the server still has 10:00.
        var unsent = true
        var serverHas = 600
        var flushes = 0
        val start = savedPositionToStream(
            hasUnsent = { unsent },
            flush = {
                flushes++
                serverHas = 2400
                unsent = false
            },
            lastPlayedSec = { 2400 },
            serverSec = { serverHas },
        )
        assertEquals(2400, start)
        assertEquals(1, flushes)
    }

    @Test
    fun whereItCantBeSentYetThePhonesOwnPosition() = runTest {
        assertEquals(
            2400,
            savedPositionToStream(hasUnsent = { true }, flush = { error("HTTP 500") }, lastPlayedSec = { 2400 }, serverSec = { 600 }),
        )
        assertEquals(
            2400,
            savedPositionToStream(hasUnsent = { true }, flush = {}, lastPlayedSec = { 2400 }, serverSec = { 600 }),
        )
    }

    @Test
    fun nothingUnsentTheServersPositionWithoutAFlush() = runTest {
        var flushes = 0
        assertEquals(600, savedPositionToStream(hasUnsent = { false }, flush = { flushes++ }, lastPlayedSec = { 2400 }, serverSec = { 600 }))
        assertEquals(0, flushes)
        assertEquals(null, savedPositionToStream(hasUnsent = { false }, flush = {}, lastPlayedSec = { 2400 }, serverSec = { null }))
    }
}
