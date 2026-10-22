package cloud.nalet.chino.mobile.downloads

import cloud.nalet.chino.mobile.data.downloads.BookChange
import cloud.nalet.chino.mobile.data.downloads.DownloadedTitle
import cloud.nalet.chino.mobile.data.downloads.OwnerClaim
import cloud.nalet.chino.mobile.data.model.Item
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The title files beside the media: in memory first, on the disk after,
 *  and a disk that cannot take a write never loses a title or crashes. */
class TitleFilesTest {
    private val root: File = Files.createTempDirectory("title-files").toFile()

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun title(id: String) = DownloadedTitle(
        itemId = id,
        item = Item(id = id, title = id, kind = "movie"),
        quality = "720p",
        server = "https://media.example.com/api",
        owners = listOf(OwnerClaim("anna", addedAtMs = 1, checkedAtMs = 1)),
    )

    private fun fileOf(t: DownloadedTitle) = File(File(root, t.key), "title.json")

    @Test
    fun aWriteThatFailsKeepsTheTitleInMemoryAndIsWrittenOnceItCanBe() = runBlocking {
        val files = TitleFiles(root)
        files.awaitReady()
        val t = title("film")
        // The disk cannot take it: where the folder goes there is a file -
        // as a full disk fails the write.
        File(root, t.key).writeText("in the way")
        files.put(t)
        files.awaitWrites()
        // No crash; the title as it was put, in memory.
        assertEquals(t, files[t.key])
        assertFalse(fileOf(t).isFile)
        // Room again: the write that failed goes out.
        File(root, t.key).delete()
        files.retryFailed()
        files.awaitWrites()
        assertTrue(fileOf(t).isFile)
        // And a new process reads it back.
        val again = TitleFiles(root)
        again.awaitReady()
        assertEquals(t, again[t.key])
    }

    @Test
    fun changesReachTheDiskInTheirOrder() = runBlocking {
        val files = TitleFiles(root)
        files.awaitReady()
        val t = title("film")
        // The writer busy while the changes come: the first put's write is
        // still queued as the title goes and comes back.
        val hold = java.util.concurrent.CountDownLatch(1)
        files.onWriter { hold.await() }
        files.put(t)
        files.remove(t.key)
        files.put(t.copy(quality = "480p"))
        hold.countDown()
        files.awaitWrites()
        val again = TitleFiles(root)
        again.awaitReady()
        assertEquals("480p", again[t.key]?.quality)
        // A change of the book: what it removed has its folder deleted.
        files.change { BookChange(it - t.key, listOf(t.key)) }
        files.awaitWrites()
        assertFalse(File(root, t.key).exists())
        assertNull(files[t.key])
    }

    @Test
    fun aTitleThatDoesNotReadAnyMoreIsNamedNotHidden() = runBlocking {
        val bad = File(root, "broken~0000000000000000").apply { mkdirs() }
        File(bad, "title.json").writeText("{ not json")
        val files = TitleFiles(root)
        files.awaitReady()
        assertEquals(listOf("broken~0000000000000000"), files.unreadable)
        files.removeUnreadable("broken~0000000000000000")
        files.awaitWrites()
        assertFalse(bad.exists())
    }
}
