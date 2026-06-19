package cloud.nalet.chino.mobile.data.auth

import cloud.nalet.chino.mobile.currentTimeMillis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the access/refresh-token lifecycle for the currently active account.
 *
 * Ktor's `Auth` plugin loadTokens / refreshTokens callbacks are themselves
 * suspending, so the API surface here is suspending too — no platform-
 * specific runBlocking like chino-androidtv's TokenManager needs to bridge
 * the OkHttp synchronous Interceptor / Authenticator contract.
 *
 * When the user switches accounts via the picker, [AccountStore.setActive]
 * flips the active id and subsequent calls here naturally read tokens for
 * the new account — no rewiring of the HttpClient needed.
 */
class TokenManager(
    private val accounts: AccountStore,
    private val oidc: OidcDeviceClient,
) {
    private val mutex = Mutex()

    /** Returns a token guaranteed valid for at least [REFRESH_SLACK_MS]
     *  longer, refreshing under a mutex if the cached one is too close to
     *  expiry. Null when no account is active or no refresh token is
     *  available — caller falls back to picker / AUTH. */
    suspend fun validAccessToken(): String? {
        val current = accounts.activeAccount.first() ?: return null
        if (currentTimeMillis() < current.expiresAtEpochMillis - REFRESH_SLACK_MS) {
            return current.accessToken
        }
        // Bind the refresh to THIS account's id — if the user switches
        // accounts via the picker between this read and the write below,
        // we still update the right row.
        val refreshingId = current.id
        return mutex.withLock {
            val latest = accounts.activeAccount.first()
            val target = latest?.takeIf { it.id == refreshingId } ?: latest ?: return@withLock null
            if (currentTimeMillis() < target.expiresAtEpochMillis - REFRESH_SLACK_MS) {
                return@withLock target.accessToken
            }
            val rt = target.refreshToken ?: return@withLock null
            val refreshed = oidc.refresh(rt) ?: return@withLock null
            accounts.updateTokensForBlocking(target.id, refreshed)
            refreshed.accessToken
        }
    }

    /** Called by the Ktor Auth plugin on a 401. Always attempts a refresh
     *  even if the cached token looks fresh — server-side revocation can
     *  invalidate a not-yet-expired token. Returns null when there's
     *  nothing to do (no refresh token, or refresh failed). */
    suspend fun forceRefresh(): String? = mutex.withLock {
        val latest = accounts.activeAccount.first() ?: return@withLock null
        val rt = latest.refreshToken ?: return@withLock null
        val refreshed = oidc.refresh(rt) ?: return@withLock null
        accounts.updateTokensForBlocking(latest.id, refreshed)
        refreshed.accessToken
    }

    companion object {
        // Refresh once we're within 60 s of expiry. Keycloak access tokens
        // default to 5 min so this is a comfortable margin.
        private const val REFRESH_SLACK_MS = 60_000L
    }
}
