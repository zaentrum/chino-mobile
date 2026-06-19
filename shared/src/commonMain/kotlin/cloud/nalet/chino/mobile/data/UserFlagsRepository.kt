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
 * optimistic toggle, server reconciliation on failure.
 */
class UserFlagsRepository(
    private val api: ChinoApi,
    /** Application-lifetime scope so optimistic write-throughs survive the
     *  ViewModel that initiated them — backing scope is wired in
     *  AppContainer (Dispatchers.Default + SupervisorJob). */
    private val scope: CoroutineScope,
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

    fun setWatchlist(itemId: String, present: Boolean) {
        _watchlist.value = if (present) _watchlist.value + itemId else _watchlist.value - itemId
        scope.launch {
            runCatching {
                if (present) api.addToWatchlist(itemId) else api.removeFromWatchlist(itemId)
            }.onFailure {
                _watchlist.value = if (present) _watchlist.value - itemId else _watchlist.value + itemId
            }
        }
    }

    fun setLike(itemId: String, present: Boolean) {
        _likes.value = if (present) _likes.value + itemId else _likes.value - itemId
        scope.launch {
            runCatching {
                if (present) api.addLike(itemId) else api.removeLike(itemId)
            }.onFailure {
                _likes.value = if (present) _likes.value - itemId else _likes.value + itemId
            }
        }
    }
}
