package cloud.nalet.chino.mobile.data.auth

import cloud.nalet.chino.mobile.currentTimeMillis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

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
    /** Whether [accountId]'s token may be renewed: not while the account,
     *  which the server answered deleted, is on its way off this device. */
    private val mayRenew: (accountId: String) -> Boolean = { true },
) {
    private val mutex = Mutex()

    /** The tokens renewed here, the last [KEPT_RENEWED], each with the
     *  account it was for ([accountOf]). Written under [mutex]. */
    @Volatile
    private var renewedFrom: Map<String, String> = emptyMap()

    /** The account on this device whose token [token] is, or was until it
     *  was renewed here: an answer to a request sent with a token renewed
     *  meanwhile still names its account. Null for none. */
    fun accountOf(token: String): String? =
        accounts.snapshotBlocking().accounts.firstOrNull { it.accessToken == token }?.id ?: renewedFrom[token]

    /** Returns a token guaranteed valid for at least [REFRESH_SLACK_MS]
     *  longer, refreshing under a mutex if the cached one is too close to
     *  expiry. Null when no account is active or no refresh token is
     *  available — caller falls back to picker / AUTH. An account that may
     *  not be renewed ([mayRenew]) has the token it holds. */
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
            if (!mayRenew(target.id)) return@withLock target.accessToken
            renew(target)
        }
    }

    /** [accountId]'s own token, valid for at least [REFRESH_SLACK_MS] longer
     *  - renewed under the mutex where it must be - whoever is active: never
     *  another account's. Null where the account isn't on this device, has
     *  no refresh token, may not be renewed ([mayRenew]), or its renewal was
     *  refused; a renewal that got no
     *  answer throws, as [validAccessToken]'s does. */
    suspend fun validAccessTokenOf(accountId: String): String? {
        val current = accounts.accounts.first().firstOrNull { it.id == accountId } ?: return null
        if (currentTimeMillis() < current.expiresAtEpochMillis - REFRESH_SLACK_MS) {
            return current.accessToken
        }
        return mutex.withLock {
            val target = accounts.accounts.first().firstOrNull { it.id == accountId } ?: return@withLock null
            if (currentTimeMillis() < target.expiresAtEpochMillis - REFRESH_SLACK_MS) {
                return@withLock target.accessToken
            }
            renew(target)
        }
    }

    /** Called by the Ktor Auth plugin on a 401. Always attempts a refresh
     *  even if the cached token looks fresh — server-side revocation can
     *  invalidate a not-yet-expired token. Returns null when there's
     *  nothing to do (no refresh token, refresh failed, or the account may
     *  not be renewed). */
    suspend fun forceRefresh(): String? = mutex.withLock {
        val latest = accounts.activeAccount.first() ?: return@withLock null
        renew(latest)
    }

    /** [target]'s token renewed and kept, under [mutex]; the one it replaces
     *  is remembered with its account ([accountOf]). Null where it may not be
     *  renewed ([mayRenew]), has no refresh token, or the renewal was
     *  refused. */
    private suspend fun renew(target: Account): String? {
        if (!mayRenew(target.id)) return null
        val rt = target.refreshToken ?: return null
        val refreshed = oidc.refresh(rt) ?: return null
        renewedFrom = (renewedFrom - target.accessToken + (target.accessToken to target.id))
            .entries.toList().takeLast(KEPT_RENEWED).associate { it.key to it.value }
        accounts.updateTokensForBlocking(target.id, refreshed)
        return refreshed.accessToken
    }

    companion object {
        // Refresh once we're within 60 s of expiry. Keycloak access tokens
        // default to 5 min so this is a comfortable margin.
        private const val REFRESH_SLACK_MS = 60_000L

        /** How many renewed tokens [accountOf] still knows: those of the
         *  requests that can be on their way as a token is renewed. */
        private const val KEPT_RENEWED = 16
    }
}
