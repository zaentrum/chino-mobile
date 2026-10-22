package cloud.nalet.chino.mobile.downloads

import android.util.Log
import cloud.nalet.chino.mobile.data.downloads.BookChange
import cloud.nalet.chino.mobile.data.downloads.DownloadedTitle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The files a download keeps beside its media, one folder per title, by
 * its key (DownloadedTitle.key: its item id and its server):
 *
 *     downloads/titles/<key>/title.json     what the screens need of it
 *     downloads/titles/<key>/poster.jpg     its poster (an episode: its series')
 *     downloads/titles/<key>/backdrop.jpg   its backdrop (an episode: its still)
 *     downloads/titles/<key>/subs/<file>    its sidecar subtitles
 *
 * What is in memory ([titles]) is the truth; the disk follows it, on one
 * writer thread of its own - never the main thread - in the order of the
 * changes, each title.json through a temporary file. A write that fails (the
 * disk is full) leaves the title as it is in memory, and is tried again a
 * while later and with the next change: nothing is lost, and nothing
 * crashes. The folders are read once, on the writer, as the process starts
 * ([ready]); a title.json that does not read any more is one of [unreadable].
 */
internal class TitleFiles(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val writer = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "chino-title-files").apply { isDaemon = true } }

    private val _titles = MutableStateFlow<Map<String, DownloadedTitle>>(emptyMap())
    val titles: StateFlow<Map<String, DownloadedTitle>> = _titles.asStateFlow()

    private val _ready = MutableStateFlow(false)

    /** The folders are read: [titles] holds every title on the phone. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Keys of the folders whose title.json could not be read. */
    @Volatile
    var unreadable: List<String> = emptyList()
        private set

    /** Keys whose title.json is still to write: one write each, of the
     *  title as it is when it runs. */
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** Keys whose write failed, tried again later. */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    init {
        writer.execute { load() }
    }

    suspend fun awaitReady() {
        ready.first { it }
    }

    /** Waits for the changes made so far to be on the disk - or to have
     *  failed: before files go into a folder whose deletion is under way. */
    suspend fun awaitWrites() {
        val done = CompletableDeferred<Unit>()
        writer.execute { done.complete(Unit) }
        done.await()
    }

    fun dirOf(key: String): File = File(root, safeName(key))

    fun subtitlesDir(key: String): File = File(dirOf(key), "subs")

    operator fun get(key: String): DownloadedTitle? = _titles.value[key]

    /** [title] in memory at once, on the disk a moment later. */
    @Synchronized
    fun put(title: DownloadedTitle) {
        _titles.value = _titles.value + (title.key to title)
        write(title.key)
    }

    /** [key]'s title changed by [change]; nothing for a title not there. */
    @Synchronized
    fun update(key: String, change: (DownloadedTitle) -> DownloadedTitle) {
        val cur = _titles.value[key] ?: return
        val next = change(cur)
        if (next == cur) return
        _titles.value = _titles.value + (key to next)
        write(key)
    }

    /** Deletes the title's folder - its metadata, artwork and subtitles. */
    @Synchronized
    fun remove(key: String) {
        _titles.value = _titles.value - key
        writer.execute { deleteFolder(key) }
    }

    /**
     * Changes the titles by [change], worked out from them as they are now,
     * under the same lock as every other change: what it changed is written,
     * what it removed has its folder deleted. What it came to is returned -
     * the downloads whose media goes with it among it.
     */
    @Synchronized
    fun change(change: (Map<String, DownloadedTitle>) -> BookChange): BookChange {
        val before = _titles.value
        val c = change(before)
        _titles.value = c.titles
        for ((key, t) in c.titles) {
            if (before[key] != t) write(key)
        }
        for (key in before.keys - c.titles.keys) {
            writer.execute { deleteFolder(key) }
        }
        return c
    }

    /** Deletes the folder of an unreadable title. */
    fun removeUnreadable(key: String) {
        unreadable = unreadable - key
        writer.execute { deleteFolder(key) }
    }

    /** What the folders take on the phone. */
    fun bytes(): Long = runCatching { root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)

    /** What [key]'s folder takes. */
    fun bytesOf(key: String): Long = runCatching { dirOf(key).walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)

    /** The writes that failed, again: there may be room now. */
    fun retryFailed() {
        for (key in failed.toList()) write(key)
    }

    /** [block] on the writer, in its turn - for a test that holds the
     *  writer while changes queue up behind it. */
    internal fun onWriter(block: () -> Unit) {
        writer.execute(block)
    }

    private fun write(key: String) {
        if (pending.add(key)) writer.execute { writeNow(key) }
    }

    private fun writeNow(key: String) {
        pending.remove(key)
        val title = _titles.value[key] ?: return
        try {
            val dir = dirOf(key)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot make $dir")
            val tmp = File(dir, "title.json.tmp")
            tmp.writeText(json.encodeToString(DownloadedTitle.serializer(), title))
            val file = File(dir, "title.json")
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
            failed.remove(key)
        } catch (e: IOException) {
            // Full, most likely: the title stays as it is in memory, and its
            // file is written again in a while, and with its next change.
            Log.w(TAG, "a title's file could not be written, kept in memory: ${e.message}")
            if (failed.add(key)) writer.schedule({ if (failed.contains(key)) write(key) }, RETRY_S, TimeUnit.SECONDS)
        }
    }

    private fun deleteFolder(key: String) {
        failed.remove(key)
        runCatching { dirOf(key).deleteRecursively() }
        // Put again since it was removed: back on the disk as it is in
        // memory - the write that put asked for may have gone out ahead of
        // this deletion, with one queued from before it.
        if (_titles.value[key] != null) writeNow(key)
    }

    private fun load() {
        val loaded = HashMap<String, DownloadedTitle>()
        val bad = ArrayList<String>()
        runCatching { root.mkdirs() }
        for (dir in root.listFiles().orEmpty()) {
            if (!dir.isDirectory) continue
            val file = File(dir, "title.json")
            val title = runCatching { json.decodeFromString(DownloadedTitle.serializer(), file.readText()) }.getOrNull()
            if (title == null || safeName(title.key) != dir.name) {
                bad += dir.name
            } else {
                loaded[title.key] = title
            }
        }
        unreadable = bad
        // What was put meanwhile wins over what the disk had.
        synchronized(this) { _titles.value = loaded + _titles.value }
        _ready.value = true
    }

    /** A key as a folder name: letters, digits, dashes and the separator. */
    private fun safeName(key: String): String = key.filter { it.isLetterOrDigit() || it == '-' || it == '~' }.ifEmpty { "_" }

    private companion object {
        const val TAG = "ChinoDownloads"
        const val RETRY_S = 30L
    }
}
