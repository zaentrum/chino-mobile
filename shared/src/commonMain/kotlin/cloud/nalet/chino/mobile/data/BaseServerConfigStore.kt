package cloud.nalet.chino.mobile.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Platform-agnostic [ServerConfigStore] logic — owns JSON encode/decode and
 * the recents list maintenance. Sub-classes supply the raw String storage
 * (Android DataStore Preferences, iOS NSUserDefaults), exactly mirroring
 * [cloud.nalet.chino.mobile.data.auth.BaseAccountStore].
 *
 * The whole thing (config + recents) is one JSON blob; every mutation is a
 * full rewrite — fine because server changes happen at human-tap cadence,
 * never in a hot path.
 */
abstract class BaseServerConfigStore : ServerConfigStore {
    protected val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Synchronous read used by [currentBlocking] + the read-modify-write
     *  cycle. Platform storages all expose a fast synchronous read path. */
    protected abstract fun readBlobBlocking(): ServerConfigBlob

    /** Asynchronous write — DataStore's edit{} is suspending on Android; the
     *  iOS impl wraps an NSUserDefaults set + MutableStateFlow emit in a
     *  no-op suspend so the signature lines up. */
    protected abstract suspend fun writeBlob(blob: ServerConfigBlob)

    /** Hot flow of the current blob — emits on every successful write. */
    protected abstract val blobFlow: Flow<ServerConfigBlob>

    // Lazy for the same reason as BaseAccountStore: the base-class constructor
    // runs BEFORE the subclass initialises its `override val blobFlow`; eager
    // init here would dereference a null blobFlow and crash on first collect.
    override val config: Flow<ServerConfig?> by lazy {
        blobFlow.map { it.config?.takeIf { c -> c.isConfigured } }.distinctUntilChanged()
    }

    override suspend fun current(): ServerConfig? =
        readBlobBlocking().config?.takeIf { it.isConfigured }

    override fun currentBlocking(): ServerConfig? =
        readBlobBlocking().config?.takeIf { it.isConfigured }

    override suspend fun recents(): List<String> = readBlobBlocking().recents

    override suspend fun addRecent(url: String) {
        val blob = readBlobBlocking()
        val next = (listOf(url) + blob.recents.filter { it != url }).take(5)
        writeBlob(blob.copy(recents = next))
    }

    override suspend fun save(config: ServerConfig) {
        writeBlob(readBlobBlocking().copy(config = config))
    }

    override suspend fun clear() {
        // Keep recents so the user can re-pick a recent after "Change server".
        writeBlob(readBlobBlocking().copy(config = null))
    }
}
