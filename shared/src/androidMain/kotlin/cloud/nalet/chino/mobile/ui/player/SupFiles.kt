package cloud.nalet.chino.mobile.ui.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.UUID

/*
 * A PGS sidecar on disk rather than on the heap. Media3 1.5.1 side-loads a
 * subtitle as one sample: it reads the whole file into an array of its own
 * (SingleSampleMediaPeriod, grown by doubling, kept as long as the player
 * lives) and copies it into the text renderer's input buffer - a .sup file,
 * tens of megabytes for a film's dialogue, would be on the heap twice and
 * more. SupSidecarDataSource hands Media3 instead a few bytes that say where
 * the file is ([SupFileRef]), the file fetched into the app's cache dir
 * first ([SupSidecarCache]); SupParser reads the file from there (an index
 * of its segments, an object's bytes as its set is drawn, SupSubtitles.kt).
 * A file kept is the player's for as long as it lives; the last ones it let
 * go stay for the next player (a quality switch, the same title again),
 * the rest are deleted.
 */

/**
 * What Media3 is handed for a .sup file in place of its bytes: where the file
 * is. Only this process's own references are read (they carry its [token]):
 * bytes a server sends that look like one are not.
 */
internal object SupFileRef {
    private const val MAGIC = "chino-sup-file\n"
    private const val MAX_SIZE = 4096
    private val token: String = UUID.randomUUID().toString()

    /** The reference to [file]. */
    fun of(file: File): ByteArray = (MAGIC + token + "\n" + file.absolutePath + "\n" + file.length()).toByteArray(Charsets.UTF_8)

    /** The file [data] refers to; null where it is no reference of this
     *  process's, or the file is gone or no longer the one referred to. */
    fun fileOf(data: ByteArray, offset: Int, length: Int): File? {
        if (length <= MAGIC.length || length > MAX_SIZE) return null
        for (i in MAGIC.indices) if (data[offset + i] != MAGIC[i].code.toByte()) return null
        val lines = String(data, offset + MAGIC.length, length - MAGIC.length, Charsets.UTF_8).split('\n')
        if (lines.size != 3 || lines[0] != token) return null
        val file = File(lines[1])
        return file.takeIf { it.isFile && it.length() == lines[2].toLongOrNull() }
    }
}

/**
 * The .sup sidecars the players fetched, in [dir]: each file kept while a
 * player that fetched it lives ([Session]), and of those no player holds the
 * last [keep] - for the next player, a quality switch's - the others
 * deleted, as are files an earlier run of the app left beyond those.
 */
internal class SupSidecarCache(private val dir: File, private val keep: Int = KEEP) {
    private val held = HashMap<File, Int>()

    // Files no player holds, the oldest first.
    private val loose = LinkedHashSet<File>()
    private val fetching = HashMap<File, Any>()
    private var tidied = false

    /** A player's hold on the files it fetches: let go with [release]. */
    fun session(): Session = Session()

    inner class Session internal constructor() {
        private val files = HashSet<File>()
        private var released = false

        /** The file of [dataSpec]'s .sup sidecar, fetched through [upstream]
         *  where the cache has none. Blocks while it downloads: a loading
         *  thread's call. */
        fun fetch(dataSpec: DataSpec, upstream: DataSource): File {
            val file = File(dir, nameOf(dataSpec.uri))
            synchronized(lockOf(file)) {
                tidy()
                if (!file.isFile) download(dataSpec, upstream, file)
                synchronized(this@SupSidecarCache) {
                    if (released) {
                        letGo(file)
                    } else if (files.add(file)) {
                        held[file] = (held[file] ?: 0) + 1
                        loose.remove(file)
                    }
                }
            }
            return file
        }

        /** Lets go of the files: the player is released. */
        fun release() = synchronized(this@SupSidecarCache) {
            if (released) return@synchronized
            released = true
            for (file in files) {
                val count = (held[file] ?: 1) - 1
                if (count > 0) held[file] = count else letGo(file)
            }
            files.clear()
            trim()
        }
    }

    // Called holding the cache's lock.
    private fun letGo(file: File) {
        held.remove(file)
        loose.remove(file)
        loose.add(file)
        trim()
    }

    // Called holding the cache's lock.
    private fun trim() {
        while (loose.size > keep) {
            val oldest = loose.first()
            loose.remove(oldest)
            oldest.delete()
        }
    }

    private fun lockOf(file: File): Any = synchronized(this) { fetching.getOrPut(file) { Any() } }

    /** Once, before the first fetch: a download an earlier run left
     *  unfinished goes, and of its files the newest [keep]. */
    private fun tidy() = synchronized(this) {
        if (tidied) return@synchronized
        tidied = true
        val found = dir.listFiles().orEmpty()
        found.filter { it.name.endsWith(PART) }.forEach { it.delete() }
        found.filter { it.name.endsWith(SUP) && it !in held }
            .sortedBy { it.lastModified() }
            .forEach { loose.add(it) }
        trim()
    }

    private fun download(dataSpec: DataSpec, upstream: DataSource, file: File) {
        dir.mkdirs()
        val part = File(dir, "${file.name}.${UUID.randomUUID()}$PART")
        try {
            val expected = upstream.open(dataSpec)
            var written = 0L
            FileOutputStream(part).use { out ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    if (Thread.interrupted()) throw InterruptedIOException("A .sup sidecar's download was cancelled")
                    val read = upstream.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    out.write(buffer, 0, read)
                    written += read
                }
            }
            if (expected != C.LENGTH_UNSET.toLong() && written != expected) {
                throw IOException("A .sup sidecar ended after $written of its $expected bytes")
            }
            if (!part.renameTo(file)) throw IOException("A .sup sidecar could not be kept in ${dir.name}")
        } finally {
            try {
                upstream.close()
            } finally {
                part.delete()
            }
        }
    }

    companion object {
        /** How many files no player holds are kept. */
        const val KEEP = 2

        private const val SUP = ".sup"
        private const val PART = ".part"
        private const val BUFFER = 64 * 1024

        @Volatile private var shared: SupSidecarCache? = null

        /** The app's cache of .sup sidecars, in its cache dir. */
        fun of(context: Context): SupSidecarCache =
            shared ?: synchronized(this) {
                shared ?: SupSidecarCache(File(context.applicationContext.cacheDir, "pgs-sidecars")).also { shared = it }
            }

        /** A file's name for a sidecar's address: its hash, the address (and
         *  the stream token on it) never on disk. */
        internal fun nameOf(uri: Uri): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(32) + SUP
        }
    }
}

/**
 * A data source that hands Media3, for each .sup sidecar ([isSup]), the
 * reference to its file on disk ([SupFileRef]) rather than its bytes: fetched
 * through [upstream] into [files]' cache, or, where the address is a file
 * already ("file:"), that file where it is. Every other request goes to
 * [upstream] as it comes.
 */
internal class SupSidecarDataSource(
    private val upstream: DataSource,
    private val files: SupSidecarCache.Session,
    private val isSup: (Uri) -> Boolean,
) : DataSource {
    private var body: ByteArray? = null
    private var readPosition = 0
    private var bodyUri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        if (!isSup(dataSpec.uri)) return upstream.open(dataSpec)
        val local = dataSpec.uri.takeIf { it.scheme == "file" }?.path?.let(::File)?.takeIf { it.isFile }
        val file = local ?: files.fetch(dataSpec, upstream)
        val ref = SupFileRef.of(file)
        body = ref
        readPosition = 0
        bodyUri = dataSpec.uri
        return ref.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val b = body ?: return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (readPosition >= b.size) return C.RESULT_END_OF_INPUT
        val n = minOf(length, b.size - readPosition)
        b.copyInto(buffer, offset, readPosition, readPosition + n)
        readPosition += n
        return n
    }

    override fun getUri(): Uri? = if (body != null) bodyUri else upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = if (body != null) emptyMap() else upstream.responseHeaders

    override fun close() {
        if (body != null) {
            body = null
            bodyUri = null
        } else {
            upstream.close()
        }
    }

    /** [isSup]: the addresses of the .sup sidecars; every one by default. */
    class Factory(
        private val upstream: DataSource.Factory,
        private val files: SupSidecarCache.Session,
        private val isSup: (Uri) -> Boolean = { true },
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SupSidecarDataSource(upstream.createDataSource(), files, isSup)
    }
}
