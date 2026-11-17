package cloud.nalet.chino.mobile.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** Held by every read-modify-write of the blob, so that two writes of
     *  different fields - a server's subtitle languages and an account's
     *  choice, read as settings open - never drop one another. */
    private val writes = Mutex()

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

    override suspend fun addRecent(url: String) = writes.withLock {
        val blob = readBlobBlocking()
        val next = (listOf(url) + blob.recents.filter { it != url }).take(5)
        writeBlob(blob.copy(recents = next))
    }

    override suspend fun save(config: ServerConfig) = writes.withLock {
        val blob = readBlobBlocking()
        // Another server: what the phone kept of the old one's accounts goes,
        // and what the old one said it offers. The same server's address
        // with or without its trailing slash is the same server.
        val same = blob.config?.baseUrl?.trimEnd('/') == config.baseUrl.trimEnd('/')
        writeBlob(
            blob.copy(
                config = config,
                watchlistByKind = blob.watchlistByKind.takeIf { same },
                features = blob.features.takeIf { same },
            ),
        )
    }

    override suspend fun clear() = writes.withLock {
        // Keep recents so the user can re-pick a recent after "Change server".
        // A server's subtitle languages go with it, its accounts' choices and
        // what it said it offers.
        writeBlob(readBlobBlocking().copy(config = null, subtitleLanguages = null, watchlistByKind = null, features = null))
    }

    override fun watchlistKindsBlocking(baseUrl: String): Boolean? =
        readBlobBlocking().features?.takeIf { it.baseUrl == baseUrl }?.watchlistKinds

    override suspend fun saveWatchlistKinds(baseUrl: String, on: Boolean) = writes.withLock {
        val blob = readBlobBlocking()
        val kept = KeptServerFeatures(baseUrl, on)
        if (blob.features != kept) writeBlob(blob.copy(features = kept))
    }

    override fun watchlistByKindBlocking(baseUrl: String, accountId: String): Boolean? =
        readBlobBlocking().watchlistByKind?.takeIf { it.baseUrl == baseUrl }?.accounts?.get(accountId)

    override suspend fun saveWatchlistByKind(baseUrl: String, accountId: String, on: Boolean?) = writes.withLock {
        val blob = readBlobBlocking()
        // One server's, the one connected: another's go.
        val before = blob.watchlistByKind?.takeIf { it.baseUrl == baseUrl }?.accounts.orEmpty()
        val after = if (on == null) before - accountId else before + (accountId to on)
        val kept = KeptAccountChoices(baseUrl, after)
        if (blob.watchlistByKind != kept) writeBlob(blob.copy(watchlistByKind = kept))
    }

    override fun subtitleLanguagesBlocking(baseUrl: String): List<String>? =
        readBlobBlocking().subtitleLanguages?.takeIf { it.baseUrl == baseUrl }?.languages

    override suspend fun saveSubtitleLanguages(baseUrl: String, languages: List<String>) = writes.withLock {
        val blob = readBlobBlocking()
        val kept = KeptSubtitleLanguages(baseUrl, languages)
        if (blob.subtitleLanguages != kept) writeBlob(blob.copy(subtitleLanguages = kept))
    }
}
