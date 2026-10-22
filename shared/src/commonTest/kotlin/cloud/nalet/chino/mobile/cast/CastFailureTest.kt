package cloud.nalet.chino.mobile.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** "{device} can't play this title." and the reason under details, by the
 *  HTTP status the TV names, else its error code, else the phone's request. */
class CastFailureTest {
    private val host = "media.example.com"

    private fun http(status: Int) = castFailureDetails(CastErrorReport(httpStatus = status), host)
    private fun code(code: Int) = castFailureDetails(CastErrorReport(detailedCode = code), host)

    @Test
    fun theLineNamesTheDevice() {
        assertEquals("Living Room TV can't play this title.", CastFailure("Living Room TV", "the TV reported: unsupported media").headline)
        assertEquals("The TV can't play this title.", CastFailure(UNNAMED_CAST_DEVICE, "").headline)
    }

    @Test
    fun anHttpStatusIsReadAsTheDesignSystemReadsIt() {
        assertEquals("the TV reported: the server ran into a problem (HTTP 503)", http(503))
        assertEquals("the TV reported: the server ran into a problem (HTTP 500)", http(500))
        assertEquals("the TV reported: the server is busy (HTTP 429)", http(429))
        assertEquals("the TV reported: the server has no stream of this title (HTTP 404)", http(404))
        assertEquals("the TV reported: the server has no stream of this title (HTTP 410)", http(410))
        assertEquals("the TV reported: no access to this title (HTTP 403)", http(403))
        assertEquals("the TV reported: the server didn't accept the sign-in (HTTP 401)", http(401))
        assertEquals("the TV reported: no answer from media.example.com (HTTP 408)", http(408))
        assertEquals("the TV reported: the server didn't accept the request (HTTP 400)", http(400))
    }

    @Test
    fun withoutAStatusTheReceiversCodeSaysWhatFailed() {
        assertEquals("the TV reported: unsupported media (error 104)", code(104))
        assertEquals("the TV reported: unsupported media (error 102)", code(102))
        assertEquals("the TV reported: couldn't load the stream from media.example.com (error 311)", code(311))
        assertEquals("the TV reported: couldn't load the stream from media.example.com (error 103)", code(103))
        assertEquals("the TV reported: couldn't read the stream (error 411)", code(411))
        assertEquals("the TV reported: couldn't load the subtitles (error 600)", code(600))
        assertEquals("the TV reported: playback error (error 905)", code(905))
    }

    @Test
    fun theStatusWinsOverTheCode() {
        assertEquals(
            "the TV reported: the server ran into a problem (HTTP 502)",
            castFailureDetails(CastErrorReport(httpStatus = 502, detailedCode = 311), host),
        )
    }

    @Test
    fun whereThePhoneSawTheRequestFail() {
        assertEquals("no answer from the TV in time", castFailureDetails(CastErrorReport(senderStatus = 15), host))
        assertEquals("the connection to the TV dropped", castFailureDetails(CastErrorReport(senderStatus = 7), host))
        assertEquals("the TV reported an error (status 2100)", castFailureDetails(CastErrorReport(senderStatus = 2100), host))
        assertEquals("the TV reported an error", castFailureDetails(CastErrorReport(), host))
        // A code the receiver named comes before the phone's status.
        assertEquals("the TV reported: unsupported media (error 104)", castFailureDetails(CastErrorReport(detailedCode = 104, senderStatus = 2104), host))
    }

    @Test
    fun theStatusIsFoundWhereTheReceiverPutsIt() {
        assertEquals(404, httpStatusOf(mapOf("httpStatus" to "404"), null))
        assertEquals(503, httpStatusOf(mapOf("StatusCode" to " 503 "), null))
        assertEquals(503, httpStatusOf(emptyMap(), "Load failed: HTTP 503"))
        assertEquals(404, httpStatusOf(emptyMap(), "status: 404"))
        assertNull(httpStatusOf(emptyMap(), "Load failed"))
        assertNull(httpStatusOf(mapOf("httpStatus" to "nine"), null))
        assertNull(httpStatusOf(mapOf("httpStatus" to "1234"), null))
    }

    @Test
    fun theBarSaysPlayingOnTheDevice() {
        assertEquals("playing on Living Room TV", castDeviceLine("Living Room TV"))
        assertEquals("playing on the TV", castDeviceLine(null))
        assertEquals("playing on the TV", castDeviceLine("  "))
    }
}
