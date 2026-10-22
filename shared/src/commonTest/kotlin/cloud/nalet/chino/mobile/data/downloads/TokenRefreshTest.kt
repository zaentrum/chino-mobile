package cloud.nalet.chino.mobile.data.downloads

import cloud.nalet.chino.mobile.data.api.FakeServer
import cloud.nalet.chino.mobile.data.api.respondJson
import cloud.nalet.chino.mobile.data.api.respondText
import cloud.nalet.chino.mobile.data.auth.StreamTokenManager
import cloud.nalet.chino.mobile.data.auth.StreamTokenManager.Companion.TOKEN_LIFE_MS
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A download resumed hours later: chino-stream wrote the token it was asked
 * with into every URI of the playlists the phone keeps, so each request goes
 * out signed with the token valid now - minted again once the old one runs
 * out - while each piece keeps one key, whatever token fetched it.
 */
class TokenRefreshTest {
    private val master = "https://media.example.com/api/v1/items/ID/play/master.m3u8?caps=avc,hvc"

    @Test
    fun aPlaylistsUriResolvesBesideTheMasterWithItsOwnQuery() {
        assertEquals(
            "https://media.example.com/api/v1/items/ID/play/v1/playlist.m3u8?stream=OLD&caps=avc,hvc&v=7",
            resolveUrl(master, "v1/playlist.m3u8?stream=OLD&caps=avc,hvc&v=7"),
        )
        assertEquals(
            "https://media.example.com/api/v1/items/ID/play/v1/seg-00001.m4s?stream=OLD",
            resolveUrl("https://media.example.com/api/v1/items/ID/play/v1/playlist.m3u8?stream=OLD", "seg-00001.m4s?stream=OLD"),
        )
        assertEquals("https://media.example.com/root.m3u8", resolveUrl(master, "/root.m3u8"))
        assertEquals("https://media.example.com/api/v1/items/ID/x.m3u8", resolveUrl(master, "../x.m3u8"))
        assertEquals("https://cdn.example.org/a.m3u8", resolveUrl(master, "https://cdn.example.org/a.m3u8"))
    }

    @Test
    fun aPieceIsKeptUnderItsUrlWithoutTheToken() {
        val signedThen = "https://media.example.com/api/v1/items/ID/play/v1/seg-00001.m4s?stream=OLD&caps=avc,hvc&v=7"
        val signedNow = "https://media.example.com/api/v1/items/ID/play/v1/seg-00001.m4s?stream=NEW&caps=avc,hvc&v=7"
        assertEquals("https://media.example.com/api/v1/items/ID/play/v1/seg-00001.m4s?caps=avc,hvc&v=7", pieceKey(signedThen))
        assertTrue(samePiece(signedThen, signedNow))
        // Another package version is another piece.
        assertTrue(!samePiece(signedThen, signedThen.replace("v=7", "v=8")))
    }

    @Test
    fun eachRequestGoesOutWithTheTokenValidNowMintedAgainOnceTheOldOneRunsOut() = runTest {
        var clock = 1_760_000_000_000L
        var mints = 0
        val server = FakeServer { request ->
            if (request.url.encodedPath.endsWith("/v1/me/stream-token")) {
                mints++
                respondJson("""{"stream_token":"t$mints"}""")
            } else {
                respondText("not found", HttpStatusCode.NotFound)
            }
        }
        val tokens = StreamTokenManager(server.api) { clock }
        val written = "https://media.example.com/api/v1/items/ID/play/v1/seg-00042.m4s?stream=t0&caps=avc,hvc&v=7"
        // Started: signed with the token minted now, not the one in the
        // playlist.
        assertEquals(written.replace("stream=t0", "stream=t1"), signedNow(written, tokens.valid()))
        // Resumed seven hours later: the old token has run out, a new one
        // is minted for the next request.
        clock += TOKEN_LIFE_MS + 60 * 60 * 1000
        assertEquals(written.replace("stream=t0", "stream=t2"), signedNow(written, tokens.valid()))
        assertEquals(2, mints)
        // Refused (the server's key changed): forgotten, minted again.
        tokens.invalidate()
        assertEquals(written.replace("stream=t0", "stream=t3"), signedNow(written, tokens.valid()))
        // The master, asked for without a token, gets one.
        assertEquals("$master&stream=t3", signedNow(master, tokens.valid()))
    }

    @Test
    fun withoutATokenToSignWithTheUrlGoesAsItIs() {
        val url = "https://media.example.com/api/v1/items/ID/play/v1/seg-00001.m4s?stream=t0"
        assertEquals(url, signedNow(url, null))
        assertEquals(url, signedNow(url, ""))
    }
}
