package cloud.nalet.chino.mobile.data.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import platform.Foundation.NSUserDefaults

/**
 * NSUserDefaults-backed token store on iOS.
 *
 * NSUserDefaults is plaintext but per-app sandboxed — same trust level as the
 * Android DataStore implementation. README flags the move to Keychain (and to
 * EncryptedSharedPreferences on Android) as a pre-prod hardening item. Keeping
 * both platforms at the same security level for v0 avoids surprises.
 */
actual class PlatformTokenStoreFactory(private val suiteName: String) {
    actual fun create(): TokenStore = IosTokenStore(NSUserDefaults(suiteName = suiteName))
}

private const val KEY_ACCESS = "access_token"
private const val KEY_REFRESH = "refresh_token"
private const val KEY_EXPIRES = "expires_at_epoch_millis"

private class IosTokenStore(private val defaults: NSUserDefaults) : TokenStore {
    private val _tokens = MutableStateFlow(readSnapshot())
    override val tokens: Flow<Tokens?> = _tokens.asStateFlow()

    override suspend fun save(tokens: Tokens) {
        defaults.setObject(tokens.accessToken, KEY_ACCESS)
        defaults.setObject(tokens.refreshToken, KEY_REFRESH)
        defaults.setObject(tokens.expiresAtEpochMillis.toString(), KEY_EXPIRES)
        _tokens.value = tokens
    }

    override suspend fun clear() {
        defaults.removeObjectForKey(KEY_ACCESS)
        defaults.removeObjectForKey(KEY_REFRESH)
        defaults.removeObjectForKey(KEY_EXPIRES)
        _tokens.value = null
    }

    override suspend fun current(): Tokens? = tokens.first()

    private fun readSnapshot(): Tokens? {
        val access = defaults.stringForKey(KEY_ACCESS) ?: return null
        return Tokens(
            accessToken = access,
            refreshToken = defaults.stringForKey(KEY_REFRESH),
            expiresAtEpochMillis = defaults.stringForKey(KEY_EXPIRES)?.toLongOrNull() ?: 0L,
        )
    }
}
