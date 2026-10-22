package cloud.nalet.chino.mobile.cast

import cloud.nalet.chino.mobile.data.api.ProgressResponse
import cloud.nalet.chino.mobile.ui.player.ResumeInput
import cloud.nalet.chino.mobile.ui.player.resumeStartSec
import kotlin.test.Test
import kotlin.test.assertEquals

/** The hand-over both ways: where the TV starts, and where the phone's player
 *  stands again when the TV does not take over. */
class CastHandoffTest {
    private val auto = CastSubtitleStart.Auto(audioLang = "eng", subtitlePref = "en", audioPref = "orig")

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
        assertEquals(CastSubtitleStart.Sidecar("a1b2"), handoffSubtitle("sidecar:a1b2", auto))
        // Media3's merged source puts the source's place before the id.
        assertEquals(CastSubtitleStart.Sidecar("a1b2"), handoffSubtitle("1:sidecar:a1b2", auto))
        assertEquals(CastSubtitleStart.Off, handoffSubtitle("", auto))
    }

    @Test
    fun aRenditionOrAnUndecidedPlayerLeavesItToTheDefaultRule() {
        // One of the master's own subtitle renditions: the TV has no copy of it.
        assertEquals(auto, handoffSubtitle("0:subs-en", auto))
        assertEquals(auto, handoffSubtitle(null, auto))
        assertEquals(auto, handoffSubtitle("sidecar:", auto))
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

    @Test
    fun aTitleTheTvAlreadyPlaysAboutThereIsNotLoadedAgain() {
        val media = CastMedia(
            itemId = "m1",
            extraId = null,
            contentUrl = "https://media.example.com/api/v1/items/m1/play/master.m3u8?stream=t&caps=avc:1080,aac",
            durationMs = 5_400_000,
            startMs = 1_830_000,
            autoplay = true,
            metadata = CastMetadata(CastMediaKind.Movie, "Sintel", null, null, null, null, emptyList()),
            textTracks = emptyList(),
            activeTextTrackId = null,
            writesProgress = true,
        )
        fun tv(state: RemotePlayerState, positionMs: Long, tag: CastTag? = CastTag("m1", null, "me", true)) =
            RemoteMediaStatus(tag, state, RemoteIdleReason.None, positionMs, 5_400_000)
        // Resume while it plays there, a few seconds on from the last write.
        assertEquals(true, alreadyOnTv(tv(RemotePlayerState.Playing, 1_838_000), media, owner = "me"))
        assertEquals(true, alreadyOnTv(tv(RemotePlayerState.Paused, 1_830_000), media, owner = "me"))
        // Start over, or far from there: loaded anew.
        assertEquals(false, alreadyOnTv(tv(RemotePlayerState.Playing, 1_838_000), media.copy(startMs = 0), owner = "me"))
        // Not this title, not this account's, not playing, nothing at all.
        assertEquals(false, alreadyOnTv(tv(RemotePlayerState.Playing, 1_838_000, CastTag("m2", null, "me", true)), media, owner = "me"))
        assertEquals(false, alreadyOnTv(tv(RemotePlayerState.Playing, 1_838_000, CastTag("m1", "x1", "me", false)), media, owner = "me"))
        assertEquals(false, alreadyOnTv(tv(RemotePlayerState.Playing, 1_838_000), media, owner = "you"))
        assertEquals(false, alreadyOnTv(tv(RemotePlayerState.Idle, 1_838_000), media, owner = "me"))
        assertEquals(false, alreadyOnTv(null, media, owner = "me"))
    }

    @Test
    fun theTitleStandsHereAgainWhereItWasPausedAfterAFailure() {
        val playing = CastHandoff(startMs = 754_000, playing = true, subtitle = auto)
        assertEquals(LocalAgain(startMs = 754_000, autoplay = false), localAgainAfterCast(playing, afterFailure = true))
        // Casting stopped before the TV played: it plays on here as it was.
        assertEquals(LocalAgain(startMs = 754_000, autoplay = true), localAgainAfterCast(playing, afterFailure = false))
        val paused = playing.copy(playing = false)
        assertEquals(LocalAgain(startMs = 754_000, autoplay = false), localAgainAfterCast(paused, afterFailure = false))
    }
}
