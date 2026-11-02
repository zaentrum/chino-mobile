package cloud.nalet.chino.mobile.ui.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.FileNotFoundException
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
 * first ([SupSidecarCache]) and its index read there too, on the loading
 * thread; SupParser reads the file from there (the index, an object's bytes
 * as its set is drawn, SupSubtitles.kt). A file kept is the player's for as
 * long as it lives; the last ones it let go stay for the next player (a
 * quality switch, the same title again), the rest are deleted.
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
 * A .sup sidecar the cache could not keep: no room for it, or a cache dir
 * that can't be written. A [FileNotFoundException], which Media3's load
 * error policies (DefaultLoadErrorHandlingPolicy and those built on it) take
 * as final: the subtitle ends there, never retried from its first byte, and
 * the playback goes on (Media3 ends a side-loaded subtitle's stream on a
 * load error).
 */
internal class SupNotKeptException(message: String, cause: Throwable? = null) : FileNotFoundException(message) {
    init {
        cause?.let(::initCause)
    }
}

/**
 * The .sup sidecars the players fetched, in [dir]: each file kept while a
 * player that fetched it lives ([Session]), and of those no player holds the
 * last [keep] - for the next player, a quality switch's - the others
 * deleted. Files an earlier run of the app left are deleted all: their names
 * come from addresses with that run's stream tokens on them, never asked
 * for again. [freeSpace] is the room left where [dir] is.
 */
internal class SupSidecarCache(
    private val dir: File,
    private val keep: Int = KEEP,
    private val freeSpace: (File) -> Long = { it.usableSpace },
) {
    // How many players hold each file; a file with none is loose.
    private val held = HashMap<File, Int>()

    // Files no player holds, the oldest first.
    private val loose = LinkedHashSet<File>()

    // A file's download, one at a time: a few locks, shared by name.
    private val fetching = Array(LOCKS) { Any() }
    private var tidied = false

    /** A player's hold on the files it fetches: let go with [release]. */
    fun session(): Session = Session()

    inner class Session internal constructor() {
        private val files = HashSet<File>()
        private var released = false

        /** The file of [dataSpec]'s .sup sidecar, fetched through [upstream]
         *  where the cache has none - held before it is looked for, so no
         *  other player's letting go deletes it. Blocks while it downloads:
         *  a loading thread's call. */
        fun fetch(dataSpec: DataSpec, upstream: DataSource): File {
            val file = File(dir, nameOf(dataSpec.uri))
            synchronized(fetching[(file.name.hashCode() and Int.MAX_VALUE) % LOCKS]) {
                tidy()
                if (!hold(file)) throw InterruptedIOException("The player let go of its sidecars")
                try {
                    if (!file.isFile) download(dataSpec, upstream, file)
                } catch (e: Throwable) {
                    unhold(file)
                    throw e
                }
                // Released while it downloaded: the file, on disk now, is loose.
                synchronized(this@SupSidecarCache) { if (released && held[file] == null) letGo(file) }
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

        // This session's hold on [file]: false once it is released.
        private fun hold(file: File): Boolean = synchronized(this@SupSidecarCache) {
            if (released) return@synchronized false
            if (files.add(file)) {
                held[file] = (held[file] ?: 0) + 1
                loose.remove(file)
            }
            true
        }

        // A hold given back: the fetch failed.
        private fun unhold(file: File) = synchronized(this@SupSidecarCache) {
            if (!files.remove(file)) return@synchronized
            val count = (held[file] ?: 1) - 1
            if (count > 0) {
                held[file] = count
            } else if (file.isFile) {
                letGo(file)
            } else {
                held.remove(file)
            }
        }
    }

    // Called holding the cache's lock: nobody holds [file].
    private fun letGo(file: File) {
        held.remove(file)
        loose.remove(file)
        loose.add(file)
        trim()
    }

    // Called holding the cache's lock. Only loose files go: a held one is
    // never in [loose].
    private fun trim() {
        while (loose.size > keep) {
            val oldest = loose.first()
            loose.remove(oldest)
            oldest.delete()
        }
    }

    /** Once, before the first fetch: what an earlier run left goes. */
    private fun tidy() = synchronized(this) {
        if (tidied) return@synchronized
        tidied = true
        dir.listFiles().orEmpty()
            .filter { (it.name.endsWith(PART) || it.name.endsWith(SUP)) && it !in held }
            .forEach { it.delete() }
    }

    private fun download(dataSpec: DataSpec, upstream: DataSource, file: File) {
        if (!dir.isDirectory && !dir.mkdirs()) throw SupNotKeptException("A .sup sidecar can't be kept: ${dir.name} can't be made")
        val part = File(dir, "${file.name}.${UUID.randomUUID()}$PART")
        try {
            val expected = upstream.open(dataSpec)
            if (expected != C.LENGTH_UNSET.toLong() && freeSpace(dir) < expected + ROOM_LEFT) {
                throw SupNotKeptException("No room for a .sup sidecar of $expected bytes in ${dir.name}")
            }
            var written = 0L
            val out = try {
                FileOutputStream(part)
            } catch (e: IOException) {
                throw SupNotKeptException("A .sup sidecar can't be kept in ${dir.name}", e)
            }
            out.use {
                val buffer = ByteArray(BUFFER)
                while (true) {
                    if (Thread.interrupted()) throw InterruptedIOException("A .sup sidecar's download was cancelled")
                    val read = upstream.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    try {
                        it.write(buffer, 0, read)
                    } catch (e: IOException) {
                        // No room left, the storage gone: no retry would fare better.
                        throw SupNotKeptException("A .sup sidecar can't be kept in ${dir.name}", e)
                    }
                    written += read
                }
            }
            if (expected != C.LENGTH_UNSET.toLong() && written != expected) {
                throw IOException("A .sup sidecar ended after $written of its $expected bytes")
            }
            if (!part.renameTo(file)) throw SupNotKeptException("A .sup sidecar could not be kept in ${dir.name}")
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

        /** The room a download leaves where the cache is, at the least. */
        const val ROOM_LEFT = 32L * 1024 * 1024

        private const val SUP = ".sup"
        private const val PART = ".part"
        private const val BUFFER = 64 * 1024
        private const val LOCKS = 16

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
 * already ("file:"), that file where it is - its index read here, on the
 * loading thread, so the text renderer's decoder only picks it up. Every
 * other request goes to [upstream] as it comes.
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
        try {
            SupSource.OnDisk(file).displaySets()
        } catch (e: IOException) {
            // The decoder reads it then, or shows nothing.
            Log.w(TAG, "A .sup sidecar's index can't be read yet", e)
        }
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

    private companion object {
        const val TAG = "SupSidecarDataSource"
    }
}
