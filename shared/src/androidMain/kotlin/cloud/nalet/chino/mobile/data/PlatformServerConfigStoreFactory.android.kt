package cloud.nalet.chino.mobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.serverConfigDataStore by preferencesDataStore(name = "chino_server_config")

actual class PlatformServerConfigStoreFactory internal constructor(private val make: () -> ServerConfigStore) {
    constructor(context: Context) : this({ AndroidServerConfigStore(context.applicationContext.serverConfigDataStore) })

    actual fun create(): ServerConfigStore = make()
}

/**
 * Single-string-entry DataStore-backed server-config store. The whole blob
 * (config + recents) is one JSON value under [KEY_BLOB]. Matches the storage
 * pattern in [cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory]'s
 * AndroidAccountStore — plaintext but app-sandboxed; the config is not secret
 * (tokens stay in AccountStore).
 *
 * In-memory MutableStateFlow caches the latest blob so [readBlobBlocking] is an
 * O(1) sync read. The cache is what was read from [store] once, on
 * construction (DataStore's file decode runs in well under 5 ms), and then
 * what this store wrote last: the file has no other writer, so the cache never
 * takes a value from DataStore's own flow, whose emission for a write may come
 * after a later write has landed.
 */
internal class AndroidServerConfigStore(private val store: DataStore<Preferences>) : BaseServerConfigStore() {
    private val key: Preferences.Key<String> = stringPreferencesKey(KEY_BLOB)

    private val state: MutableStateFlow<ServerConfigBlob> = MutableStateFlow(
        runBlocking { readFromDisk() },
    )

    override val blobFlow: Flow<ServerConfigBlob> = state.asStateFlow()

    override fun readBlobBlocking(): ServerConfigBlob = state.value

    override suspend fun writeBlob(blob: ServerConfigBlob) {
        store.edit { prefs ->
            prefs[key] = json.encodeToString(ServerConfigBlob.serializer(), blob)
        }
        state.value = blob
    }

    private suspend fun readFromDisk(): ServerConfigBlob {
        val raw = store.data.first()[key]
        return raw?.let { decode(it) } ?: ServerConfigBlob()
    }

    private fun decode(raw: String): ServerConfigBlob =
        runCatching { json.decodeFromString(ServerConfigBlob.serializer(), raw) }
            .getOrElse { ServerConfigBlob() }
}

private const val KEY_BLOB = "blob_v1"
