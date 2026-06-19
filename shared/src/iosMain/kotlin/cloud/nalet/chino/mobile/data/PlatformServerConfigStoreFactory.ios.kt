package cloud.nalet.chino.mobile.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSUserDefaults

/**
 * NSUserDefaults-backed server-config store. Mirrors the Android impl + the
 * iOS IosAccountStore: a single JSON string entry under [KEY_BLOB] cached in a
 * MutableStateFlow so synchronous read paths are O(1).
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
        val raw = defaults.stringForKey(KEY_BLOB) ?: return ServerConfigBlob()
        return runCatching { json.decodeFromString(ServerConfigBlob.serializer(), raw) }
            .getOrElse { ServerConfigBlob() }
    }
}

private const val KEY_BLOB = "blob_v1"
