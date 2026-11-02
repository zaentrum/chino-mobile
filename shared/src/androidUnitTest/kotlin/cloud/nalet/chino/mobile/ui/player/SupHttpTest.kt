package cloud.nalet.chino.mobile.ui.player

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A .sup sidecar fetched over HTTP as the player fetches one (PlayerScreen:
 * OkHttp under SupSidecarDataSource), from a server on this machine: the
 * stream token on its address and a redirect kept, and a cache that can't
 * keep it - no room, a dir that can't be made - ending the subtitle at once,
 * never the playback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SupHttpTest {
    @get:Rule val folder = TemporaryFolder()

    private val token = "tok123"
    private val sup = threeLines().sup()
    private val requests: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** A server on 127.0.0.1: a connection per request. */
    private inner class Server : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort

        init {
            Thread {
                while (!socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (e: IOException) {
                        break
                    }
                    Thread { client.use(::answer) }.start()
                }
            }.start()
        }

        private fun answer(client: java.net.Socket) {
            val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val target = (reader.readLine() ?: return).split(' ')[1]
            var agent: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("user-agent:")) agent = line.substringAfter(':').trim()
            }
            requests += "$target $agent"
            val out = client.getOutputStream()
            try {
                when (target.substringBefore('?')) {
                    "/redirect/x.sup" -> out.head("302 Found", "Location: /subs/x.sup?${target.substringAfter('?')}")
                    "/subs/x.sup" -> if (target.contains("stream=$token")) {
                        out.head("200 OK", length = sup.size)
                        out.write(sup)
                        out.flush()
                    } else {
                        out.head("401 Unauthorized")
                    }
                    else -> out.head("404 Not Found")
                }
            } catch (e: IOException) {
            }
        }

        override fun close() = socket.close()
    }

    private fun OutputStream.head(status: String, vararg extra: String, length: Int = 0) {
        val head = StringBuilder("HTTP/1.1 $status\r\nConnection: close\r\n")
        for (h in extra) head.append(h).append("\r\n")
        head.append("Content-Length: ").append(length).append("\r\n\r\n")
        write(head.toString().toByteArray(Charsets.ISO_8859_1))
        flush()
    }

    /** The data source PlayerScreen gives a PGS sidecar. */
    private fun chain(session: SupSidecarCache.Session): DataSource.Factory =
        SupSidecarDataSource.Factory(OkHttpDataSource.Factory(OkHttpClient()).setUserAgent("chino-mobile/0.2 (Android)"), session)

    @Test
    fun aSidecarsDownloadKeepsTheStreamTokenOnItsAddressAndFollowsARedirect() {
        Server().use { server ->
            val dir = folder.newFolder("cache")
            val session = SupSidecarCache(dir).session()
            val uri = Uri.parse("http://127.0.0.1:${server.port}/redirect/x.sup?stream=$token")
            val source = chain(session).createDataSource()
            source.open(DataSpec.Builder().setUri(uri).setFlags(DataSpec.FLAG_ALLOW_GZIP).build())
            val ref = try {
                DataSourceUtil.readToEnd(source)
            } finally {
                source.close()
            }
            val file = SupFileRef.fileOf(ref, 0, ref.size)!!
            assertTrue(sup.contentEquals(file.readBytes()))
            assertEquals(
                listOf("/redirect/x.sup?stream=$token chino-mobile/0.2 (Android)", "/subs/x.sup?stream=$token chino-mobile/0.2 (Android)"),
                requests.toList(),
            )
            session.release()
        }
    }

    /** Loads the sidecar at [path] as a title's merged source does, with
     *  Media3's own load error rules (the sidecar's), until it ends: what the
     *  player sees of it. */
    private fun load(path: String, cache: SupSidecarCache): String {
        requests.clear()
        Server().use { server ->
            val session = cache.session()
            val uri = Uri.parse("http://127.0.0.1:${server.port}$path")
            val config = MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(MimeTypes.APPLICATION_PGS).setLanguage("en").build()
            val sidecar = SingleSampleMediaSource.Factory(chain(session)).createMediaSource(config, C.TIME_UNSET)
            val merged = MergingMediaSource(SilenceMediaSource.Factory().setDurationUs(600_000_000L).createMediaSource(), sidecar)
            var timeline: Timeline? = null
            merged.prepareSource({ _, t -> timeline = t }, null, PlayerId.UNSET)
            shadowOf(Looper.getMainLooper()).idle()
            val period = merged.createPeriod(MediaSource.MediaPeriodId(timeline!!.getUidOfPeriod(0)), DefaultAllocator(true, 64 * 1024), 0)
            period.prepare(
                object : MediaPeriod.Callback {
                    override fun onPrepared(mediaPeriod: MediaPeriod) = Unit

                    override fun onContinueLoadingRequested(source: MediaPeriod) = Unit
                },
                0,
            )
            shadowOf(Looper.getMainLooper()).idle()
            val groups = period.trackGroups
            val streams = arrayOfNulls<SampleStream>(2)
            period.selectTracks(arrayOf<ExoTrackSelection?>(FixedTrackSelection(groups[0], 0), FixedTrackSelection(groups[1], 0)), BooleanArray(2), streams, BooleanArray(2), 0)
            period.continueLoading(LoadingInfo.Builder().setPlaybackPositionUs(0).build())
            var waitedMs = 0L
            while (period.bufferedPositionUs != C.TIME_END_OF_SOURCE && waitedMs < 20_000) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                waitedMs += 100
                Thread.sleep(3)
            }
            var thrown: Throwable? = null
            try {
                streams[1]!!.maybeThrowError()
                period.maybeThrowPrepareError()
            } catch (e: Throwable) {
                thrown = e
            }
            val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
            val read = streams[1]!!.readData(FormatHolder(), buffer, 0)
            val ended = read == C.RESULT_BUFFER_READ && buffer.isEndOfStream
            merged.releasePeriod(period)
            session.release()
            assertNull(thrown, "the player was told: $thrown")
            return "requests=${requests.size} ended=$ended waitedLessThanAMinute=${waitedMs < 60_000}"
        }
    }

    @Test
    fun aSidecarTheCacheCannotKeepEndsAtOnceItsPlaybackGoingOn() {
        // No room for it: asked for once, for its length, then left.
        val full = folder.newFolder("full")
        assertEquals("requests=1 ended=true waitedLessThanAMinute=true", load("/subs/x.sup?stream=$token", SupSidecarCache(full, freeSpace = { 0L })))
        assertEquals(emptyList<String>(), full.list().orEmpty().toList())
        // A cache dir that can't be made: a file stands where it would be.
        val blocked = File(folder.newFile("not-a-dir"), "pgs-sidecars")
        assertEquals("requests=0 ended=true waitedLessThanAMinute=true", load("/subs/x.sup?stream=$token", SupSidecarCache(blocked)))
    }
}
