package cloud.nalet.chino.mobile.cast

import cloud.nalet.chino.mobile.data.api.ProgressResponse
import cloud.nalet.chino.mobile.data.api.SidecarSubtitle
import cloud.nalet.chino.mobile.ui.player.ResumeInput
import cloud.nalet.chino.mobile.ui.player.SESSION_MARGIN_MS
import cloud.nalet.chino.mobile.ui.player.resumeStartSec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The hand-over both ways: where the TV starts, whether it plays on, and
 *  where the phone's player stands again when the TV does not take over. */
class CastHandoffTest {
    private val auto = CastSubtitleStart.Auto(subtitlePref = "en", audioPref = "orig")
    private val sidecars = listOf(
        SidecarSubtitle(id = "a1b2", lang = "ger", url = "https://media.example.com/api/v1/play/subs/a1b2.vtt"),
        SidecarSubtitle(id = "f9", lang = "eng", label = "English (forced)", url = "https://media.example.com/api/v1/play/subs/f9.vtt"),
    )

    @Test
    fun theTvContinuesWhereThePhoneWas() {
        assertEquals(754_000, handoffStartMs(positionMs = 754_000, durationMs = 5_400_000, ended = false))
        // Without a known length, the position as it is.
        assertEquals(754_000, handoffStartMs(positionMs = 754_000, durationMs = 0, ended = false))
        assertEquals(0, handoffStartMs(positionMs = -40, durationMs = 5_400_000, ended = false))
    }

    @Test
    fun neverPastTheLastSecondAndFromTheHeadOncePlayedToTheEnd() {
        assertEquals(5_399_000, handoffStartMs(positionMs = 5_400_000, durationMs = 5_400_000, ended = false))
        assertEquals(0, handoffStartMs(positionMs = 5_400_000, durationMs = 5_400_000, ended = true))
    }

    @Test
    fun thePhonesSidecarIsTheTvsOffStaysOff() {
        assertEquals(CastSubtitleStart.Sidecar("a1b2", "ger", forced = false), handoffSubtitle("sidecar:a1b2", sidecars, auto))
        // Media3's merged source puts the source's place before the id.
        assertEquals(CastSubtitleStart.Sidecar("a1b2", "ger", forced = false), handoffSubtitle("1:sidecar:a1b2", sidecars, auto))
        // Forced as its label says, which finds the TV's rendition of it.
        assertEquals(CastSubtitleStart.Sidecar("f9", "eng", forced = true), handoffSubtitle("sidecar:f9", sidecars, auto))
        assertEquals(CastSubtitleStart.Off, handoffSubtitle("", sidecars, auto))
    }

    @Test
    fun aRenditionAnUnknownSidecarOrAnUndecidedPlayerLeavesItToTheDefaultRule() {
        // One of the master's own subtitle renditions: the TV has no copy of it.
        assertEquals(auto, handoffSubtitle("0:subs-en", sidecars, auto))
        assertEquals(auto, handoffSubtitle(null, sidecars, auto))
        assertEquals(auto, handoffSubtitle("sidecar:", sidecars, auto))
        assertEquals(auto, handoffSubtitle("sidecar:gone", sidecars, auto))
    }

    @Test
    fun aFinishedTitleHasNoResumePoint() {
        assertEquals(1834, resumeSecOf(ProgressResponse(positionSec = 1834)))
        assertEquals(0, resumeSecOf(ProgressResponse(positionSec = 0, finished = true)))
        // Should a server send a position with it, finished still wins.
        assertEquals(0, resumeSecOf(ProgressResponse(positionSec = 5300, finished = true)))
    }

    @Test
    fun castFromATitlesPageStartsWhereThePlayerWouldHave() {
        // The player's rule on what GET progress gives, as it casts from Play.
        fun start(p: ProgressResponse, startOver: Boolean = false) =
            resumeStartSec(ResumeInput(savedSec = resumeSecOf(p), durationSec = 5400.0, startOver = startOver))
        assertEquals(1834, start(ProgressResponse(positionSec = 1834)))
        assertEquals(0, start(ProgressResponse(positionSec = 0, finished = true)))
        assertEquals(0, start(ProgressResponse(positionSec = 25)))
        assertEquals(0, start(ProgressResponse(positionSec = 1834), startOver = true))
    }

    private fun tv(state: RemotePlayerState, positionMs: Long, tag: CastTag? = tagOf("m1")) =
        RemoteMediaStatus(tag, state, RemoteIdleReason.None, positionMs, 5_400_000)

    private fun tagOf(itemId: String, extraId: String? = null, owner: String = "owner-a") =
        CastTag(itemId, extraId, owner, writesProgress = extraId == null, load = "L1", linksExpireAtMs = 0L)

    @Test
    fun aTitleTheTvPlaysForThisPhonePlaysOnWhereverTheServerHadIt() {
        // Resume on its page while it plays there - the server's position
        // far behind the TV's, writes missed or not: the TV's position wins.
        assertTrue(playsOnTv(tv(RemotePlayerState.Playing, 3_600_000), "m1", null, "owner-a"))
        assertTrue(playsOnTv(tv(RemotePlayerState.Paused, 1_830_000), "m1", null, "owner-a"))
        assertTrue(playsOnTv(tv(RemotePlayerState.Buffering, 10_000), "m1", null, "owner-a"))
    }

    @Test
    fun anotherTitleAnotherPhonesOrNothingPlayingIsLoadedAnew() {
        assertFalse(playsOnTv(tv(RemotePlayerState.Playing, 1_838_000, tagOf("m2")), "m1", null, "owner-a"))
        assertFalse(playsOnTv(tv(RemotePlayerState.Playing, 1_838_000, tagOf("m1", extraId = "x1")), "m1", null, "owner-a"))
        assertFalse(playsOnTv(tv(RemotePlayerState.Playing, 1_838_000), "m1", null, "owner-b"))
        assertFalse(playsOnTv(tv(RemotePlayerState.Idle, 1_838_000), "m1", null, "owner-a"))
        assertFalse(playsOnTv(tv(RemotePlayerState.Playing, 1_838_000, tag = null), "m1", null, "owner-a"))
        assertFalse(playsOnTv(null, "m1", null, "owner-a"))
    }

    @Test
    fun theLinksAreSignedAnewWhenTheyStopWorkingBeforeTheTitleEnds() {
        val now = 1_800_000_000_000
        val hour = 3_600_000L
        // A 90-minute film at 10 minutes, links good for 5 h: as they are.
        assertFalse(linksRunOut(now + 5 * hour, now, positionMs = 600_000, durationMs = 5_400_000))
        // A 40-minute pause four hours in, 80 minutes to play, 1 h 20 left
        // of the links: they would not last the rest and the margin.
        assertTrue(linksRunOut(now + 80 * 60_000L, now, positionMs = 600_000, durationMs = 5_400_000))
        // Exactly what is left to play and the margin: they last.
        assertFalse(linksRunOut(now + 4_800_000 + SESSION_MARGIN_MS, now, positionMs = 600_000, durationMs = 5_400_000))
        // Without a length: only the margin counts.
        assertTrue(linksRunOut(now + 10 * 60_000L, now, positionMs = 600_000, durationMs = 0))
        // Media cast before the phone marked it: left alone.
        assertFalse(linksRunOut(0L, now, positionMs = 600_000, durationMs = 5_400_000))
    }

    @Test
    fun thePhoneAlwaysComesBackPaused() {
        // The TV couldn't, or the viewer stopped casting before it played -
        // or another phone took the TV: the title stands here where it was,
        // and does not play by itself, with sound.
        val playing = CastHandoff(startMs = 754_000, playing = true, audioLang = "eng", subtitle = auto, restart = true)
        assertEquals(LocalAgain(startMs = 754_000, autoplay = false), localAgainAfterCast(playing))
        assertEquals(LocalAgain(startMs = 754_000, autoplay = false), localAgainAfterCast(playing.copy(playing = false)))
    }
}
