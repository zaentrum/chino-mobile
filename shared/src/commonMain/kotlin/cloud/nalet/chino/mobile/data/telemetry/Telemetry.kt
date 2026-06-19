package cloud.nalet.chino.mobile.data.telemetry

import cloud.nalet.chino.mobile.currentTimeMillis
import cloud.nalet.chino.mobile.data.api.ChinoApi
import cloud.nalet.chino.mobile.data.api.TelemetryBatch
import cloud.nalet.chino.mobile.data.api.TelemetryEvent
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One observability funnel for the whole app. Every event is auto-stamped
 * with the static device/app context the host supplies plus the active
 * account id so the cluster log aggregator can slice without each call site
 * re-passing the same fields.
 *
 * Mirrors chino-androidtv's Telemetry but accepts the static context as a
 * Map argument (rather than reading Build.MODEL directly) so the same code
 * compiles for iOS where UIDevice is the source of truth. ChinoMobileApp
 * (Android) and the iOS host both build the map and inject it via
 * AppContainer.
 */
class Telemetry(
    private val api: ChinoApi,
    private val staticContext: Map<String, String>,
    private val appScope: CoroutineScope,
) {
    /** Random-derived per-process session id. Not RFC 4122 — kotlinx UUID
     *  isn't stable across all KMP targets yet — but the cluster aggregator
     *  treats this purely as an opaque grouping key. */
    val sessionId: String = buildString {
        val bytes = ByteArray(16).also { Random.Default.nextBytes(it) }
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 16) append('0')
            append(v.toString(16))
        }
    }
    private val sessionStartMs: Long = currentTimeMillis()

    /** Active account id at the moment an event fires. AppContainer wires
     *  this from AccountStore.activeAccountId so cross-account switches
     *  inside the same process are reflected in every subsequent event. */
    @Volatile private var accountIdSnapshot: String? = null
    fun setActiveAccount(id: String?) { accountIdSnapshot = id }

    /** Fire an event. Doesn't block — schedules a fire-and-forget POST that
     *  swallows failures (chino-api's /v1/play/events is best-effort
     *  logging). */
    fun event(kind: String, itemId: String? = null, extra: Map<String, String> = emptyMap()) {
        val payload = buildMap<String, String> {
            putAll(staticContext)
            put("session_uptime_ms", (currentTimeMillis() - sessionStartMs).toString())
            accountIdSnapshot?.let { put("account_id", it) }
            putAll(extra)
        }
        val ev = TelemetryEvent(
            ts = currentTimeMillis(),
            kind = kind,
            itemId = itemId,
            payload = payload,
        )
        appScope.launch {
            runCatching {
                api.postTelemetry(TelemetryBatch(sessionId = sessionId, events = listOf(ev)))
            }
        }
    }
}
