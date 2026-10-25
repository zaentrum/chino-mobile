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

    private fun code(code: Int) = castFailureDetails(CastErrorReport(detailedCode = code), host, device = "Living Room TV")

    @Test
    fun theLineNamesTheDevice() {
        assertEquals("Living Room TV can't play this title.", CastFailure("Living Room TV", "Living Room TV reported: unsupported media").headline)
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
    fun aRefusedLinkIsTheDevicesLinkNotTheViewersSignIn() {
        val line = status(401)
        assertEquals("the server turned down the device's link to the stream (HTTP 401)", line)
        assertFalse(line.contains("sign"), line)
    }

    @Test
    fun noTokenForTheTvIsThePhonesSignInOrTheServer() {
        fun mint(probe: CastProbe) = castFailureDetails(CastErrorReport(mint = probe), host, device = "Living Room TV")
        // POST /me/stream-token refused: this phone's sign-in, not the TV's
        // link (401); the account not let play it (403).
        assertEquals("the server no longer accepts this phone's sign-in (HTTP 401)", mint(CastProbe.Answered(401)))
        assertEquals("the server doesn't let this account play it (HTTP 403)", mint(CastProbe.Answered(403)))
        assertEquals("the server ran into a problem (HTTP 503)", mint(CastProbe.Answered(503)))
        assertEquals("no answer from media.example.com", mint(CastProbe.NoAnswer))
        // A tablet is "this tablet", never "this device" - the TV is that.
        assertEquals(
            "the server no longer accepts this tablet's sign-in (HTTP 401)",
            castFailureDetails(CastErrorReport(mint = CastProbe.Answered(401)), host, device = "Living Room TV", thisDevice = "tablet"),
        )
        // No answer is the server unreachable: a title on the phone plays there.
        assertTrue(CastErrorReport(mint = CastProbe.NoAnswer).serverUnreachable)
        assertTrue(CastErrorReport(detailedCode = 905, probe = CastProbe.NoAnswer).serverUnreachable)
        assertFalse(CastErrorReport(mint = CastProbe.Answered(403)).serverUnreachable)
        // A proxy's answer for a server behind it that is down counts as
        // the server down, the app's one rule (502-504); its own 500 not.
        for (status in 502..504) assertTrue(CastErrorReport(mint = CastProbe.Answered(status)).serverUnreachable, "$status")
        assertTrue(CastErrorReport(probe = CastProbe.Answered(503)).serverUnreachable)
        assertFalse(CastErrorReport(probe = CastProbe.Answered(500)).serverUnreachable)
    }

    @Test
    fun aRequestThatFailedSaysTheServerDidNotAnswerOnlyWithoutAnAnswerOrBehindADownProxy() {
        assertTrue(serverDidNotAnswer(kotlinx.io.IOException("no route")))
        for (status in 502..504) assertTrue(serverDidNotAnswer(cloud.nalet.chino.mobile.data.api.ApiStatusException(status, "/api/v1/me/stream-token")), "$status")
        for (status in listOf(401, 403, 404, 500)) assertFalse(serverDidNotAnswer(cloud.nalet.chino.mobile.data.api.ApiStatusException(status, "/api/v1/me/stream-token")), "$status")
    }

    @Test
    fun thePhonesOwnFailureIsThePhonesNotTheTvs() {
        val line = castFailureDetails(CastErrorReport(onThePhone = true), host, device = "Living Room TV")
        assertEquals("this phone ran into a problem sending the title", line)
        assertFalse(line.contains("Living Room TV reported"), line)
        assertEquals(
            "this tablet ran into a problem sending the title",
            castFailureDetails(CastErrorReport(onThePhone = true), host, device = null, thisDevice = "tablet"),
        )
    }

    @Test
    fun theDeviceGoesByItsNameNeverAsTheTv() {
        fun sender(status: Int, device: String?) = castFailureDetails(CastErrorReport(senderStatus = status), host, device)
        assertEquals("no answer from Kitchen speaker in time", sender(15, "Kitchen speaker"))
        assertEquals("the connection to the device dropped", sender(7, null))
        assertEquals("the device reported an error (status 2100)", sender(2100, null))
        assertEquals("Kitchen speaker reported an error", castFailureDetails(CastErrorReport(), host, "Kitchen speaker"))
        listOf(15, 7, 2016, 2100).forEach { assertFalse(sender(it, null).contains("TV"), sender(it, null)) }
    }

    @Test
    fun noAnswerToThePhoneIsTheConnection() {
        assertEquals("no answer from media.example.com", castFailureDetails(CastErrorReport(detailedCode = 905, probe = CastProbe.NoAnswer), host))
        assertEquals("no answer from the server", castFailureDetails(CastErrorReport(probe = CastProbe.NoAnswer), ""))
    }

    @Test
    fun whereTheServerAnswersTheReceiversCodeSaysWhatFailed() {
        assertEquals("the device reported: unsupported media (error 104)", status(200, code = 104))
        assertEquals("Living Room TV reported: unsupported media (error 104)", code(104))
        assertEquals("Living Room TV reported: unsupported media (error 102)", code(102))
        assertEquals("Living Room TV reported: couldn't load the stream from media.example.com (error 311)", code(311))
        assertEquals("Living Room TV reported: couldn't load the stream from media.example.com (error 103)", code(103))
        assertEquals("Living Room TV reported: couldn't read the stream (error 315)", code(315))
        assertEquals("Living Room TV reported: couldn't read the stream (error 316)", code(316))
        assertEquals("Living Room TV reported: couldn't read the stream (error 411)", code(411))
        assertEquals("Living Room TV reported: couldn't load the subtitles (error 600)", code(600))
        assertEquals("Living Room TV reported: playback error (error 905)", code(905))
    }

    @Test
    fun aDeviceWithoutAScreenSaysSo() {
        val report = CastErrorReport(detailedCode = 905, reason = CAST_REASON_VIDEO_DEVICE_REQUIRED, probe = CastProbe.Answered(200))
        assertEquals("the device plays sound only, not video", castFailureDetails(report, host))
    }

    @Test
    fun whereThePhoneSawTheRequestFail() {
        fun line(report: CastErrorReport) = castFailureDetails(report, host, device = "Living Room TV")
        assertEquals("no answer from Living Room TV in time", line(CastErrorReport(senderStatus = 15)))
        assertEquals("the connection to Living Room TV dropped", line(CastErrorReport(senderStatus = 7)))
        assertEquals("Living Room TV reported an error (status 2100)", line(CastErrorReport(senderStatus = 2100)))
        assertEquals("Living Room TV reported an error", line(CastErrorReport()))
        // A code the receiver named comes before the phone's status.
        assertEquals("Living Room TV reported: unsupported media (error 104)", line(CastErrorReport(detailedCode = 104, senderStatus = 2104)))
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
