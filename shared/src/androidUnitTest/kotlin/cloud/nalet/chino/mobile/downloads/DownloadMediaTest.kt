package cloud.nalet.chino.mobile.downloads

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import cloud.nalet.chino.mobile.data.downloads.DownloadedTitle
import cloud.nalet.chino.mobile.data.downloads.Owner
import cloud.nalet.chino.mobile.data.downloads.OwnerClaim
import cloud.nalet.chino.mobile.data.downloads.pieceKey
import cloud.nalet.chino.mobile.data.model.Item
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A download through the app's own stack - its signing, its playlists kept
 * without tokens, its cache keys - against a server stood up in the test
 * that, as chino-stream does, writes the token it was asked with into every
 * URI of its playlists and takes only the token valid now. Then played with
 * no server at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadMediaTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var base: String
    private lateinit var cache: SimpleCache
    private lateinit var database: StandaloneDatabaseProvider

    /** The tokens the server takes now, and the one the app mints now. */
    @Volatile private var accepted = setOf(TOKEN_ONE)
    @Volatile private var token = TOKEN_ONE
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    private val keys = CacheKeyFactory { spec -> spec.key ?: pieceKey(spec.uri.toString()) }

    @Before
    fun setUp() {
        dir = File(RuntimeEnvironment.getApplication().cacheDir, "download-test-${System.nanoTime()}").apply { mkdirs() }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                val asked = url.queryParameter("stream")
                if (asked !in accepted) return MockResponse().setResponseCode(401)
                val path = url.encodedPath
                return when {
                    path.endsWith("/master.m3u8") -> MockResponse().setBody(
                        "#EXTM3U\n#EXT-X-VERSION:6\n" +
                            "#EXT-X-STREAM-INF:BANDWIDTH=800000,AVERAGE-BANDWIDTH=600000,RESOLUTION=640x360,CODECS=\"avc1.4d401e\"\n" +
                            "v0/playlist.m3u8?stream=$asked&v=1\n",
                    )
                    path.endsWith("/v0/playlist.m3u8") -> MockResponse().setBody(
                        "#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:6\n#EXT-X-PLAYLIST-TYPE:VOD\n" +
                            "#EXT-X-MAP:URI=\"init.mp4?stream=$asked&v=1\"\n" +
                            "#EXTINF:6.000,\nseg-00001.m4s?stream=$asked&v=1\n" +
                            "#EXTINF:6.000,\nseg-00002.m4s?stream=$asked&v=1\n" +
                            "#EXT-X-ENDLIST\n",
                    )
                    path.endsWith("/init.mp4") -> MockResponse().setBody("INIT-BYTES")
                    path.endsWith("/seg-00001.m4s") -> MockResponse().setBody("SEGMENT-ONE")
                    path.endsWith("/seg-00002.m4s") -> MockResponse().setBody("SEGMENT-TWO")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        base = server.url("/api").toString().trimEnd('/')
        database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        cache = SimpleCache(File(dir, "media"), NoOpCacheEvictor(), database)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        cache.release()
        dir.deleteRecursively()
    }

    private val masterUrl get() = "$base/v1/items/film/play/master.m3u8?caps=avc"

    private fun titleOf(owner: Owner) = DownloadedTitle(
        itemId = "film",
        item = Item(id = "film", title = "Film", kind = "movie"),
        quality = "360p",
        server = base,
        owners = listOf(OwnerClaim(owner.subject, addedAtMs = 0, checkedAtMs = 0)),
    )

    /** The download stack as the engine builds it, for [active] signed in. */
    private fun downloader(active: Owner?, titles: List<DownloadedTitle>): HlsDownloader {
        val book = titles.associateBy { it.key }
        val http = OkHttpClient.Builder()
            .addInterceptor(SigningInterceptor { url -> streamTokenFor(url, base, active, book) { token } })
            .build()
        val sources = CacheDataSource.Factory()
            .setCache(cache)
            .setCacheKeyFactory(keys)
            .setUpstreamDataSourceFactory(downloadUpstream(http) {})
        val item = MediaItem.Builder()
            .setUri(masterUrl)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setStreamKeys(listOf(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, 0)))
            .build()
        return HlsDownloader(item, sources)
    }

    /** What playback reads: the cache alone, no upstream. */
    private fun readFromThePhone(url: String): ByteArray {
        val source = CacheDataSource.Factory()
            .setCache(cache)
            .setCacheKeyFactory(keys)
            .setCacheWriteDataSinkFactory(null)
            .createDataSource()
        source.open(DataSpec(Uri.parse(url)))
        return try {
            DataSourceUtil.readToEnd(source)
        } finally {
            source.close()
        }
    }

    @Test
    fun downloadedWithTheTokenOfThenAndPlayedWithNoServerAtAll() {
        val anna = Owner(base, "anna")
        downloader(anna, listOf(titleOf(anna))).download(null)

        // Every request signed with the token valid as it went out.
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { it.requestUrl?.queryParameter("stream") == TOKEN_ONE }, requests.map { it.path }.toString())

        // The server is gone: the master, its rendition and every piece read
        // from the phone, under the keys the download kept them by.
        server.shutdown()
        val master = readFromThePhone(masterUrl).decodeToString()
        assertFalse("stream=" in master, master)
        val parsedMaster = HlsPlaylistParser().parse(Uri.parse(masterUrl), master.byteInputStream()) as HlsMultivariantPlaylist
        val mediaUrl = parsedMaster.variants.single().url.toString()
        val media = readFromThePhone(mediaUrl).decodeToString()
        assertFalse("stream=" in media, media)
        val parsedMedia = HlsPlaylistParser(parsedMaster, null).parse(Uri.parse(mediaUrl), media.byteInputStream()) as HlsMediaPlaylist
        val pieces = listOfNotNull(parsedMedia.segments.first().initializationSegment) + parsedMedia.segments
        val bytes = pieces.map { s -> readFromThePhone(resolve(mediaUrl, s.url)).decodeToString() }
        assertEquals(listOf("INIT-BYTES", "SEGMENT-ONE", "SEGMENT-TWO"), bytes)
        // A piece that is not on the phone is not streamed in its place.
        assertFailsWith<IOException> { readFromThePhone("$base/v1/items/film/play/v0/seg-00003.m4s?v=1") }

        // No token on the disk: not in the cache's files, not in its index.
        assertNoTokenOnDisk()
    }

    @Test
    fun aDownloadResumedHoursLaterAsksWithTheTokenValidThen() {
        val anna = Owner(base, "anna")
        downloader(anna, listOf(titleOf(anna))).download(null)
        // A piece went missing, as a download cut off would have it.
        val second = pieceKey("$base/v1/items/film/play/v0/seg-00002.m4s?v=1")
        cache.removeResource(second)
        requests.clear()
        // Hours later: the old token is refused, a new one is valid.
        accepted = setOf(TOKEN_TWO)
        token = TOKEN_TWO
        downloader(anna, listOf(titleOf(anna))).download(null)
        // Only what was missing was fetched, signed with the new token -
        // never the one written into the playlists it kept.
        assertEquals(listOf("/api/v1/items/film/play/v0/seg-00002.m4s"), requests.map { it.requestUrl?.encodedPath })
        assertTrue(requests.all { it.requestUrl?.queryParameter("stream") == TOKEN_TWO })
        server.shutdown()
        assertEquals("SEGMENT-TWO", readFromThePhone("$base/v1/items/film/play/v0/seg-00002.m4s?v=1").decodeToString())
        assertNoTokenOnDisk()
    }

    @Test
    fun anotherAccountsDownloadIsNeverSignedWithTheSignedInOnesToken() {
        val parent = Owner(base, "parent")
        val child = Owner(base, "child")
        // The parent's title, the child signed in: no token goes, the server
        // refuses, nothing is kept.
        assertFailsWith<IOException> { downloader(child, listOf(titleOf(parent))).download(null) }
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { it.requestUrl?.queryParameter("stream") == null })
        // Nobody signed in: the same.
        requests.clear()
        assertFailsWith<IOException> { downloader(null, listOf(titleOf(parent))).download(null) }
        assertTrue(requests.all { it.requestUrl?.queryParameter("stream") == null })
    }

    @Test
    fun aTokenGoesOnlyToTheConnectedServerForATitleOfTheSignedInAccount() {
        val anna = Owner(base, "anna")
        val book = listOf(titleOf(anna)).associateBy { it.key }
        val mint = { "T" }
        fun asked(url: String, owner: Owner? = anna, server: String = base) = streamTokenFor(url.toHttpUrl(), server, owner, book, mint)
        assertEquals("T", asked("$base/v1/items/film/play/v0/seg-00001.m4s"))
        // Another origin - another host, another port, another scheme.
        assertEquals(null, asked("http://elsewhere.example:8080/api/v1/items/film/play/master.m3u8"))
        assertEquals(null, asked(base.replace("http://", "https://") + "/v1/items/film/play/master.m3u8"))
        // The connected server is another one than the title's.
        assertEquals(null, asked("$base/v1/items/film/play/master.m3u8", server = "https://other.example/api"))
        // A title the account does not have, or none named.
        assertEquals(null, asked("$base/v1/items/other/play/master.m3u8"))
        assertEquals(null, asked("$base/v1/play/subs/s1.vtt"))
        // Another account, nobody.
        assertEquals(null, asked("$base/v1/items/film/play/master.m3u8", owner = Owner(base, "ben")))
        assertEquals(null, asked("$base/v1/items/film/play/master.m3u8", owner = null))
    }

    private fun assertNoTokenOnDisk() {
        val app = RuntimeEnvironment.getApplication()
        val places = listOf(dir, app.filesDir, File(app.applicationInfo.dataDir, "databases"))
        for (place in places) {
            for (f in place.walkTopDown().filter { it.isFile }) {
                val text = f.readBytes().toString(Charsets.ISO_8859_1)
                assertFalse(TOKEN_ONE in text || TOKEN_TWO in text, "a token in $f")
            }
        }
    }

    private fun resolve(base: String, ref: String): String = Uri.parse(base).buildUpon().build().let {
        cloud.nalet.chino.mobile.data.downloads.resolveUrl(base, ref)
    }

    private companion object {
        const val TOKEN_ONE = "tok1-c0ffee-0001"
        const val TOKEN_TWO = "tok2-c0ffee-0002"
    }
}
