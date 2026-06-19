package cloud.nalet.chino.mobile.data.auth

import cloud.nalet.chino.mobile.currentTimeMillis
import cloud.nalet.chino.mobile.data.api.ChinoApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Caches the chino-api-minted `?stream=` HMAC token across all consumers
 * (poster URLs, backdrop URLs, the video player). chino-api signs them with
 * TTL ~6 h so we mint once at app start and re-mint a few minutes before
 * expiry.
 *
 * Mirrors chino-androidtv's StreamTokenManager but exposes a suspending
 * [valid()] instead of a blocking one — Ktor's flow doesn't need the sync
 * helper that OkHttp's interceptor contract forces on us in TV.
 *
 * Token rotation on user switch is implicit: switching the active account in
 * [AccountStore.setActive] doesn't tear this manager down, but the next
 * mintStreamToken call will use the new user's bearer (the TokenManager's
 * Auth plugin reads from the same AccountStore). Stale tokens for the
 * previous user expire on the 6 h TTL — pre-prod follow-up is to clear
 * [_current] explicitly on account switch so the next valid() call mints
 * fresh.
 */
class StreamTokenManager(private val api: ChinoApi) {
    private val mutex = Mutex()
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current.asStateFlow()
    private var expiresAtEpochMillis: Long = 0L

    /** Returns a valid stream token, minting if expired or missing. Callers
     *  are coroutine-scoped (the player VM, Coil's interceptor on a worker
     *  pool, etc.) so suspending is fine here. */
    suspend fun valid(): String {
        val now = currentTimeMillis()
        _current.value?.takeIf { now < expiresAtEpochMillis - SLACK_MS }?.let { return it }
        return mutex.withLock {
            val now2 = currentTimeMillis()
            _current.value?.takeIf { now2 < expiresAtEpochMillis - SLACK_MS }?.let { return@withLock it }
            val resp = api.mintStreamToken()
            // Server may not stamp expires_at — default to a 6 h TTL so we
            // re-mint at the same cadence chino-api uses internally.
            val computedExpiry = currentTimeMillis() + 6L * 60 * 60 * 1000
            expiresAtEpochMillis = computedExpiry
            _current.value = resp.token
            resp.token
        }
    }

    /** Clear the cached token. Call on active-account switch so the next
     *  poster/player URL gets a freshly-minted token bound to the new user
     *  rather than the previous one. */
    fun invalidate() {
        _current.value = null
        expiresAtEpochMillis = 0L
    }

    companion object {
        private const val SLACK_MS = 5L * 60 * 1000 // refresh 5 min before expiry
    }
}
