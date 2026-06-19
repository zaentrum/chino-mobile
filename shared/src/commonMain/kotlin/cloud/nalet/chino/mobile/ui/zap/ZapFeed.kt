package cloud.nalet.chino.mobile.ui.zap

import cloud.nalet.chino.mobile.data.api.ChinoApi
import cloud.nalet.chino.mobile.data.model.Item
import kotlin.random.Random

/**
 * The Zap candidate feed — faithful port of chino-androidtv's ZapFeed (itself
 * a port of chino-web's useZapFeed), retargeted onto the mobile Ktor [ChinoApi]
 * and kotlinx.coroutines.
 *
 * Builds a pool from movie (by rating) + series (by newest) lists, dedups
 * against watched ids + a session-permanent shown-set + the packaged-only
 * filter, Fisher-Yates shuffles it (so a cold/empty preference vector doesn't
 * collapse to "always the top-rated title"), then ε-greedy samples a queue.
 *
 * V1 drops type=episode (web treats it as best-effort, and episodes routinely
 * lack a list-level duration). [scoreItem] is the exploit-branch ranking
 * (type-level on the pool; full genre/cast scoring happens per-card via the
 * ScreenModel). [random] is injectable for deterministic tests.
 *
 * Verbatim constants: ε=0.6, SCORE_JITTER=0.05, POOL_SIZE_PER_TYPE=30,
 * SEED_SIZE=8, REFILL_AT=5.
 */
class ZapFeed(
    private val api: ChinoApi,
    private val scoreItem: (Item) -> Double = { 0.0 },
    private val random: Random = Random.Default,
) {
    private var pool: List<Item> = emptyList()
    private val shown = HashSet<String>()
    private val _queue = ArrayList<Item>()

    val queue: List<Item> get() = _queue.toList()
    var empty: Boolean = false
        private set

    /**
     * The DETERMINISTIC top-ranked candidate of the current pool, or null if the
     * pool is empty. Independent of the Fisher-Yates shuffle and the ε-greedy
     * sample: ranks the whole pool by the same exploit key the queue sampler
     * uses ([scoreItem] + rating) with NO jitter and an id tie-break, so two
     * independent loads of the same backing data agree on card[0]. This is the
     * shared source the in-screen first card and the app-start warm both use so
     * they warm + play the SAME first card. */
    fun topRankedCandidate(): Item? =
        pool.maxWithOrNull(
            compareBy<Item> { scoreItem(it) + (it.rating ?: 0.0) / 100.0 }.thenBy { it.id },
        )

    /** Fetch + assemble the pool and seed the queue. Each fetch degrades to
     *  empty on failure (the packaged filter is skipped entirely if its
     *  endpoint is unavailable — older deploys still get a pool). */
    suspend fun load() {
        val movies = runCatching {
            api.listItems(type = "movie", sort = "rating", limit = POOL_SIZE_PER_TYPE).items
        }.getOrDefault(emptyList())
        val series = runCatching {
            api.listItems(type = "series", sort = "newest", limit = POOL_SIZE_PER_TYPE).items
        }.getOrDefault(emptyList())
        val watched = runCatching {
            api.watched(limit = WATCHED_LIMIT).items.mapTo(HashSet()) { it.id }
        }.getOrDefault(emptySet())
        // null → packaged endpoint unavailable → skip the filter entirely.
        val packaged: Set<String>? = runCatching { api.packagedIds().ids.toHashSet() }.getOrNull()

        val seen = HashSet<String>()
        val assembled = ArrayList<Item>()
        for (item in (movies + series)) {
            val id = item.id
            if (id.isBlank() || !seen.add(id)) continue        // first-wins dedup
            if (id in watched || item.watchedAt != null) continue
            if (packaged != null && id !in packaged) continue  // packaged-only
            assembled.add(item)
        }
        shuffleInPlace(assembled)
        pool = assembled
        empty = pool.isEmpty()

        _queue.clear()
        _queue.addAll(sample(SEED_SIZE))
    }

    /** Mark an id shown so it never resurfaces this session; drop it from the queue. */
    fun markShown(id: String) {
        shown.add(id)
        _queue.removeAll { it.id == id }
    }

    /** Top up the queue when it runs short; flips [empty] when exhausted. */
    fun refill() {
        if (_queue.size >= REFILL_AT) return
        val more = sample(SEED_SIZE - _queue.size)
        if (more.isEmpty() && _queue.isEmpty()) empty = true
        _queue.addAll(more)
    }

    private fun sample(n: Int): List<Item> {
        val inQueue = _queue.mapTo(HashSet()) { it.id }
        val candidates = pool.filter { it.id !in shown && it.id !in inQueue }
        if (candidates.isEmpty()) return emptyList()

        val taken = HashSet<String>()
        val out = ArrayList<Item>()
        for (i in 0 until n) {
            val available = candidates.filter { it.id !in taken }
            if (available.isEmpty()) break
            val pick = if (random.nextDouble() < EPSILON) {
                available[random.nextInt(available.size)]                       // explore
            } else {
                available.maxByOrNull {                                          // exploit
                    scoreItem(it) + (it.rating ?: 0.0) / 100.0 + random.nextDouble() * SCORE_JITTER
                }!!
            }
            taken.add(pick.id)
            out.add(pick)
        }
        return out
    }

    private fun shuffleInPlace(items: MutableList<Item>) {
        for (i in items.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val tmp = items[i]; items[i] = items[j]; items[j] = tmp
        }
    }

    companion object {
        private const val EPSILON = 0.6
        private const val SCORE_JITTER = 0.05
        private const val POOL_SIZE_PER_TYPE = 30
        private const val WATCHED_LIMIT = 200
        private const val SEED_SIZE = 8
        private const val REFILL_AT = 5
    }
}
