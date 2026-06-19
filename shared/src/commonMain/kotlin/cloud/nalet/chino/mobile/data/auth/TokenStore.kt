package cloud.nalet.chino.mobile.data.auth

import kotlinx.coroutines.flow.Flow

/**
 * Per-platform implementation persists tokens to:
 *   Android — DataStore Preferences (plaintext, sandboxed by UID; flagged for
 *             EncryptedSharedPreferences upgrade before non-beta).
 *   iOS     — Keychain (kSecAttrAccessibleAfterFirstUnlock).
 *
 * The interface is intentionally narrow so each platform can pick the right
 * storage primitive without leaking it into commonMain.
 */
interface TokenStore {
    val tokens: Flow<Tokens?>
    suspend fun save(tokens: Tokens)
    suspend fun clear()

    /**
     * Synchronous read for Ktor's `Auth` plugin loadTokens callback, which is itself
     * a suspending callback — implementations should expose `tokens.first()` here.
     */
    suspend fun current(): Tokens?
}
