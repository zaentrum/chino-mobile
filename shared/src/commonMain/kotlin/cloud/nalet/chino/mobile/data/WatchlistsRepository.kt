package cloud.nalet.chino.mobile.data

import cloud.nalet.chino.mobile.data.api.ChinoApi
import cloud.nalet.chino.mobile.data.api.Watchlist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lists-aware companion to [UserFlagsRepository] for multiple named
 * watchlists. Holds the user's list overview (default first) and a per-item
 * membership cache (itemId -> set of listId the item is in). Mirrors the
 * UserFlags pattern: one fetch on first access, optimistic write-through,
 * server reconciliation on failure.
 *
 * The legacy single-flag [UserFlagsRepository.watchlist] set stays the
 * "in the DEFAULT list" signal (back-compat /me/watchlist route). The
 * "saved" signal the app surfaces — detail toggle fill + card badge — now
 * means "in AT LEAST ONE list", which callers compute by OR-ing the default
 * set with [membershipsFor].
 */
class WatchlistsRepository(
    private val api: ChinoApi,
    /** Application-lifetime scope so optimistic write-throughs survive the
     *  screen-model that initiated them — same backing scope as UserFlags. */
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    private val _lists = MutableStateFlow<List<Watchlist>>(emptyList())
    val lists: StateFlow<List<Watchlist>> = _lists.asStateFlow()
    private var listsLoaded = false

    /** itemId -> the set of list ids the item currently belongs to (caller's
     *  lists only). Absent keys are simply "unknown / not yet fetched"; an
     *  empty set means "in no list". Seeded lazily by [warmMemberships]. */
    private val _memberships = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val memberships: StateFlow<Map<String, Set<String>>> = _memberships.asStateFlow()

    /** Loads the list overview once. Idempotent. */
    suspend fun warmLists(force: Boolean = false) {
        mutex.withLock {
            if (listsLoaded && !force) return
            runCatching { api.listWatchlists().lists }.onSuccess {
                _lists.value = it
                listsLoaded = true
            }
        }
    }

    /** Re-fetches the list overview (item counts shift as items are added /
     *  removed). Best-effort. */
    fun refreshLists() {
        scope.launch { warmLists(force = true) }
    }

    /** True when [itemId]'s memberships are present in the cache. */
    fun isMembershipKnown(itemId: String): Boolean = _memberships.value.containsKey(itemId)

    /** The set of list ids [itemId] is in, or null when unknown. */
    fun membershipsFor(itemId: String): Set<String>? = _memberships.value[itemId]

    /** Fetches memberships for [ids] not already cached and folds them into
     *  the cache. Items the server omits map to the empty set so the "saved"
     *  badge can resolve to false without a second round-trip.
     *
     *  The fold is NON-clobbering: an optimistic [setMembership] made while
     *  the fetch was in flight (open the sheet → tap a list fast) writes the
     *  id into the cache, so at fold time only ids still ABSENT are written
     *  — the fetched (stale) set never overwrites the user's toggle. */
    suspend fun warmMemberships(ids: List<String>) {
        val missing = ids.filter { it !in _memberships.value }
        if (missing.isEmpty()) return
        runCatching { api.getMemberships(missing).memberships }.onSuccess { fetched ->
            _memberships.update { current ->
                val next = current.toMutableMap()
                missing.forEach { id ->
                    if (id !in current) {
                        next[id] = fetched[id]?.toSet() ?: emptySet()
                    }
                }
                next
            }
        }
    }

    /** Optimistically toggles [itemId]'s membership in [listId], updating both
     *  the membership cache and the list's item count, then writes through.
     *  Rolls back on failure. */
    fun setMembership(itemId: String, listId: String, present: Boolean) {
        applyMembership(itemId, listId, present)
        scope.launch {
            runCatching {
                if (present) api.addItemToList(listId, itemId)
                else api.removeItemFromList(listId, itemId)
            }.onFailure {
                applyMembership(itemId, listId, !present)
            }
        }
    }

    private fun applyMembership(itemId: String, listId: String, present: Boolean) {
        val current = _memberships.value[itemId] ?: emptySet()
        val alreadyPresent = listId in current
        if (alreadyPresent == present) return
        val next = if (present) current + listId else current - listId
        _memberships.value = _memberships.value + (itemId to next)
        // Keep the per-list item count in sync so the lists overview chips
        // don't go stale until the next refresh.
        _lists.value = _lists.value.map { list ->
            if (list.id == listId) {
                list.copy(itemCount = (list.itemCount + if (present) 1 else -1).coerceAtLeast(0))
            } else list
        }
    }

    /** Creates a list, prepends-or-appends it into the cached overview
     *  (default stays first), and returns it. Throws on a server rejection
     *  (duplicate / too many / empty) so the dialog can surface the error. */
    suspend fun create(name: String): Watchlist {
        val created = api.createWatchlist(name)
        mutex.withLock {
            // Insert after the default list (which sorts first), matching the
            // server's "default first, then createdAt asc" order.
            val existing = _lists.value
            _lists.value = (existing + created)
                .sortedWith(compareByDescending<Watchlist> { it.isDefault }.thenBy { it.createdAt ?: "" })
        }
        return created
    }

    /** Renames a list and updates the cached overview. Throws on rejection. */
    suspend fun rename(listId: String, name: String): Watchlist {
        val updated = api.renameWatchlist(listId, name)
        _lists.value = _lists.value.map { if (it.id == listId) updated else it }
        return updated
    }

    /** Deletes a list (cascades its items) and drops it from the cache +
     *  prunes its id from every cached membership set. Throws on rejection
     *  (e.g. deleting the default list -> 409). */
    suspend fun delete(listId: String) {
        api.deleteWatchlist(listId)
        _lists.value = _lists.value.filterNot { it.id == listId }
        _memberships.value = _memberships.value.mapValues { (_, set) -> set - listId }
    }
}
