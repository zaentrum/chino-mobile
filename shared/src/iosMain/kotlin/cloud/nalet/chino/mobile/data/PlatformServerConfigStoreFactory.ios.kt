package cloud.nalet.chino.mobile.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.jsonObject
import platform.Foundation.NSUserDefaults

/**
 * NSUserDefaults-backed server-config store. Mirrors the Android impl + the
 * iOS IosAccountStore: a single JSON string entry under [KEY_BLOB] cached in a
 * MutableStateFlow so synchronous read paths are O(1).
 *
 * Its key is its own. Both stores used to write "blob_v1" in the same suite,
 * so signing in overwrote the server config with the accounts, and the next
 * launch found no server, seeded the empty build default (every request then
 * went to http://localhost/) and overwrote the accounts in turn. A server
 * blob still under the shared key is read once and moved.
 *
 * NSUserDefaults is plaintext but app-sandboxed — and the config is not secret
 * anyway (tokens stay in the AccountStore / Keychain hardening item).
 */
actual class PlatformServerConfigStoreFactory(private val suiteName: String) {
    actual fun create(): ServerConfigStore = IosServerConfigStore(NSUserDefaults(suiteName = suiteName))
}

private class IosServerConfigStore(private val defaults: NSUserDefaults) : BaseServerConfigStore() {
    private val state: MutableStateFlow<ServerConfigBlob> = MutableStateFlow(readSnapshot())

    override val blobFlow: Flow<ServerConfigBlob> = state.asStateFlow()

    override fun readBlobBlocking(): ServerConfigBlob = state.value

    override suspend fun writeBlob(blob: ServerConfigBlob) {
        val raw = json.encodeToString(ServerConfigBlob.serializer(), blob)
        defaults.setObject(raw, KEY_BLOB)
        state.value = blob
    }

    private fun readSnapshot(): ServerConfigBlob {
        defaults.stringForKey(KEY_BLOB)?.let { raw ->
            return runCatching { json.decodeFromString(ServerConfigBlob.serializer(), raw) }
                .getOrElse { ServerConfigBlob() }
        }
        // Before the key was its own: take a server blob from the shared key
        // (one with "config" / "recents", not the accounts' "accounts").
        val legacy = defaults.stringForKey(SHARED_LEGACY_KEY) ?: return ServerConfigBlob()
        val obj = runCatching { json.parseToJsonElement(legacy).jsonObject }.getOrNull() ?: return ServerConfigBlob()
        if ("accounts" in obj || ("config" !in obj && "recents" !in obj)) return ServerConfigBlob()
        val blob = runCatching { json.decodeFromJsonElement(ServerConfigBlob.serializer(), obj) }.getOrNull()
            ?: return ServerConfigBlob()
        defaults.setObject(json.encodeToString(ServerConfigBlob.serializer(), blob), KEY_BLOB)
        return blob
    }
}

private const val KEY_BLOB = "server_config_v1"

/** The key IosAccountStore writes, which this store shared until it had its own. */
private const val SHARED_LEGACY_KEY = "blob_v1"
