package cloud.nalet.chino.mobile.ui.home

import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.api.ContinueWatchingItem
import cloud.nalet.chino.mobile.data.api.FakeServer
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
        val load = homeLoad(failed(noAnswer), failed(noAnswer), failed(noAnswer), failed(noAnswer), token, known, lastToken = tokens.current.value)
        // The banner, the rows the phone knows, the hero that resumes.
        assertTrue(load.view.offline)
        assertEquals(listOf(film), load.view.continueWatching)
        assertEquals(movies, load.view.recentMovies)
        assertTrue(heroPlay(Item(id = "m1", title = "Harbour Lights", kind = "movie"), load.view.continueWatching, null)!!.resumes)
        // Signed with the token the posters on screen have.
        assertEquals("t-morning", load.streamToken)
        // The mint alone without an answer: the rows as the server gave them.
        val rows = homeLoad(answered(listOf(film)), answered(movies), answered(emptyList()), answered(emptyList()), token, known = null)
        assertFalse(rows.view.offline)
        assertEquals(movies, rows.view.recentMovies)
        assertEquals("", rows.streamToken)
        // A sign-in the server refuses on the mint: Home's own page for it.
        val refused = failed<String>(ApiStatusException(401, "/api/v1/me/stream-token"))
        val thrown = assertFailsWith<ApiStatusException> { homeLoad(failed(noAnswer), failed(noAnswer), failed(noAnswer), failed(noAnswer), refused, known) }
        assertEquals(401, thrown.status)
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
