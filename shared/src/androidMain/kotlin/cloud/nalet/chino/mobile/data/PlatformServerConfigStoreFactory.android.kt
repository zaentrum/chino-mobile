package cloud.nalet.chino.mobile.data

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

private val Context.serverConfigDataStore by preferencesDataStore(name = "chino_server_config")

actual class PlatformServerConfigStoreFactory(private val context: Context) {
    actual fun create(): ServerConfigStore = AndroidServerConfigStore(context.applicationContext)
}

/**
 * Single-string-entry DataStore-backed server-config store. The whole blob
 * (config + recents) is one JSON value under [KEY_BLOB]. Matches the storage
 * pattern in [cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory]'s
 * AndroidAccountStore — plaintext but app-sandboxed; the config is not secret
 * (tokens stay in AccountStore).
 *
 * In-memory MutableStateFlow caches the latest blob so [readBlobBlocking] is an
 * O(1) sync read. The cache is seeded by a one-time runBlocking on construction
 * (DataStore's file decode runs in well under 5 ms) and refreshed by every
 * writeBlob; we also collect DataStore's own flow in the background so external
 * edits keep the cache fresh.
 */
private class AndroidServerConfigStore(private val context: Context) : BaseServerConfigStore() {
    private val key: Preferences.Key<String> = stringPreferencesKey(KEY_BLOB)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val state: MutableStateFlow<ServerConfigBlob> = MutableStateFlow(
        runBlocking { readFromDisk() },
    )

    init {
        scope.launch {
            context.serverConfigDataStore.data
                .onEach { prefs ->
                    val fromDisk = prefs[key]?.let { decode(it) } ?: ServerConfigBlob()
                    if (fromDisk != state.value) state.value = fromDisk
                }
                .collect {}
        }
    }

    override val blobFlow: Flow<ServerConfigBlob> = state.asStateFlow()

    override fun readBlobBlocking(): ServerConfigBlob = state.value

    override suspend fun writeBlob(blob: ServerConfigBlob) {
        context.serverConfigDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(ServerConfigBlob.serializer(), blob)
        }
        state.value = blob
    }

    private suspend fun readFromDisk(): ServerConfigBlob {
        val raw = context.serverConfigDataStore.data.first()[key]
        return raw?.let { decode(it) } ?: ServerConfigBlob()
    }

    private fun decode(raw: String): ServerConfigBlob =
        runCatching { json.decodeFromString(ServerConfigBlob.serializer(), raw) }
            .getOrElse { ServerConfigBlob() }
}

private const val KEY_BLOB = "blob_v1"
