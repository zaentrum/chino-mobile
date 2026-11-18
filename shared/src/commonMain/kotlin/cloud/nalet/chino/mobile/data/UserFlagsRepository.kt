package cloud.nalet.chino.mobile.data

import cloud.nalet.chino.mobile.data.api.ChinoApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory cache of the user's watchlist + likes sets shared across the
 * whole app. Mirrors chino-androidtv's UserFlagsRepository + chino-web's
 * hooks/useUserFlags pattern: one fetch per kind on first access,
 * optimistic toggle, server reconciliation on failure. A toggle is the
 * account's signed in as it is pressed: its write goes with that account's
 * own token, and is not sent once another account is signed in, or nobody
 * is.
 */
class UserFlagsRepository(
    private val api: ChinoApi,
    /** Application-lifetime scope so optimistic write-throughs survive the
     *  ViewModel that initiated them — backing scope is wired in
     *  AppContainer (Dispatchers.Default + SupervisorJob). */
    private val scope: CoroutineScope,
    /** The account signed in now; null for nobody. */
    private val accountNow: () -> String?,
    /** [accountId]'s own token while it is the account signed in; null once
     *  it is not. */
    private val bearerOf: suspend (accountId: String) -> String?,
) {
    private val mutex = Mutex()

    private val _watchlist = MutableStateFlow<Set<String>>(emptySet())
    val watchlist: StateFlow<Set<String>> = _watchlist.asStateFlow()
    private var watchlistLoaded = false

    private val _likes = MutableStateFlow<Set<String>>(emptySet())
    val likes: StateFlow<Set<String>> = _likes.asStateFlow()
    private var likesLoaded = false

    /** Idempotent — call from screens that need the flags. Returns when
     *  cache is warm. */
    suspend fun warm() {
        mutex.withLock {
            if (!watchlistLoaded) {
                runCatching { api.getWatchlist().items }.onSuccess {
                    _watchlist.value = it.toSet()
                    watchlistLoaded = true
                }
            }
            if (!likesLoaded) {
                runCatching { api.getLikes().items }.onSuccess {
                    _likes.value = it.toSet()
                    likesLoaded = true
                }
            }
        }
    }

    /** Forgets the cached watchlist and likes: the account they belong to is
     *  signed out. The next [warm] loads them for whoever is signed in then. */
    suspend fun clear() {
        mutex.withLock {
            _watchlist.value = emptySet()
            watchlistLoaded = false
            _likes.value = emptySet()
            likesLoaded = false
        }
    }

    fun setWatchlist(itemId: String, present: Boolean) {
        val account = accountNow() ?: return
        _watchlist.value = if (present) _watchlist.value + itemId else _watchlist.value - itemId
        scope.launch {
            runCatching {
                val bearer = bearerOf(account) ?: throw NotSignedInNow()
                if (present) api.addToWatchlist(itemId, bearer) else api.removeFromWatchlist(itemId, bearer)
            }.onFailure {
                // Undone in the cache of the account it was pressed for alone.
                if (accountNow() == account) _watchlist.value = if (present) _watchlist.value - itemId else _watchlist.value + itemId
            }
        }
    }

    fun setLike(itemId: String, present: Boolean) {
        val account = accountNow() ?: return
        _likes.value = if (present) _likes.value + itemId else _likes.value - itemId
        scope.launch {
            runCatching {
                val bearer = bearerOf(account) ?: throw NotSignedInNow()
                if (present) api.addLike(itemId, bearer) else api.removeLike(itemId, bearer)
            }.onFailure {
                if (accountNow() == account) _likes.value = if (present) _likes.value - itemId else _likes.value + itemId
            }
        }
    }
}

/** A write made for an account that is no longer the one signed in, by the
 *  time it would go: not sent. */
class NotSignedInNow : Exception("That account is no longer the one signed in.")
