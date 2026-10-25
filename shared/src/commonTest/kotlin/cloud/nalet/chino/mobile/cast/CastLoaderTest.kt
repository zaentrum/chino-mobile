package cloud.nalet.chino.mobile.cast

import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.auth.withStreamToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A title loaded on the TV start to end, and a load or a title that fails:
 * the loader against a fake TV - the Cast SDK's RemoteMediaClient as the
 * loader drives it - and a fake server answering the phone's own fetches of
 * the TV's master.
 */
class CastLoaderTest {
    private class FakeTv : CastTv {
        override var device: String? = "Living Room TV"
        var status: RemoteMediaStatus? = null

        /** Meant to play or wait, as the TV's last Playing or Paused said;
         *  null: as its status says. */
        var intent: Boolean? = null
        var content: String? = null
        var tvTracks: List<TvTrack> = emptyList()
        var active: List<Long> = emptyList()
        val loads = mutableListOf<CastMedia>()
        val reloads = mutableListOf<CastReload>()
        val trackSets = mutableListOf<List<Long>>()
        var plays = 0

        /** The TV's answers, one per load or reload; Playing when none is left. */
        val answers = ArrayDeque<CastAnswer>()

        /** The next load waits for an answer until the loader lets it go. */
        var holdNext = false
        var hold: CompletableDeferred<CastAnswer>? = null

        /** What the TV lists once it plays a load. */
        var loaded: (FakeTv) -> Unit = {}

        override fun status(): RemoteMediaStatus? = status
        override fun playIntent(): Boolean? = intent ?: when (status?.state) {
            RemotePlayerState.Playing -> true
            RemotePlayerState.Paused -> false
            else -> null
        }
        override fun contentUrl(): String? = content
        override fun tracks(): List<TvTrack> = tvTracks
        override fun activeTrackIds(): List<Long> = active

        override suspend fun load(media: CastMedia, owner: String): CastAnswer {
            loads += media
            content = media.contentUrl
            val held = if (holdNext) CompletableDeferred<CastAnswer>().also { hold = it; holdNext = false } else null
            val answer = held?.await() ?: answers.removeFirstOrNull() ?: CastAnswer.Playing
            if (answer == CastAnswer.Playing) {
                status = RemoteMediaStatus(media.tag(owner), RemotePlayerState.Playing, RemoteIdleReason.None, media.startMs, media.durationMs ?: 0)
                loaded(this)
            }
            return answer
        }

        override suspend fun reload(again: CastReload): CastAnswer {
            reloads += again
            content = content?.let { withStreamToken(it, again.token.token) }
            return answers.removeFirstOrNull() ?: CastAnswer.Playing
        }

        override suspend fun setActiveTracks(ids: List<Long>): Boolean {
            trackSets += ids
            active = ids
            return true
        }

        override fun play() {
            plays++
        }
    }

    private val tv = FakeTv()
    private val fetched = mutableListOf<String>()
    private var server: (String) -> MasterFetch = { MasterFetch.Answered(200, MASTER) }
    private var minted = 0

    /** What the server answers the phone's request for a token instead of one. */
    private var mintFails: Exception? = null
    private var writer: CastWriter? = CastWriter("account-a", "owner-a")
    private val clock = 1_800_000_000_000
    private var loadIds = 0
    private val loading = mutableListOf<Triple<String, String, Long>>()
    private val floors = mutableListOf<Long?>()

    private val loader = CastLoader(
        writer = { writer },
        token = { fresh ->
            mintFails?.let { throw it }
            if (fresh || minted == 0) minted++
            CastToken("t$minted", clock + LIFE_MS)
        },
        fetchMaster = { url ->
            fetched += url
            server(url)
        },
        now = { clock },
        newLoad = { "L${++loadIds}" },
        serverHost = { "media.example.com" },
        loading = { load, itemId, startMs, floorMs ->
            loading += Triple(load, itemId, startMs)
            floors += floorMs
        },
        superseded = { tv.hold?.complete(CastAnswer.Ended) },
    )

    private fun input(
        autoplay: Boolean = true,
        restart: Boolean = false,
        audioLang: String? = null,
        subtitle: CastSubtitleStart = CastSubtitleStart.Off,
    ) = CastMediaInput(
        apiBase = "https://media.example.com/api",
        itemId = "m1",
        phoneMasterUrl = "https://media.example.com/api/v1/items/m1/play/master.m3u8?stream=phone&caps=avc:1080,hvc:2160,aac&q=high",
        item = null,
        seriesTitle = null,
        durationMs = 5_400_000,
        startMs = 1_830_000,
        autoplay = autoplay,
        sidecars = emptyList(),
        audioLang = audioLang,
        audioPref = "orig",
        subtitleStart = subtitle,
        writesProgress = true,
        restart = restart,
    )

    private fun ours(state: RemotePlayerState, positionMs: Long, linksExpireAtMs: Long = clock + LIFE_MS, owner: String = "owner-a") =
        RemoteMediaStatus(CastTag("m1", null, owner, true, "L0", linksExpireAtMs), state, RemoteIdleReason.None, positionMs, 5_400_000)

    private fun failedWith(code: Int) = CastAnswer.Failed(CastErrorReport(detailedCode = code, senderStatus = 2100))

    @Test
    fun aTitleIsLoadedWithItsLinksSignedForTheTvOnceItsMasterIsRead() = runTest {
        assertEquals(CastLoadResult.Playing, loader.load(tv, input()))
        val tvMaster = "https://media.example.com/api/v1/items/m1/play/master.m3u8?stream=t1&caps=avc:1080,aac"
        assertEquals(listOf(tvMaster), fetched)
        val media = tv.loads.single()
        assertEquals(tvMaster, media.contentUrl)
        assertEquals("L1", media.load)
        assertEquals(clock + LIFE_MS, media.linksExpireAtMs)
        // The start guard of the position writes heard of it first.
        assertEquals(listOf(Triple("L1", "m1", 1_830_000L)), loading)
    }

    @Test
    fun theTitleTheTvPlaysForThisPhonePlaysOn() = runTest {
        tv.status = ours(RemotePlayerState.Paused, 3_600_000)
        assertEquals(CastLoadResult.Playing, loader.load(tv, input(autoplay = true)))
        assertTrue(tv.loads.isEmpty())
        assertTrue(fetched.isEmpty())
        assertEquals(1, tv.plays)
        // Playing already: left as it is.
        tv.status = ours(RemotePlayerState.Playing, 3_600_000)
        loader.load(tv, input(autoplay = true))
        assertEquals(1, tv.plays)
    }

    @Test
    fun playFromStartOrAnotherPhonesTitleIsLoadedAnew() = runTest {
        tv.status = ours(RemotePlayerState.Playing, 3_600_000)
        loader.load(tv, input(restart = true))
        assertEquals(1, tv.loads.size)
        tv.status = ours(RemotePlayerState.Playing, 3_600_000, owner = "owner-b")
        loader.load(tv, input())
        assertEquals(2, tv.loads.size)
    }

    @Test
    fun withoutAnAccountNothingIsLoaded() = runTest {
        writer = null
        assertEquals(CastLoadResult.Ended, loader.load(tv, input()))
        assertTrue(tv.loads.isEmpty())
    }

    @Test
    fun aMasterTheServerRefusesIsTheReasonAndTheTvIsNotAsked() = runTest {
        server = { MasterFetch.Answered(404, "") }
        val result = assertIs<CastLoadResult.Failed>(loader.load(tv, input()))
        assertEquals("Living Room TV can't play this title.", result.failure.headline)
        assertEquals("the server has no stream of this title (HTTP 404)", result.failure.details)
        assertEquals("m1", result.failure.itemId)
        assertTrue(tv.loads.isEmpty())
        // The TV was not asked: what it plays plays on.
        assertEquals(false, result.endsCasting)
    }

    @Test
    fun noAnswerToThePhonesOwnFetchIsTheServerDownAndTheTvIsNotAsked() = runTest {
        // The token for the TV was fresh enough not to ask the server for
        // one; the server gives the phone no answer for the master.
        server = { MasterFetch.NoAnswer }
        val result = assertIs<CastLoadResult.Failed>(loader.load(tv, input()))
        assertTrue(tv.loads.isEmpty())
        assertEquals(false, result.endsCasting)
        assertEquals("no answer from media.example.com", result.failure.details)
        // A title on the phone plays there.
        assertTrue(result.failure.serverUnreachable)
    }

    @Test
    fun aProxyThatSaysTheServerIsDownIsTheServerUnreachable() = runTest {
        // The server behind a proxy is down: the token's request answers 502.
        mintFails = ApiStatusException(502, "/api/v1/me/stream-token")
        val minting = assertIs<CastLoadResult.Failed>(loader.load(tv, input()))
        assertTrue(minting.failure.serverUnreachable)
        assertEquals(false, minting.endsCasting)
        // ...or the master's fetch answers 504.
        mintFails = null
        server = { MasterFetch.Answered(504, "") }
        val fetching = assertIs<CastLoadResult.Failed>(loader.load(tv, input()))
        assertTrue(fetching.failure.serverUnreachable)
        assertTrue(tv.loads.isEmpty())
    }

    @Test
    fun noTokenForTheTvsLinksIsAReasonOfItsOwnAndTheTvPlaysOn() = runTest {
        // POST /me/stream-token refused: the phone's own sign-in, not the TV's link.
        mintFails = ApiStatusException(401, "/api/v1/me/stream-token")
        val refused = assertIs<CastLoadResult.Failed>(loader.load(tv, input()))
        assertEquals("the server no longer accepts this phone's sign-in (HTTP 401)", refused.failure.details)
        assertEquals(false, refused.endsCasting)
        mintFails = IllegalStateException("unreachable")
        val unanswered = assertIs<CastLoadResult.Failed>(loader.load(tv, input()))
        assertEquals("no answer from media.example.com", unanswered.failure.details)
        assertTrue(tv.loads.isEmpty())
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun aTokenTheServerTurnsDownIsMintedAnewBeforeTheTvIsAsked() = runTest {
        server = { url -> if ("stream=t1" in url) MasterFetch.Answered(401, "") else MasterFetch.Answered(200, MASTER) }
        assertEquals(CastLoadResult.Playing, loader.load(tv, input()))
        assertEquals(2, minted)
        assertTrue("stream=t2" in tv.loads.single().contentUrl)
    }

    @Test
    fun aLoadThatFailsIsTriedOnceMoreAsANewLoad() = runTest {
        tv.answers += failedWith(905)
        assertEquals(CastLoadResult.Playing, loader.load(tv, input()))
        assertEquals(listOf("L1", "L2"), tv.loads.map { it.load })
        assertEquals(listOf("L1", "L2"), loading.map { it.first })
    }

    @Test
    fun aLoadThatFailsTwiceSaysWhatTheTvReported() = runTest {
        tv.answers += failedWith(905)
        tv.answers += failedWith(905)
        val result = assertIs<CastLoadResult.Failed>(loader.load(tv, input()))
        assertEquals("Living Room TV reported: playback error (error 905)", result.failure.details)
        assertEquals(2, tv.loads.size)
        // The TV failed at it: casting stops.
        assertEquals(true, result.endsCasting)
    }

    @Test
    fun mediaTheTvCannotDecodeIsNotTriedAgain() = runTest {
        tv.answers += failedWith(104)
        val result = assertIs<CastLoadResult.Failed>(loader.load(tv, input()))
        assertEquals("Living Room TV reported: unsupported media (error 104)", result.failure.details)
        assertEquals(1, tv.loads.size)
    }

    @Test
    fun theTvsLinkTurnedDownAfterItFailedGetsANewToken() = runTest {
        tv.answers += failedWith(905)
        // The master answered as the phone read it first; the TV's link is
        // turned down once the TV failed.
        server = { url -> if (fetched.size > 1 && "stream=t1" in url) MasterFetch.Answered(401, "") else MasterFetch.Answered(200, MASTER) }
        assertEquals(CastLoadResult.Playing, loader.load(tv, input()))
        assertTrue("stream=t2" in tv.loads.last().contentUrl, tv.loads.last().contentUrl)
    }

    @Test
    fun noAnswerToThePhoneAfterTheTvFailedIsTheConnection() = runTest {
        tv.answers += failedWith(905)
        server = { if (fetched.size > 1) MasterFetch.NoAnswer else MasterFetch.Answered(200, MASTER) }
        val result = assertIs<CastLoadResult.Failed>(loader.load(tv, input()))
        assertEquals("no answer from media.example.com", result.failure.details)
        assertEquals(1, tv.loads.size)
    }

    @Test
    fun theAudioAndSubtitleComeOnOnceTheTvHasLoaded() = runTest {
        tv.loaded = { t ->
            t.tvTracks = listOf(
                TvTrack(1, TvTrackType.Video, null, null),
                TvTrack(2, TvTrackType.Audio, "en", "English"),
                TvTrack(3, TvTrackType.Audio, "ja", "Japanese"),
                TvTrack(4, TvTrackType.Text, "en", "English"),
            )
            t.active = listOf(1, 2)
        }
        loader.load(tv, input(audioLang = "jpn", subtitle = CastSubtitleStart.Auto(subtitlePref = "en")))
        assertEquals(listOf(listOf(1L, 3L, 4L)), tv.trackSets)
    }

    @Test
    fun aNewerLoadTakesThePlaceOfOneStillWaiting() = runTest {
        tv.holdNext = true
        val first = async { loader.load(tv, input()) }
        testScheduler.advanceUntilIdle()
        // The first waits for the TV's answer; the viewer asks for another.
        val second = async { loader.load(tv, input(restart = true)) }
        assertEquals(CastLoadResult.Ended, first.await())
        assertEquals(CastLoadResult.Playing, second.await())
        assertEquals(listOf("L1", "L2"), tv.loads.map { it.load })
    }

    @Test
    fun aTitleThatFailedAsItPlayedIsLoadedOnceMoreWhereItWas() = runTest {
        tv.content = "https://media.example.com/api/v1/items/m1/play/master.m3u8?stream=t0&caps=avc:1080,aac"
        val at = CastStoppedAt("m1", atMs = 1_234_000, playing = true, activeTrackIds = listOf(1, 2))
        assertNull(loader.recover(tv, at, CastErrorReport(detailedCode = 315)))
        val again = tv.reloads.single()
        assertEquals(CastReload(CastToken("t1", clock + LIFE_MS), "L1", 1_234_000, autoplay = true, activeTrackIds = listOf(1, 2)), again)
        assertEquals(listOf(Triple("L1", "m1", 1_234_000L)), loading)
        // Its start guard, and its floor: nothing short of where it stood.
        assertEquals(listOf<Long?>(1_234_000L), floors)
    }

    @Test
    fun aTitleWhoseLinkTheServerTurnedDownGetsANewToken() = runTest {
        tv.content = "https://media.example.com/api/v1/items/m1/play/master.m3u8?stream=t0&caps=avc:1080,aac"
        server = { url -> if ("stream=t0" in url) MasterFetch.Answered(401, "") else MasterFetch.Answered(200, MASTER) }
        minted = 1
        assertNull(loader.recover(tv, CastStoppedAt("m1", 1_234_000, playing = false, activeTrackIds = emptyList()), CastErrorReport(detailedCode = 905)))
        assertEquals("t2", tv.reloads.single().token.token)
        assertEquals(false, tv.reloads.single().autoplay)
    }

    @Test
    fun aTitleTheServerNoLongerHasFailsWithoutAnotherTry() = runTest {
        tv.content = "https://media.example.com/api/v1/items/m1/play/master.m3u8?stream=t0&caps=avc:1080,aac"
        server = { MasterFetch.Answered(404, "") }
        val failure = loader.recover(tv, CastStoppedAt("m1", 1_234_000, playing = true, activeTrackIds = emptyList()), CastErrorReport(detailedCode = 905))
        assertEquals("the server has no stream of this title (HTTP 404)", failure?.details)
        assertTrue(tv.reloads.isEmpty())
    }

    @Test
    fun aTitleThatPlaysAgainWithLinksRunningOutIsSignedAnew() = runTest {
        // Paused for hours: an hour of the links left, 80 minutes to play.
        tv.status = ours(RemotePlayerState.Playing, 600_000, linksExpireAtMs = clock + 3_600_000)
        tv.active = listOf(1, 2, 1000)
        assertEquals(CastRenewal.Renewed, loader.renewLinks(tv))
        assertEquals(CastReload(CastToken("t1", clock + LIFE_MS), "L1", 600_000, autoplay = true, activeTrackIds = listOf(1, 2, 1000)), tv.reloads.single())
        assertEquals(listOf<Long?>(600_000L), floors)
    }

    @Test
    fun aPausedTitleIsSignedAnewPausedAndOneWithLinksToSpareIsLeftAlone() = runTest {
        tv.status = ours(RemotePlayerState.Paused, 600_000, linksExpireAtMs = clock + 3_600_000)
        assertEquals(CastRenewal.Renewed, loader.renewLinks(tv))
        assertEquals(false, tv.reloads.single().autoplay)
        tv.status = ours(RemotePlayerState.Playing, 600_000, linksExpireAtMs = clock + LIFE_MS)
        assertEquals(CastRenewal.NotNeeded, loader.renewLinks(tv))
        // Another phone's title: not this phone's to sign.
        tv.status = ours(RemotePlayerState.Playing, 600_000, linksExpireAtMs = clock + 60_000, owner = "owner-b")
        assertEquals(CastRenewal.NotNeeded, loader.renewLinks(tv))
        assertEquals(1, tv.reloads.size)
    }

    @Test
    fun aLongTitleIsNotReloadedForLinksNoTokenMakesLast() = runTest {
        // A file of several episodes, 5 h 40 min: fresh links "run out" all
        // the same, as a token lives at most 6 h - 5 min; a new one would end
        // no later.
        val duration = (5 * 60 + 40) * 60_000L
        tv.status = RemoteMediaStatus(CastTag("m1", null, "owner-a", true, "L0", clock + LIFE_MS), RemotePlayerState.Playing, RemoteIdleReason.None, 0, duration)
        assertTrue(linksRunOut(clock + LIFE_MS, clock, 0, duration))
        assertEquals(CastRenewal.NotNeeded, loader.renewLinks(tv))
        assertEquals(CastRenewal.NotNeeded, loader.renewLinks(tv))
        assertTrue(tv.reloads.isEmpty())
    }

    @Test
    fun aPausedTitleThatIsSoughtIsReloadedPaused() = runTest {
        // Paused for hours; the viewer seeks while paused: the TV reports
        // Buffering, and is still meant to wait.
        tv.status = ours(RemotePlayerState.Buffering, 600_000, linksExpireAtMs = clock + 3_600_000)
        tv.intent = false
        assertEquals(CastRenewal.Renewed, loader.renewLinks(tv))
        assertEquals(false, tv.reloads.single().autoplay)
    }

    @Test
    fun resumingAPausedTitleWhoseLinksRunOutReloadsItPlayingAndSendsNoPlay() = runTest {
        // Paused for hours on the TV; the viewer taps Resume on the phone.
        tv.status = ours(RemotePlayerState.Paused, 600_000, linksExpireAtMs = clock + 3_600_000)
        assertEquals(CastLoadResult.Playing, loader.load(tv, input(autoplay = true)))
        assertEquals(true, tv.reloads.single().autoplay)
        assertEquals(0, tv.plays)
        assertTrue(tv.loads.isEmpty())
    }

    @Test
    fun aRenewalThatFailsIsTriedOnceMoreAsARecoveryIsAndElseSaysWhy() = runTest {
        tv.content = "https://media.example.com/api/v1/items/m1/play/master.m3u8?stream=t0&caps=avc:1080,aac"
        tv.status = ours(RemotePlayerState.Playing, 600_000, linksExpireAtMs = clock + 3_600_000)
        tv.answers += failedWith(905)
        assertEquals(CastRenewal.Renewed, loader.renewLinks(tv))
        assertEquals(2, tv.reloads.size)

        tv.reloads.clear()
        tv.status = ours(RemotePlayerState.Playing, 600_000, linksExpireAtMs = clock + 3_600_000)
        tv.answers += failedWith(905)
        tv.answers += failedWith(905)
        val failed = assertIs<CastRenewal.Failed>(loader.renewLinks(tv))
        assertEquals("Living Room TV reported: playback error (error 905)", failed.failure.details)
        assertEquals(2, tv.reloads.size)
    }

    private companion object {
        /** A token's life as the TV's links are told it. */
        const val LIFE_MS = 6L * 60 * 60 * 1000 - 5L * 60 * 1000

        val MASTER = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="a0/index.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="a"
            v0/index.m3u8
        """.trimIndent()
    }
}
