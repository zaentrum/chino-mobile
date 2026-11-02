package cloud.nalet.chino.mobile.ui.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A PGS sidecar on disk (SupFiles.kt): Media3 is handed where the file is,
 * never its bytes; SupParser reads it from there, an object's bytes as its set
 * is drawn; the files are kept while a player holds them and the last two
 * after. The .sup files are made in the test (SupFile.kt).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SupFilesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun file(bytes: ByteArray, name: String = "track.sup"): File = folder.newFile(name).apply { writeBytes(bytes) }

    private fun parseAll(bytes: ByteArray): List<CuesWithTiming> {
        val out = ArrayList<CuesWithTiming>()
        SupParser().parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues()) { out += it }
        return out
    }

    /** The pictures of [cues]: their sizes and places. */
    private fun shapes(cues: List<CuesWithTiming>) = cues.map { c ->
        Triple(c.startTimeUs, c.durationUs, c.cues.map { listOf(it.bitmap!!.width, it.bitmap!!.height, it.position, it.line) })
    }

    @Test
    fun aSupFileOnDiskShowsAsItsBytesDoReadFromItsReference() {
        val bytes = threeLines().sup()
        val ref = SupFileRef.of(file(bytes))
        assertEquals(shapes(parseAll(bytes)), shapes(parseAll(ref)))
        val fromDisk = SupParser().parseToLegacySubtitle(ref, 0, ref.size)
        val inMemory = SupParser().parseToLegacySubtitle(bytes, 0, bytes.size)
        assertEquals((0 until inMemory.eventTimeCount).map(inMemory::getEventTime), (0 until fromDisk.eventTimeCount).map(fromDisk::getEventTime))
        for (at in listOf(0L, 1_500_000L, 3_500_000L, 5_000_000L, 7_000_000L, 9_000_000L)) {
            assertEquals(
                inMemory.getCues(at).map { it.bitmap!!.width to it.line },
                fromDisk.getCues(at).map { it.bitmap!!.width to it.line },
                "at $at",
            )
        }
    }

    @Test
    fun ofAFileOnDiskOnlyItsIndexIsInMemoryNeverItsObjectsBytes() {
        // Sixty lines, each an object of many runs, as a line of text is.
        val pgs = Pgs()
        repeat(60) { i ->
            pgs.set(
                1_000_000L + i * 4_000_000L,
                composition(2 * i, STATE_EPOCH_START, Shown(0, 160, 950)),
                PALETTE to palette(0, 0, white(1)),
                *objectSegments(0, 1600, 90, colour = 1, data = textLike(1600, 90), parts = 2).map { OBJECT to it }.toTypedArray(),
            ).clears(3_000_000L + i * 4_000_000L, 2 * i + 1)
        }
        val bytes = pgs.sup()
        val onDisk = SupSource.OnDisk(file(bytes))
        val sets = onDisk.displaySets()
        assertEquals(120, sets.size)
        val held = heldBytes(sets)
        assertTrue(held * 50 < bytes.size, "$held bytes held of a ${bytes.size}-byte file")
        // And every picture still comes from the file.
        val cues = SupPictures(onDisk).cuesOf(sets[118]).single()
        assertEquals(1600 to 90, cues.bitmap!!.width to cues.bitmap!!.height)
    }

    /** A row of 1600 pixels as 80 runs of 10 transparent and 10 of colour 1. */
    private fun textLike(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(height) {
            repeat(width / 20) {
                out.write(0); out.write(10)
                out.write(0); out.write(0x80 or 10); out.write(1)
            }
            out.write(0); out.write(0)
        }
        return out.toByteArray()
    }

    @Test
    fun aReferenceThisProcessDidNotMakeIsNotRead() {
        val target = file(threeLines().sup())
        val forged = ("chino-sup-file\n" + "another-run\n" + target.absolutePath + "\n" + target.length()).toByteArray()
        assertNull(SupFileRef.fileOf(forged, 0, forged.size))
        assertEquals(emptyList<Any>(), SupParser().parseToLegacySubtitle(forged, 0, forged.size).getCues(1_500_000))
        // Nor one whose file has changed since.
        val ref = SupFileRef.of(target)
        target.appendBytes(byteArrayOf(0))
        assertNull(SupFileRef.fileOf(ref, 0, ref.size))
    }

    /** Hands out [bytes], counting how often it is opened; [claimed] the
     *  length it says it has. */
    private class Upstream(private val bytes: ByteArray, private val claimed: Long = bytes.size.toLong()) : DataSource.Factory {
        var opened = 0

        override fun createDataSource(): DataSource = object : DataSource {
            private val inner = ByteArrayDataSource(bytes)

            override fun addTransferListener(transferListener: TransferListener) = Unit

            override fun open(dataSpec: DataSpec): Long {
                opened++
                inner.open(dataSpec)
                return claimed
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner.read(buffer, offset, length)

            override fun getUri(): Uri? = inner.uri

            override fun close() = inner.close()
        }
    }

    private fun readAll(source: DataSource, uri: Uri): ByteArray {
        source.open(DataSpec(uri))
        return try {
            DataSourceUtil.readToEnd(source)
        } finally {
            source.close()
        }
    }

    @Test
    fun aSidecarIsFetchedIntoTheCacheOnceAndMedia3IsHandedWhereItIs() {
        val bytes = threeLines().sup()
        val upstream = Upstream(bytes)
        val dir = folder.newFolder("cache")
        val cache = SupSidecarCache(dir)
        val session = cache.session()
        val uri = Uri.parse("https://example.invalid/subs/1.vtt?token=secret")
        val factory = SupSidecarDataSource.Factory(upstream, session)
        val ref = readAll(factory.createDataSource(), uri)
        val fetched = SupFileRef.fileOf(ref, 0, ref.size)
        assertNotNull(fetched)
        assertEquals(dir, fetched!!.parentFile)
        assertContentEquals(bytes, fetched.readBytes())
        // The address, the token on it, is not on disk.
        assertFalse(fetched.name.contains("secret"))
        // Again, for the same player or the next: from the cache.
        readAll(factory.createDataSource(), uri)
        readAll(SupSidecarDataSource.Factory(upstream, cache.session()).createDataSource(), uri)
        assertEquals(1, upstream.opened)
        assertTrue(ref.size < 1024)
    }

    @Test
    fun everyOtherRequestGoesToTheUpstreamAsItComes() {
        val bytes = "WEBVTT\n".toByteArray()
        val source = SupSidecarDataSource.Factory(Upstream(bytes), SupSidecarCache(folder.newFolder("cache")).session()) { false }.createDataSource()
        assertContentEquals(bytes, readAll(source, Uri.parse("https://example.invalid/subs/2.vtt")))
    }

    @Test
    fun aDownloadCutShortKeepsNoFile() {
        val bytes = threeLines().sup()
        val dir = folder.newFolder("cache")
        val source = SupSidecarDataSource.Factory(Upstream(bytes, claimed = bytes.size + 100L), SupSidecarCache(dir).session()).createDataSource()
        assertFailsWith<IOException> { readAll(source, Uri.parse("https://example.invalid/subs/3.vtt")) }
        assertEquals(emptyList<String>(), dir.list().orEmpty().toList())
    }

    @Test
    fun aFileIsKeptWhileAPlayerHoldsItOfThoseLetGoTheLastTwo() {
        val dir = folder.newFolder("cache")
        val cache = SupSidecarCache(dir)
        val sessions = (1..4).map { cache.session() }
        val files = sessions.mapIndexed { i, session ->
            val uri = Uri.parse("https://example.invalid/subs/$i.vtt")
            readAll(SupSidecarDataSource.Factory(Upstream(threeLines().sup()), session).createDataSource(), uri)
            File(dir, SupSidecarCache.nameOf(uri))
        }
        // A file two players hold stays when one lets go.
        val second = cache.session()
        readAll(SupSidecarDataSource.Factory(Upstream(threeLines().sup()), second).createDataSource(), Uri.parse("https://example.invalid/subs/3.vtt"))
        sessions[3].release()
        assertTrue(files[3].isFile)
        sessions[0].release()
        sessions[1].release()
        sessions[2].release()
        // Let go of: 0, 1, 2 - the last two kept.
        assertEquals(listOf(false, true, true, true), files.map { it.isFile })
        second.release()
        // 3 let go of last: 1 goes.
        assertEquals(listOf(false, false, true, true), files.map { it.isFile })
    }

    @Test
    fun aLocalFileIsReferredToWhereItIs() {
        val local = file(threeLines().sup(), "beside-the-download.sup")
        val dir = folder.newFolder("cache")
        val ref = readAll(SupSidecarDataSource.Factory(Upstream(byteArrayOf(1)), SupSidecarCache(dir).session()).createDataSource(), Uri.fromFile(local))
        assertEquals(local.absolutePath, SupFileRef.fileOf(ref, 0, ref.size)!!.absolutePath)
        assertEquals(emptyList<String>(), dir.list().orEmpty().toList())
    }

    @Test
    fun whatAnEarlierRunLeftGoesItsFilesAndADownloadItLeftUnfinished() {
        val dir = folder.newFolder("cache")
        // Their names come from addresses with that run's stream tokens on them.
        val old = (1..3).map { i -> File(dir, "old$i.sup").apply { writeBytes(byteArrayOf(1)); setLastModified(1_000_000L * i) } }
        val part = File(dir, "x.sup.1234.part").apply { writeBytes(byteArrayOf(1)) }
        val cache = SupSidecarCache(dir)
        readAll(SupSidecarDataSource.Factory(Upstream(threeLines().sup()), cache.session()).createDataSource(), Uri.parse("https://example.invalid/subs/9.vtt"))
        assertEquals(listOf(false, false, false), old.map { it.isFile })
        assertFalse(part.exists())
        assertEquals(1, dir.list()!!.size)
    }

    @Test
    fun aSidecarThereIsNoRoomForIsNotKeptAndNotTriedAgain() {
        val bytes = threeLines().sup()
        val dir = folder.newFolder("cache")
        val upstream = Upstream(bytes)
        // Less room than the sidecar and the margin a download leaves.
        val cache = SupSidecarCache(dir, freeSpace = { bytes.size + SupSidecarCache.ROOM_LEFT - 1 })
        val error = try {
            readAll(SupSidecarDataSource.Factory(upstream, cache.session()).createDataSource(), Uri.parse("https://example.invalid/subs/4.vtt"))
            null
        } catch (e: IOException) {
            e
        }
        assertTrue(error is SupNotKeptException, "$error")
        assertEquals(1, upstream.opened)
        assertEquals(emptyList<String>(), dir.list().orEmpty().toList())
        // Final for the sidecar's load error rules (Media3's own): never retried from its first byte.
        assertEquals(C.TIME_UNSET, DefaultLoadErrorHandlingPolicy().getRetryDelayMsFor(loadError(error!!)))
    }

    @Test
    fun aCacheDirThatCannotBeMadeKeepsNothingAsksTheServerNothingAndIsNotTriedAgain() {
        val blocked = folder.newFile("not-a-dir")
        val upstream = Upstream(threeLines().sup())
        val cache = SupSidecarCache(File(blocked, "pgs-sidecars"))
        val error = try {
            readAll(SupSidecarDataSource.Factory(upstream, cache.session()).createDataSource(), Uri.parse("https://example.invalid/subs/5.vtt"))
            null
        } catch (e: IOException) {
            e
        }
        assertTrue(error is SupNotKeptException, "$error")
        assertEquals(0, upstream.opened)
        assertEquals(C.TIME_UNSET, DefaultLoadErrorHandlingPolicy().getRetryDelayMsFor(loadError(error!!)))
    }

    private fun loadError(error: IOException) = LoadErrorHandlingPolicy.LoadErrorInfo(
        LoadEventInfo(0, DataSpec(Uri.EMPTY), 0),
        MediaLoadData(C.DATA_TYPE_MEDIA),
        error,
        1,
    )

    /** Hands out [bytes] 1 KiB a read; the first read says [entered] and waits for [go]. */
    private class Gated(private val bytes: ByteArray) : DataSource.Factory {
        val entered = CountDownLatch(1)
        val go = CountDownLatch(1)

        override fun createDataSource(): DataSource = object : DataSource {
            private val inner = ByteArrayDataSource(bytes)
            private var reads = 0

            override fun addTransferListener(transferListener: TransferListener) = Unit

            override fun open(dataSpec: DataSpec): Long {
                inner.open(dataSpec)
                return bytes.size.toLong()
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (reads++ == 0) {
                    entered.countDown()
                    go.await(30, TimeUnit.SECONDS)
                }
                return inner.read(buffer, offset, minOf(length, 1024))
            }

            override fun getUri(): Uri? = inner.uri

            override fun close() = inner.close()
        }
    }

    @Test
    fun aPlayerReleasedWhileItsFetchWaitsNeverTakesTheFileFromOneThatHoldsIt() {
        val dir = folder.newFolder("cache")
        val cache = SupSidecarCache(dir)
        val bytes = threeLines().sup()
        val uri = Uri.parse("https://example.invalid/subs/f.sup?stream=t")
        val file = File(dir, SupSidecarCache.nameOf(uri))
        val gate = Gated(bytes)
        // The player that plays on, and one released while its load waits for the same file.
        val live = cache.session()
        val old = cache.session()
        val liveLoad = thread { readAll(SupSidecarDataSource.Factory(gate, live).createDataSource(), uri) }
        assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
        var oldError: Throwable? = null
        val oldLoad = thread {
            try {
                readAll(SupSidecarDataSource.Factory(Upstream(bytes), old).createDataSource(), uri)
            } catch (e: IOException) {
                oldError = e
            }
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (oldLoad.state != Thread.State.BLOCKED && System.currentTimeMillis() < deadline) Thread.sleep(5)
        old.release()
        gate.go.countDown()
        liveLoad.join(10_000)
        oldLoad.join(10_000)
        assertTrue(oldError is java.io.InterruptedIOException, "$oldError")
        val ref = SupFileRef.of(file)
        val subtitle = SupParser().parseToLegacySubtitle(ref, 0, ref.size)
        assertEquals(1, subtitle.getCues(1_500_000).size)
        // Two other players come and go: the live one's file stays.
        repeat(2) { i ->
            val other = cache.session()
            readAll(SupSidecarDataSource.Factory(Upstream(bytes), other).createDataSource(), Uri.parse("https://example.invalid/subs/other$i.sup"))
            other.release()
        }
        assertTrue(file.isFile)
        assertEquals(1, subtitle.getCues(4_500_000).size)
        live.release()
        // Let go of last: one of the last two, kept.
        assertTrue(file.isFile)
    }

    @Test
    fun aSidecarsIndexIsReadAsItsDownloadEndsTheDecoderOnlyPicksItUp() {
        val bytes = threeLines().sup()
        val dir = folder.newFolder("cache")
        val uri = Uri.parse("https://example.invalid/subs/6.vtt")
        val ref = readAll(SupSidecarDataSource.Factory(Upstream(bytes), SupSidecarCache(dir).session()).createDataSource(), uri)
        val file = SupFileRef.fileOf(ref, 0, ref.size)!!
        assertEquals(5, SupIndexCache.setsOf(file) { fail("the index was read again"); emptyList() }.size)
    }

    @Test
    fun aSidecarFileDeletedOrCutUnderItsSubtitleShowsNothingAndNothingThrows() {
        val bytes = threeLines().sup()
        val deleted = folder.newFile("deleted.sup").apply { writeBytes(bytes) }
        val ref = SupFileRef.of(deleted)
        val subtitle = SupParser().parseToLegacySubtitle(ref, 0, ref.size)
        assertEquals(1, subtitle.getCues(1_500_000).size)
        deleted.delete()
        assertEquals(0, subtitle.getCues(4_500_000).size)
        // Decoded again, after a seek: nothing, the file gone.
        val again = SupParser().parseToLegacySubtitle(ref, 0, ref.size)
        assertEquals(0, again.getCues(4_500_000).size)
        val cut = folder.newFile("cut.sup").apply { writeBytes(bytes) }
        val cutRef = SupFileRef.of(cut)
        val cutSubtitle = SupParser().parseToLegacySubtitle(cutRef, 0, cutRef.size)
        cut.writeBytes(bytes.copyOf(bytes.size / 2))
        assertEquals(0, cutSubtitle.getCues(6_600_000).size)
    }
}
