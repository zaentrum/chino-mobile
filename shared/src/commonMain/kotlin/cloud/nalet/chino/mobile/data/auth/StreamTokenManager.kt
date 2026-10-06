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
class StreamTokenManager(
    private val api: ChinoApi,
    /** The wall clock the token's life is told by - not a monotonic one,
     *  which stands still while the device sleeps. Tests pass their own. */
    private val now: () -> Long = { currentTimeMillis() },
) {
    private val mutex = Mutex()
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current.asStateFlow()
    private var expiresAtEpochMillis: Long = 0L

    /** Returns a valid stream token, minting if expired or missing. Callers
     *  are coroutine-scoped (the player VM, Coil's interceptor on a worker
     *  pool, etc.) so suspending is fine here. Asked where a link is used -
     *  an image fetched, a Zap card shown - a link that has lived for hours
     *  is signed with a token valid now, judged by the wall clock then. */
    suspend fun valid(): String = validFor(0L)

    /**
     * A token with [lifeMs] of its life left at least - for links built once
     * and used that long: a playback session's, which cannot sign its
     * segments again halfway through (forSession in ui/player). The current
     * token when it lives that long, else the next one, minted first. A
     * session longer than a token lives gets the freshest there is: one
     * minted within the last [SLACK_MS].
     */
    suspend fun validFor(lifeMs: Long): String {
        val need = lifeMs.coerceAtMost(TOKEN_LIFE_MS - SLACK_MS)
        lasting(need)?.let { return it }
        return mutex.withLock {
            lasting(need)?.let { return@withLock it }
            val resp = api.mintStreamToken()
            // chino-api's tokens live 6 h (streamTokenTTL). Counted from
            // when this one arrived, by this device's clock - the server's
            // expires_at is its own clock's.
            expiresAtEpochMillis = now() + TOKEN_LIFE_MS
            _current.value = resp.token
            resp.token
        }
    }

    /** The current token, when it has more than [SLACK_MS] of its life left
     *  now and [lifeMs] at least. Past [SLACK_MS] the next one is minted,
     *  while links still signed with this one keep working for those last
     *  minutes. */
    private fun lasting(lifeMs: Long): String? {
        val left = expiresAtEpochMillis - now()
        return _current.value?.takeIf { left > SLACK_MS && left >= lifeMs }
    }

    /** Clear the cached token. Call on active-account switch so the next
     *  poster/player URL gets a freshly-minted token bound to the new user
     *  rather than the previous one. */
    fun invalidate() {
        _current.value = null
        expiresAtEpochMillis = 0L
    }

    companion object {
        /** How long chino-api's stream tokens live (streamTokenTTL). */
        const val TOKEN_LIFE_MS = 6L * 60 * 60 * 1000
        /** Refresh this long before expiry. */
        const val SLACK_MS = 5L * 60 * 1000
    }
}
