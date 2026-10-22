package cloud.nalet.chino.mobile.downloads

import cloud.nalet.chino.mobile.data.downloads.DownloadedTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The files a download keeps beside its media, one folder per title:
 *
 *     downloads/titles/<item id>/title.json   what the screens need of it
 *     downloads/titles/<item id>/poster.jpg   its poster (an episode: its series')
 *     downloads/titles/<item id>/backdrop.jpg its backdrop (an episode: its still)
 *     downloads/titles/<item id>/subs/<id>.vtt  its sidecar subtitles
 *
 * Read once as the process starts, kept in [titles]; every change is
 * written at once, through a temporary file, so a title.json is never half
 * written.
 */
internal class TitleFiles(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val _titles = MutableStateFlow(readAll())
    val titles: StateFlow<Map<String, DownloadedTitle>> = _titles.asStateFlow()

    fun dirOf(itemId: String): File = File(root, safeName(itemId))

    fun subtitlesDir(itemId: String): File = File(dirOf(itemId), "subs")

    operator fun get(itemId: String): DownloadedTitle? = _titles.value[itemId]

    @Synchronized
    fun put(title: DownloadedTitle) {
        val dir = dirOf(title.itemId).apply { mkdirs() }
        val tmp = File(dir, "title.json.tmp")
        tmp.writeText(json.encodeToString(DownloadedTitle.serializer(), title))
        if (!tmp.renameTo(File(dir, "title.json"))) {
            File(dir, "title.json").writeText(tmp.readText())
            tmp.delete()
        }
        _titles.value = _titles.value + (title.itemId to title)
    }

    @Synchronized
    fun update(itemId: String, change: (DownloadedTitle) -> DownloadedTitle) {
        val current = _titles.value[itemId] ?: return
        put(change(current))
    }

    /** Deletes the title's folder - its metadata, artwork and subtitles. */
    @Synchronized
    fun remove(itemId: String) {
        dirOf(itemId).deleteRecursively()
        _titles.value = _titles.value - itemId
    }

    @Synchronized
    fun removeAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
        _titles.value = emptyMap()
    }

    /** What the folders take on the phone. */
    fun bytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun readAll(): Map<String, DownloadedTitle> {
        root.mkdirs()
        return root.listFiles().orEmpty()
            .mapNotNull { dir -> File(dir, "title.json").takeIf { it.isFile } }
            .mapNotNull { f -> runCatching { json.decodeFromString(DownloadedTitle.serializer(), f.readText()) }.getOrNull() }
            .associateBy { it.itemId }
    }

    /** An item id as a folder name: ids are UUIDs, anything else is kept to
     *  letters, digits and dashes. */
    private fun safeName(id: String): String = id.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "_" }
}
