package cloud.nalet.chino.mobile.data.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSUserDefaults

/**
 * NSUserDefaults-backed multi-account store. Mirrors the Android impl: a
 * single JSON string entry under [KEY_BLOB] cached in a MutableStateFlow so
 * synchronous read paths are O(1).
 *
 * NSUserDefaults is plaintext but app-sandboxed — same trust level as the
 * existing [IosTokenStore]. Pre-prod hardening to Keychain is tracked in the
 * shared module README.
 */
actual class PlatformAccountStoreFactory(private val suiteName: String) {
    actual fun create(): AccountStore = IosAccountStore(NSUserDefaults(suiteName = suiteName))
}

private class IosAccountStore(private val defaults: NSUserDefaults) : BaseAccountStore() {
    private val state: MutableStateFlow<AccountStoreBlob> = MutableStateFlow(readSnapshot())

    override val blobFlow: Flow<AccountStoreBlob> = state.asStateFlow()

    override fun readBlobBlocking(): AccountStoreBlob = state.value

    override suspend fun writeBlob(blob: AccountStoreBlob) {
        val raw = json.encodeToString(AccountStoreBlob.serializer(), blob)
        defaults.setObject(raw, KEY_BLOB)
        state.value = blob
    }

    private fun readSnapshot(): AccountStoreBlob {
        val raw = defaults.stringForKey(KEY_BLOB) ?: return AccountStoreBlob()
        return runCatching { json.decodeFromString(AccountStoreBlob.serializer(), raw) }
            .getOrElse { AccountStoreBlob() }
    }
}

private const val KEY_BLOB = "blob_v1"
