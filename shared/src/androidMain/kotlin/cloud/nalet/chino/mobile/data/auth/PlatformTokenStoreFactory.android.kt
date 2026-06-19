package cloud.nalet.chino.mobile.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.tokenDataStore by preferencesDataStore(name = "chino_tokens")

actual class PlatformTokenStoreFactory(private val context: Context) {
    actual fun create(): TokenStore = AndroidTokenStore(context.applicationContext)
}

private class AndroidTokenStore(private val context: Context) : TokenStore {
    private val accessKey = stringPreferencesKey("access_token")
    private val refreshKey = stringPreferencesKey("refresh_token")
    private val expiresAtKey = stringPreferencesKey("expires_at_epoch_millis")

    override val tokens: Flow<Tokens?> = context.tokenDataStore.data.map { prefs ->
        val access = prefs[accessKey] ?: return@map null
        Tokens(
            accessToken = access,
            refreshToken = prefs[refreshKey],
            expiresAtEpochMillis = prefs[expiresAtKey]?.toLongOrNull() ?: 0L,
        )
    }

    override suspend fun save(tokens: Tokens) {
        context.tokenDataStore.edit { prefs ->
            prefs[accessKey] = tokens.accessToken
            tokens.refreshToken?.let { prefs[refreshKey] = it }
            prefs[expiresAtKey] = tokens.expiresAtEpochMillis.toString()
        }
    }

    override suspend fun clear() {
        context.tokenDataStore.edit { it.clear() }
    }

    override suspend fun current(): Tokens? = tokens.first()
}
