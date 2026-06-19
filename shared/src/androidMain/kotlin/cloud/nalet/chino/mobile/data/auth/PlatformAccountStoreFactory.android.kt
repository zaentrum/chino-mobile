package cloud.nalet.chino.mobile.data.auth

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.accountDataStore by preferencesDataStore(name = "chino_accounts")

actual class PlatformAccountStoreFactory(private val context: Context) {
    actual fun create(): AccountStore = AndroidAccountStore(context.applicationContext)
}

/**
 * Single-string-entry DataStore-backed account store. The whole blob (accounts
 * list + active id) is one JSON value under [KEY_BLOB]. Matches the storage
 * pattern in chino-androidtv/AccountStore so the two clients are easy to
 * reason about together; the difference is we use DataStore here instead of
 * EncryptedSharedPreferences (security-level parity with the existing
 * TokenStore — pre-prod hardening is tracked in the README).
 *
 * In-memory MutableStateFlow caches the latest blob so [readBlobBlocking],
 * [currentAccessTokenBlocking], and [snapshotBlocking] are O(1) sync reads.
 * The cache is seeded by a one-time runBlocking on construction (DataStore's
 * file decode runs in well under 5 ms even on a cold launch) and refreshed
 * by every successful writeBlob. We also collect DataStore's own flow in a
 * background scope so external edits (extremely unlikely — we own this
 * preference file) keep the cache fresh.
 */
private class AndroidAccountStore(private val context: Context) : BaseAccountStore() {
    private val key: Preferences.Key<String> = stringPreferencesKey(KEY_BLOB)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Seed synchronously — runs once at factory.create() and the cost is the
    // first DataStore file read. Subsequent reads come from the cache.
    private val state: MutableStateFlow<AccountStoreBlob> = MutableStateFlow(
        runBlocking { readFromDisk() },
    )

    init {
        scope.launch {
            context.accountDataStore.data
                .onEach { prefs ->
                    val fromDisk = prefs[key]?.let { decode(it) } ?: AccountStoreBlob()
                    // Only emit if the disk value differs from what we just
                    // wrote — otherwise writeBlob's own state.value = ...
                    // already emitted and re-emitting here would be a
                    // redundant recomposition.
                    if (fromDisk != state.value) state.value = fromDisk
                }
                .collect {}
        }
    }

    override val blobFlow: Flow<AccountStoreBlob> = state.asStateFlow()

    override fun readBlobBlocking(): AccountStoreBlob = state.value

    override suspend fun writeBlob(blob: AccountStoreBlob) {
        context.accountDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(AccountStoreBlob.serializer(), blob)
        }
        state.value = blob
    }

    private suspend fun readFromDisk(): AccountStoreBlob {
        val raw = context.accountDataStore.data.first()[key]
        return raw?.let { decode(it) } ?: AccountStoreBlob()
    }

    private fun decode(raw: String): AccountStoreBlob =
        runCatching { json.decodeFromString(AccountStoreBlob.serializer(), raw) }
            .getOrElse { AccountStoreBlob() }
}

private const val KEY_BLOB = "blob_v1"
