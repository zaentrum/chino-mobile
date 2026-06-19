package cloud.nalet.chino.mobile.feedback

import cloud.nalet.chino.mobile.data.api.ChinoApi
import cloud.nalet.chino.mobile.data.api.FeedbackReport
import cloud.nalet.chino.mobile.data.api.FeedbackResponse
import cloud.nalet.chino.mobile.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One funnel for bug reports — POST /v1/feedback on chino-api, which opens
 * (or dedup-appends to) a ticket on the connected server's issue system. Mirrors [cloud.nalet.chino.mobile.data.telemetry.Telemetry]
 * in shape: held by AppContainer, stamps the host-supplied static device/app
 * context onto every report so call sites only pass what's specific to them.
 *
 * Two paths with opposite failure semantics:
 *  - [report] (auto: error/crash/player) is fire-and-forget and swallows ALL
 *    failures — an auto report must never crash, toast, or otherwise surface.
 *    Session-throttled: a fingerprint is sent at most once per process, hard
 *    cap [MAX_AUTO_PER_SESSION] auto reports per process (the server rate
 *    limits per user on top of this).
 *  - [reportManual] (the Settings dialog) is a plain suspend call that
 *    PROPAGATES errors so the UI can show an inline failure state.
 */
class BugReporter(
    private val api: ChinoApi,
    /** Static device/app fields the host supplies (same map Telemetry gets) —
     *  device model, OS version, app version, flavor, client. */
    private val staticContext: Map<String, String>,
    /** Crash-report files the host's uncaught-exception handler wrote on a
     *  previous process's way down; [flushPending] drains them once signed
     *  in. Null on hosts without a crash handler (iOS for now). */
    private val pendingStore: PendingReportStore? = null,
) {
    /** Own supervisor scope (not appScope) so a failed auto report can never
     *  cancel siblings and reports outlive any screen-model's onCleared. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val throttle = Mutex()
    private val sentFingerprints = mutableSetOf<String>()
    private var autoSentCount = 0
    private var flushedPending = false

    /**
     * Auto report (kind = "error" | "crash" | "player"). Launched on the
     * internal scope; every failure (network, 401 pre-login, 429 rate limit,
     * 5xx) is swallowed silently. Screenshots over the server's 3 MB cap are
     * dropped (report still goes out) rather than letting the whole submit 413.
     */
    fun report(
        kind: String,
        title: String? = null,
        description: String,
        fingerprint: String? = null,
        context: Map<String, String> = emptyMap(),
        screenshot: ByteArray? = null,
    ) {
        scope.launch {
            runCatching {
                val fp = fingerprint?.takeIf { it.isNotBlank() }
                val allowed = throttle.withLock {
                    when {
                        autoSentCount >= MAX_AUTO_PER_SESSION -> false
                        fp != null && fp in sentFingerprints -> false
                        else -> {
                            fp?.let { sentFingerprints.add(it) }
                            autoSentCount += 1
                            true
                        }
                    }
                }
                if (!allowed) return@launch
                api.submitFeedback(
                    report = FeedbackReport(
                        source = SOURCE,
                        kind = kind,
                        title = title,
                        description = description,
                        fingerprint = fp,
                        context = staticContext + context,
                    ),
                    screenshot = screenshot?.takeIf { it.size <= MAX_SCREENSHOT_BYTES },
                )
            }
        }
    }

    /**
     * Manual report from the Settings "Report a bug" dialog. Returns the
     * server's response (id + url + duplicate flag) and PROPAGATES failures
     * (incl. [cloud.nalet.chino.mobile.data.api.FeedbackSubmitException])
     * so the dialog can show an inline error and preserve the input. Not
     * session-throttled — the server's per-user rate limit is the backstop.
     */
    suspend fun reportManual(
        description: String,
        screenshot: ByteArray? = null,
        context: Map<String, String> = emptyMap(),
    ): FeedbackResponse = api.submitFeedback(
        report = FeedbackReport(
            source = SOURCE,
            kind = "manual",
            description = description,
            context = staticContext + context,
        ),
        screenshot = screenshot?.takeIf { it.size <= MAX_SCREENSHOT_BYTES },
    )

    /**
     * Drain crash reports a previous process wrote on its way down. Each file
     * is submitted (no screenshot), deleted on success, and KEPT on failure
     * (e.g. 401 because the crash predated sign-in) for the next launch.
     * Corrupt files are dropped. Once per process — call sites can fire it on
     * every shell mount without re-walking the directory.
     */
    suspend fun flushPending() {
        val store = pendingStore ?: return
        throttle.withLock {
            if (flushedPending) return
            flushedPending = true
        }
        val files = runCatching { store.list() }.getOrElse { return }
        for (file in files) {
            val pending = runCatching {
                pendingReportJson.decodeFromString(PendingReport.serializer(), file.content)
            }.getOrNull()
            if (pending == null) {
                // Unreadable — drop it so it doesn't re-fail every launch.
                runCatching { store.delete(file.name) }
                continue
            }
            val sent = runCatching {
                api.submitFeedback(
                    report = FeedbackReport(
                        source = SOURCE,
                        kind = pending.kind,
                        title = pending.title,
                        description = pending.description,
                        fingerprint = pending.fingerprint?.takeIf { it.isNotBlank() },
                        context = pending.context,
                    ),
                )
            }.isSuccess
            if (sent) {
                runCatching { store.delete(file.name) }
                // Count flushed crashes against the session throttle so the
                // same signature can't also re-file via the live path.
                throttle.withLock {
                    pending.fingerprint?.takeIf { it.isNotBlank() }?.let { sentFingerprints.add(it) }
                }
            }
        }
    }

    companion object {
        private const val SOURCE = "mobile"
        private const val MAX_AUTO_PER_SESSION = 3
        /** Server-side multipart cap for the screenshot part. */
        private const val MAX_SCREENSHOT_BYTES = 3 * 1024 * 1024
    }
}

/**
 * Fingerprint of an error signature, shared in spirit with chino-web's
 * normalization (exact cross-platform equality is NOT required):
 * sha256Hex(errorClassOrName + "|" + message with digits/uuids stripped +
 * "|" + top 3 stack frames with line:column numbers and URL query strings
 * stripped). Stripping the volatile parts keeps "the same bug" hashing to
 * the same ticket across positions, ids and rebuilt URLs.
 */
fun bugFingerprint(name: String, message: String?, stack: String? = null): String {
    val normalizedMessage = (message ?: "")
        .replace(UUID_REGEX, "<uuid>")
        .replace(DIGITS_REGEX, "<n>")
    val frames = (stack ?: "")
        .lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("at ") }
        .take(3)
        .map { frame ->
            frame
                .replace(QUERY_REGEX, "")
                .replace(LINE_COL_REGEX, "")
        }
        .joinToString(";")
    return sha256Hex("$name|$normalizedMessage|$frames")
}

private val UUID_REGEX =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val DIGITS_REGEX = Regex("\\d+")
private val QUERY_REGEX = Regex("\\?[^\\s)]*")
private val LINE_COL_REGEX = Regex(":\\d+(:\\d+)?")

/**
 * Crash report persisted by the host's uncaught-exception handler — the
 * process is dying, so the report is written to disk synchronously and
 * submitted by [BugReporter.flushPending] on the NEXT authenticated launch.
 */
@Serializable
data class PendingReport(
    val kind: String,
    val title: String? = null,
    val description: String,
    val fingerprint: String? = null,
    val context: Map<String, String> = emptyMap(),
)

/** Shared by the writer (host crash handler) and reader (flushPending). */
internal val pendingReportJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/** Filesystem-backed queue of crash reports awaiting submission. Implemented
 *  per-platform (Android: filesDir/bug_reports JSON files); commonMain only
 *  needs list + delete — writing happens host-side at crash time. */
interface PendingReportStore {
    suspend fun list(): List<PendingReportFile>
    suspend fun delete(name: String)
}

class PendingReportFile(val name: String, val content: String)
