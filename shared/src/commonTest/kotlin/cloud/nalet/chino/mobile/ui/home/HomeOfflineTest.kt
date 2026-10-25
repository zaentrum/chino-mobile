package cloud.nalet.chino.mobile.ui.home

import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.api.ContinueWatchingItem
import cloud.nalet.chino.mobile.data.api.FakeServer
import cloud.nalet.chino.mobile.data.api.NO_ACCESS_TITLE
import cloud.nalet.chino.mobile.data.api.NO_ANSWER_SENTENCE
import cloud.nalet.chino.mobile.data.api.respondJson
import cloud.nalet.chino.mobile.data.api.respondText
import cloud.nalet.chino.mobile.data.auth.StreamTokenManager
import cloud.nalet.chino.mobile.data.model.Item
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Home with the server gone: Continue watching never vanishes without a
 * word - the rows the phone knows stay, under the banner, or it says it
 * couldn't load - and a partly watched hero still resumes from what the
 * phone knows.
 */
class HomeOfflineTest {
    private val film = ContinueWatchingItem(id = "m1", title = "Harbour Lights", positionSec = 103, durationSec = 5400, type = "movie")
    private val episode = ContinueWatchingItem(
        id = "e2", title = "The Bridge", positionSec = 0, durationSec = 1500, upNext = true,
        seriesTitle = "Quiet Water", seasonNumber = 1, episodeNumber = 2, type = "episode", parentId = "s1",
    )
    private val movies = listOf(Item(id = "m2", title = "Northern Coast", kind = "movie"))
    private val noAnswer = IOException("refused")

    private fun <T> answered(v: T) = Asked(v)
    private fun <T> failed(e: Exception) = Asked<T>(null, e)

    private val known = KnownRows(feed = listOf(film, episode), recentMovies = movies, recentSeries = emptyList(), topRated = emptyList())

    /** Where every row had its answer, or none did, the server is not asked. */
    private val notAsked: suspend () -> Boolean = { error("the server asked") }

    @Test
    fun theServerAnswersTheRowsAsItGaveThemNoBanner() {
        val view = homeView(answered(listOf(film, episode)), answered(movies), answered(emptyList()), answered(emptyList()), known = null)
        assertEquals(listOf(film), view.continueWatching)
        assertEquals(listOf(episode), view.nextUp)
        assertEquals(movies, view.recentMovies)
        assertFalse(view.offline)
        assertNull(view.continueWatchingFailed)
        assertEquals(KnownRows(listOf(film, episode), movies, emptyList(), emptyList()), view.toKeep)
    }

    @Test
    fun theServerGoneMidSessionTheRowsThePhoneKnowsStayUnderTheBanner() {
        val view = homeView(failed(noAnswer), failed(noAnswer), failed(noAnswer), failed(noAnswer), known)
        assertTrue(view.offline)
        assertEquals(listOf(film), view.continueWatching)
        assertEquals(listOf(episode), view.nextUp)
        assertEquals(movies, view.recentMovies)
        assertNull(view.continueWatchingFailed)
        // Kept as they were, for the next time.
        assertEquals(known, view.toKeep)
    }

    @Test
    fun aPartlyWatchedHeroStillResumesFromWhatThePhoneKnowsNeverABarePlay() {
        val view = homeView(failed(noAnswer), failed(noAnswer), failed(noAnswer), failed(noAnswer), known)
        val hero = heroPlay(Item(id = "m1", title = "Harbour Lights", kind = "movie"), view.continueWatching + view.nextUp, null)
        assertTrue(hero!!.resumes)
        assertEquals("resume 1:43", hero.label)
    }

    @Test
    fun whatThisPhonePlayedSinceIsWhereItResumes() {
        val view = homeView(failed(noAnswer), failed(noAnswer), failed(noAnswer), failed(noAnswer), known, playedHere = mapOf("m1" to 600))
        assertEquals(600, view.continueWatching.single().positionSec)
        assertEquals("resume 10:00", heroPlay(Item(id = "m1", title = "Harbour Lights", kind = "movie"), view.continueWatching, null)!!.label)
        // What is kept is the server's, not the phone's.
        assertEquals(103, view.toKeep.feed!!.first().positionSec)
    }

    @Test
    fun theServerGoneAndNothingKnownContinueWatchingSaysItCouldntLoadTheBannerSaysWhy() {
        val view = homeView(failed(noAnswer), failed(noAnswer), failed(noAnswer), failed(noAnswer), known = null)
        assertTrue(view.offline)
        assertTrue(view.continueWatching.isEmpty())
        // No sentence of its own: the banner says the server can't be reached.
        assertEquals(ShelfFailure(sentence = null, details = "no answer"), view.continueWatchingFailed)
        // Nothing kept that never answered: next time it says so again.
        assertNull(view.toKeep.feed)
    }

    @Test
    fun theServerAnsweringWithAProblemContinueWatchingSaysWhyItself() {
        val view = homeView(failed(ApiStatusException(500, "/api/v1/continue-watching")), answered(movies), answered(emptyList()), answered(emptyList()), known = null)
        assertFalse(view.offline)
        assertEquals(ShelfFailure(sentence = "The server ran into a problem.", details = "HTTP 500"), view.continueWatchingFailed)
        assertEquals(movies, view.recentMovies)
    }

    @Test
    fun anEmptyContinueWatchingTheServerAnsweredIsNoFailure() {
        val view = homeView(answered(emptyList()), answered(movies), answered(emptyList()), answered(emptyList()), known = known)
        assertTrue(view.continueWatching.isEmpty())
        assertNull(view.continueWatchingFailed)
        assertEquals(emptyList(), view.toKeep.feed)
    }

    @Test
    fun theStreamTokenMintedAgainWithTheServerGoneTheBannerAndTheRowsThePhoneKnowsNeverTheErrorPage() = runTest {
        // The token minted at the start of a session, then the server gone
        // and the token past its time: the mint has no answer.
        var clockMs = 1_759_700_000_000L
        var up = true
        val server = FakeServer { request ->
            if (!up) throw noAnswer
            if (request.url.encodedPath.endsWith("/v1/me/stream-token")) respondJson("""{"stream_token":"t-morning"}""")
            else respondText("not found", HttpStatusCode.NotFound)
        }
        val tokens = StreamTokenManager(server.api) { clockMs }
        assertEquals("t-morning", tokens.valid())
        up = false
        clockMs += StreamTokenManager.TOKEN_LIFE_MS - StreamTokenManager.SLACK_MS
        val token = ask { tokens.valid() }
        assertTrue(token.failure is IOException)
        val load = homeLoad(failed(noAnswer), failed(noAnswer), failed(noAnswer), failed(noAnswer), token, known, lastToken = tokens.current.value, reachable = notAsked)
        // The banner, the rows the phone knows, the hero that resumes.
        assertTrue(load.view.offline)
        assertEquals(listOf(film), load.view.continueWatching)
        assertEquals(movies, load.view.recentMovies)
        assertTrue(heroPlay(Item(id = "m1", title = "Harbour Lights", kind = "movie"), load.view.continueWatching, null)!!.resumes)
        // Signed with the token the posters on screen have.
        assertEquals("t-morning", load.streamToken)
        // The mint alone without an answer: the rows as the server gave them.
        val rows = homeLoad(answered(listOf(film)), answered(movies), answered(emptyList()), answered(emptyList()), token, known = null, reachable = notAsked)
        assertFalse(rows.view.offline)
        assertEquals(movies, rows.view.recentMovies)
        assertEquals("", rows.streamToken)
        // A sign-in the server refuses on the mint: Home's own page for it.
        val refused = failed<String>(ApiStatusException(401, "/api/v1/me/stream-token"))
        val thrown = assertFailsWith<ApiStatusException> { homeLoad(failed(noAnswer), failed(noAnswer), failed(noAnswer), failed(noAnswer), refused, known, reachable = notAsked) }
        assertEquals(401, thrown.status)
    }

    @Test
    fun oneSlowRowAmongAnswersIsNoWordOnTheServerItIsAskedNoBannerWhereItAnswers() = runTest {
        val topRated = listOf(Item(id = "m3", title = "Long Shadows", kind = "movie"))
        val known = known.copy(topRated = topRated)
        // The top-rated query alone too slow (a socket timeout is no answer),
        // the other three answered: the server asked, and it answers.
        var asked = 0
        val slow = homeLoad(
            answered(listOf(film)), answered(movies), answered(emptyList()), failed(noAnswer), answered("t"), known,
            reachable = { asked++; true },
        )
        assertEquals(1, asked)
        assertFalse(slow.view.offline)
        // The row as the phone knows it, nothing said.
        assertEquals(topRated, slow.view.topRated)
        assertEquals(listOf(film), slow.view.continueWatching)
        assertNull(slow.view.continueWatchingFailed)
        // Continue watching the one without an answer, and nothing known of
        // it: it says it couldn't load, with its own reason - no banner.
        val feedSlow = homeLoad(failed(noAnswer), answered(movies), answered(emptyList()), answered(emptyList()), answered("t"), null, reachable = { true })
        assertFalse(feedSlow.view.offline)
        assertEquals(ShelfFailure(sentence = "Can't reach the server.", details = "no answer"), feedSlow.view.continueWatchingFailed)
        // The server asked doesn't answer either: offline, the banner.
        val gone = homeLoad(answered(listOf(film)), answered(movies), answered(emptyList()), failed(noAnswer), answered("t"), known, reachable = { false })
        assertTrue(gone.view.offline)
        // A status is an answer: a 500 beside a row without one is the
        // server there, asked too.
        assertEquals(HomeReach.Unsure, homeReach(failed<Int>(ApiStatusException(500, "/api/v1/items")), failed<Int>(noAnswer)))
        assertEquals(HomeReach.NoAnswer, homeReach(failed<Int>(noAnswer), failed<Int>(noAnswer)))
        assertEquals(HomeReach.Answered, homeReach(answered(1), failed<Int>(ApiStatusException(500, "/api/v1/items"))))
        // Every row without an answer: offline at once, the server not asked.
        assertTrue(homeLoad(failed(noAnswer), failed(noAnswer), failed(noAnswer), failed(noAnswer), answered("t"), known, reachable = notAsked).view.offline)
    }

    @Test
    fun aRefusedSignInIsHomesOwnPageNoAccessContinueWatchingsHeadingNeverCantReachTheServerFromOneThatAnswered() = runTest {
        // A 401 on any row, after the app's own renewal: Home's page for a
        // refused sign-in, with its ways on - never a row's couldn't-load.
        val feedRefused = assertFailsWith<ApiStatusException> {
            homeLoad(failed(ApiStatusException(401, "/api/v1/continue-watching")), answered(movies), answered(emptyList()), answered(emptyList()), answered("t"), null, reachable = notAsked)
        }
        assertEquals(401, feedRefused.status)
        val rowRefused = assertFailsWith<ApiStatusException> {
            homeLoad(answered(listOf(film)), answered(movies), answered(emptyList()), failed(ApiStatusException(401, "/api/v1/items")), answered("t"), known, reachable = notAsked)
        }
        assertEquals(401, rowRefused.status)
        // A 403 on Continue watching, nothing known of it: the design
        // system's no-access heading, nothing under it but the status, no
        // "try again" that can't change it.
        val noAccess = homeLoad(failed(ApiStatusException(403, "/api/v1/continue-watching")), answered(movies), answered(emptyList()), answered(emptyList()), answered("t"), null, reachable = notAsked)
        assertFalse(noAccess.view.offline)
        assertEquals(ShelfFailure(sentence = null, details = "HTTP 403", title = NO_ACCESS_TITLE, tryAgain = false), noAccess.view.continueWatchingFailed)
        // The server answered: never "Can't reach the server." under it.
        for (status in listOf(400, 401, 403, 404, 500, 503)) {
            val view = homeView(failed(ApiStatusException(status, "/api/v1/continue-watching")), answered(movies), answered(emptyList()), answered(emptyList()), known = null)
            assertNotEquals(NO_ANSWER_SENTENCE, view.continueWatchingFailed!!.sentence, "$status")
            assertFalse(view.offline, "$status")
        }
    }

    @Test
    fun theWordsAreTheScreensOwn() = runTest {
        assertEquals("couldn't load continue watching", CONTINUE_WATCHING_COULDNT_LOAD)
        assertEquals(CONTINUE_WATCHING_COULDNT_LOAD.lowercase(), CONTINUE_WATCHING_COULDNT_LOAD)
        // An ask that fails is no exception for Home: its reason, kept.
        val asked = ask<Int> { throw noAnswer }
        assertNull(asked.value)
        assertEquals(noAnswer, asked.failure)
        assertEquals(3, ask { 3 }.value)
    }
}
