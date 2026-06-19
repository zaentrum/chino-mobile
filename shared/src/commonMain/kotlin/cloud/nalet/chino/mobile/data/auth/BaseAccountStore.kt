package cloud.nalet.chino.mobile.data.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Platform-agnostic AccountStore logic — owns JSON encode/decode, accounts-
 * list sorting, active-id maintenance, and the legacy migration. Sub-classes
 * supply the raw String storage (Android DataStore Preferences, iOS
 * NSUserDefaults).
 *
 * Concurrency: the underlying platform storage is single-writer per-process
 * and atomic at the file level on both Android (DataStore commit) and iOS
 * (NSUserDefaults synchronize), so we don't need a Mutex in the common base.
 * The "blob is one big JSON string" model means every mutation is a full
 * rewrite — fine because account changes happen at human-tap cadence, never
 * in hot paths.
 */
abstract class BaseAccountStore : AccountStore {
    protected val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Synchronous read used by snapshotBlocking + currentAccessTokenBlocking +
     *  the addOrUpdate / remove / setActive read-modify-write cycle. Platform
     *  storages (DataStore prefs, NSUserDefaults) all expose a synchronous
     *  read path that's fast enough for these call sites. */
    protected abstract fun readBlobBlocking(): AccountStoreBlob

    /** Asynchronous write — DataStore's edit{} is suspending on Android; the
     *  iOS impl wraps an NSUserDefaults set + MutableStateFlow emit in a
     *  no-op suspend so the signature lines up. */
    protected abstract suspend fun writeBlob(blob: AccountStoreBlob)

    /** Hot flow of the current blob — emits on every successful write so the
     *  navigator + picker stay in sync. */
    protected abstract val blobFlow: Flow<AccountStoreBlob>

    // Lazy because the base-class constructor runs BEFORE the subclass
    // initializes its `override val blobFlow` — eager init here would
    // dereference a null blobFlow and crash on first collect (observed:
    // BaseAccountStore$special$$inlined$map$2 NPE in AppContainer.init
    // when collecting activeAccountId for the Telemetry / stream-token
    // wiring). Deferring to first access lets the subclass finish init
    // first.
    override val accounts: Flow<List<Account>> by lazy {
        blobFlow.map { it.accounts }.distinctUntilChanged()
    }
    override val activeAccountId: Flow<String?> by lazy {
        blobFlow.map { it.activeId }.distinctUntilChanged()
    }
    override val activeAccount: Flow<Account?> by lazy {
        blobFlow.map { b -> b.accounts.firstOrNull { it.id == b.activeId } }.distinctUntilChanged()
    }

    override suspend fun addOrUpdate(account: Account, setActive: Boolean) {
        val blob = readBlobBlocking()
        val without = blob.accounts.filter { it.id != account.id }
        val updated = (without + account).sortedByDescending { it.lastUsedAt }
        writeBlob(blob.copy(
            accounts = updated,
            activeId = if (setActive) account.id else blob.activeId,
        ))
    }

    override suspend fun remove(id: String) {
        val blob = readBlobBlocking()
        val updated = blob.accounts.filter { it.id != id }
        val newActive = if (blob.activeId == id) updated.firstOrNull()?.id else blob.activeId
        writeBlob(blob.copy(accounts = updated, activeId = newActive))
    }

    override suspend fun setActive(id: String) {
        val blob = readBlobBlocking()
        if (blob.accounts.none { it.id == id }) return
        // Bump lastUsedAt so the picker preserves recency ordering across
        // sessions — most-recent shows leftmost.
        val now = cloud.nalet.chino.mobile.currentTimeMillis()
        val updated = blob.accounts.map { if (it.id == id) it.copy(lastUsedAt = now) else it }
        writeBlob(blob.copy(accounts = updated, activeId = id))
    }

    override fun snapshotBlocking(): AccountStore.Snapshot {
        val blob = readBlobBlocking()
        val active = blob.accounts.firstOrNull { it.id == blob.activeId }
        return AccountStore.Snapshot(accounts = blob.accounts, activeAccount = active)
    }

    override fun currentAccessTokenBlocking(): String? {
        val blob = readBlobBlocking()
        return blob.accounts.firstOrNull { it.id == blob.activeId }?.accessToken
    }

    override suspend fun updateTokensForBlocking(accountId: String, tokens: Tokens) {
        val blob = readBlobBlocking()
        val updated = blob.accounts.map {
            if (it.id == accountId) {
                it.copy(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken ?: it.refreshToken,
                    expiresAtEpochMillis = tokens.expiresAtEpochMillis,
                )
            } else it
        }
        writeBlob(blob.copy(accounts = updated))
    }

    override suspend fun migrateFromTokenStoreIfPresent(legacy: TokenStore) {
        // Skip when we already have accounts — running this on every launch
        // is fine because the no-op branch is a single read.
        if (readBlobBlocking().accounts.isNotEmpty()) return
        val tokens = legacy.current() ?: return
        // Synthetic id; will be replaced by the real `sub` after the next
        // userinfo round-trip in the AuthScreenModel.
        val synthetic = Account(
            id = "legacy",
            displayName = "Account",
            email = "",
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAtEpochMillis = tokens.expiresAtEpochMillis,
            lastUsedAt = cloud.nalet.chino.mobile.currentTimeMillis(),
        )
        addOrUpdate(synthetic, setActive = true)
        legacy.clear()
    }
}
