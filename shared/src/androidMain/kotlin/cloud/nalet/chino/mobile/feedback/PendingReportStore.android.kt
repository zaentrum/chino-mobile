package cloud.nalet.chino.mobile.feedback

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Crash-report queue backed by `<filesDir>/bug_reports/<millis>.json`. The
 * androidApp Application owns one instance: its uncaught-exception handler
 * calls [writeCrashSync] while the process is dying (synchronous on purpose
 * — there is no later), and the same instance is handed to AppContainer so
 * BugReporter.flushPending can drain the files on the next signed-in launch.
 */
class FilePendingReportStore(private val dir: File) : PendingReportStore {

    /**
     * Persist a crash report SYNCHRONOUSLY — called from the uncaught-
     * exception handler right before the previous handler kills the process.
     * Keeps at most [MAX_PENDING] files (oldest dropped) so a crash loop
     * can't grow the directory unbounded. Swallows everything: a failing
     * crash writer must never mask the original crash.
     */
    fun writeCrashSync(description: String, fingerprint: String, context: Map<String, String>) {
        try {
            dir.mkdirs()
            val report = PendingReport(
                kind = "crash",
                description = description,
                fingerprint = fingerprint,
                context = context,
            )
            File(dir, "${System.currentTimeMillis()}.json")
                .writeText(pendingReportJson.encodeToString(PendingReport.serializer(), report))
            val files = jsonFiles()
            if (files.size > MAX_PENDING) {
                files.take(files.size - MAX_PENDING).forEach { it.delete() }
            }
        } catch (_: Throwable) {
            // Dying process — nothing sane to do with a failed write.
        }
    }

    override suspend fun list(): List<PendingReportFile> = withContext(Dispatchers.IO) {
        jsonFiles().mapNotNull { f ->
            runCatching { PendingReportFile(name = f.name, content = f.readText()) }.getOrNull()
        }
    }

    override suspend fun delete(name: String) {
        withContext(Dispatchers.IO) { File(dir, name).delete() }
    }

    /** Millis-named files sort lexicographically = chronologically. */
    private fun jsonFiles(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?: emptyList()

    companion object {
        private const val MAX_PENDING = 5
    }
}
