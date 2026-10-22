package cloud.nalet.chino.mobile.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "{device} can't play this title." and the reason under details: the
 *  status the phone got fetching the TV's master itself, else the receiver's
 *  code, else the phone's request. */
class CastFailureTest {
    private val host = "media.example.com"

    private fun status(status: Int, code: Int? = 905) =
        castFailureDetails(CastErrorReport(detailedCode = code, probe = CastProbe.Answered(status)), host)

    private fun code(code: Int) = castFailureDetails(CastErrorReport(detailedCode = code), host)

    @Test
    fun theLineNamesTheDevice() {
        assertEquals("Living Room TV can't play this title.", CastFailure("Living Room TV", "the TV reported: unsupported media").headline)
        assertEquals("The device can't play this title.", CastFailure(UNNAMED_CAST_DEVICE, "").headline)
    }

    @Test
    fun aDeviceGoesByItsOwnNameElseItsModel() {
        assertEquals("Living Room TV", castDeviceName(" Living Room TV ", "Chromecast"))
        assertEquals("Chromecast", castDeviceName("", "Chromecast"))
        assertNull(castDeviceName(null, " "))
        assertEquals("playing on Living Room TV", castDeviceLine("Living Room TV"))
        assertEquals("playing on the device", castDeviceLine(null))
        assertEquals("playing on the device", castDeviceLine("  "))
    }

    @Test
    fun theServersAnswerToThePhoneIsReadAsTheDesignSystemReadsIt() {
        assertEquals("the server ran into a problem (HTTP 503)", status(503))
        assertEquals("the server ran into a problem (HTTP 500)", status(500))
        assertEquals("the server is busy (HTTP 429)", status(429))
        assertEquals("the server has no stream of this title (HTTP 404)", status(404))
        assertEquals("the server has no stream of this title (HTTP 410)", status(410))
        assertEquals("no access to this title (HTTP 403)", status(403))
        assertEquals("no answer from media.example.com (HTTP 408)", status(408))
        assertEquals("the server didn't accept the request (HTTP 400)", status(400))
    }

    @Test
    fun aRefusedLinkIsTheTvsLinkNotTheViewersSignIn() {
        val line = status(401)
        assertEquals("the server turned down the TV's link to the stream (HTTP 401)", line)
        assertFalse(line.contains("sign"), line)
    }

    @Test
    fun noAnswerToThePhoneIsTheConnection() {
        assertEquals("no answer from media.example.com", castFailureDetails(CastErrorReport(detailedCode = 905, probe = CastProbe.NoAnswer), host))
        assertEquals("no answer from the server", castFailureDetails(CastErrorReport(probe = CastProbe.NoAnswer), ""))
    }

    @Test
    fun whereTheServerAnswersTheReceiversCodeSaysWhatFailed() {
        assertEquals("the TV reported: unsupported media (error 104)", status(200, code = 104))
        assertEquals("the TV reported: unsupported media (error 104)", code(104))
        assertEquals("the TV reported: unsupported media (error 102)", code(102))
        assertEquals("the TV reported: couldn't load the stream from media.example.com (error 311)", code(311))
        assertEquals("the TV reported: couldn't load the stream from media.example.com (error 103)", code(103))
        assertEquals("the TV reported: couldn't read the stream (error 315)", code(315))
        assertEquals("the TV reported: couldn't read the stream (error 316)", code(316))
        assertEquals("the TV reported: couldn't read the stream (error 411)", code(411))
        assertEquals("the TV reported: couldn't load the subtitles (error 600)", code(600))
        assertEquals("the TV reported: playback error (error 905)", code(905))
    }

    @Test
    fun aDeviceWithoutAScreenSaysSo() {
        val report = CastErrorReport(detailedCode = 905, reason = CAST_REASON_VIDEO_DEVICE_REQUIRED, probe = CastProbe.Answered(200))
        assertEquals("the device plays sound only, not video", castFailureDetails(report, host))
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
    fun onlyWhatASecondTryCanFixIsTriedAgain() {
        assertTrue(castMayRetry(CastErrorReport(detailedCode = 315)))
        assertTrue(castMayRetry(CastErrorReport(detailedCode = 905)))
        assertTrue(castMayRetry(CastErrorReport(senderStatus = 15)))
        assertFalse(castMayRetry(CastErrorReport(detailedCode = 104)))
        assertFalse(castMayRetry(CastErrorReport(detailedCode = 102)))
        assertFalse(castMayRetry(CastErrorReport(detailedCode = 905, reason = CAST_REASON_VIDEO_DEVICE_REQUIRED)))
    }
}
