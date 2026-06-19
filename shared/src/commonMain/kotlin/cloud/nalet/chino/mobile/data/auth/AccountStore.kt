package cloud.nalet.chino.mobile.data.auth

import kotlinx.coroutines.flow.Flow

/**
 * Multi-account replacement for [TokenStore]. Persists a list of [Account]
 * records + the active account id, all in a single JSON blob.
 *
 * Mirrors chino-androidtv's AccountStore semantics so the two clients pick
 * the same start-destination heuristic ("zero accounts → auth, ≥2 accounts
 * → picker, 1 healthy account → library"). The interface is platform-
 * agnostic — concrete storage is per-platform:
 *
 *   Android — DataStore Preferences (single string entry, plaintext).
 *   iOS     — NSUserDefaults (single string entry, plaintext).
 *
 * Both are app-sandboxed and match the existing single-token TokenStore's
 * security level; pre-prod hardening to EncryptedSharedPreferences (Android)
 * + Keychain (iOS) is tracked in the shared module README.
 */
interface AccountStore {
    /** All known accounts, sorted by lastUsedAt DESC (most-recent first). */
    val accounts: Flow<List<Account>>
    val activeAccountId: Flow<String?>
    val activeAccount: Flow<Account?>

    /** Append a new account or update tokens of an existing one (matched by id).
     *  Pass [setActive] = true to make it the picker default after add. */
    suspend fun addOrUpdate(account: Account, setActive: Boolean = false)

    suspend fun remove(id: String)

    suspend fun setActive(id: String)

    /** Synchronous snapshot of the current state — used by the navigator on
     *  its very first composition to pick the right start destination
     *  without flickering through the auth screen for one frame. */
    fun snapshotBlocking(): Snapshot

    data class Snapshot(val accounts: List<Account>, val activeAccount: Account?)

    /** Ktor `Auth` plugin loadTokens hook — runs on dispatcher threads,
     *  blocking IO on the storage is fine here. */
    fun currentAccessTokenBlocking(): String?

    /** Replaces just the token fields on the named account; preserves
     *  displayName / email / id. Called by the token refresher after a
     *  silent renew — scoped by accountId rather than "active" because the
     *  user could have switched accounts in the picker mid-refresh, and a
     *  blind "write to active" would corrupt the new account's tokens with
     *  the old account's refreshed values. */
    suspend fun updateTokensForBlocking(accountId: String, tokens: Tokens)

    /** One-shot import from the legacy single-token [TokenStore] on first
     *  launch after the multi-account roll-out. Creates a single account
     *  with a synthetic id ("legacy") and the existing tokens; the next
     *  userinfo round-trip replaces the synthetic id with the real
     *  Keycloak sub. Idempotent: a second call with an empty source store
     *  is a no-op. */
    suspend fun migrateFromTokenStoreIfPresent(legacy: TokenStore)
}

/** Per-platform factory — Android gives us a Context, iOS gives us a suite
 *  name (mirrors PlatformTokenStoreFactory's split). The host app wires the
 *  right constructor at startup. */
expect class PlatformAccountStoreFactory {
    fun create(): AccountStore
}
