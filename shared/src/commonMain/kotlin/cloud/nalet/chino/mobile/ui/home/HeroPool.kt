package cloud.nalet.chino.mobile.ui.home

import cloud.nalet.chino.mobile.data.api.ContinueWatchingItem
import cloud.nalet.chino.mobile.data.api.Season
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.ui.trailer.localTrailer
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource

/*
 * The Home hero's pool and what its Play plays — chino-web's useHeroPool,
 * seriesPlay.ts and usePlayTitle, so a server shows the same hero on the web
 * and in the app. Pure but for the cache, so commonTest runs it everywhere.
 */

/** How many titles the hero rotates through (web's slice of 8). */
internal const val HERO_POOL_SIZE = 8

/** How many of the newest movies, and of the newest series, are candidates. */
internal const val HERO_CANDIDATES = 40

private val YOUTUBE_KEY =
    Regex("""[?&]v=([A-Za-z0-9_-]{6,})|youtu\.be/([A-Za-z0-9_-]{6,})|youtube\.com/embed/([A-Za-z0-9_-]{6,})""")

/** The YouTube video id in a trailer URL — watch?v=, youtu.be/ or embed/ —
 *  or null: web's ytIdFromUrl. */
internal fun youTubeKey(url: String): String? =
    YOUTUBE_KEY.find(url)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }

/**
 * The hero's pool as chino-web picks it: of the [candidates] (the newest
 * movies and series), those whose [details] have a trailer — one this
 * server plays ([localTrailer]) or a YouTube link — a title enriched enough
 * to lead with, each with the details' overview, extras and trailer links
 * (the list endpoint leaves them out): what the hero's Trailer chooses from
 * (trailerChoice). Those with a trailer of their own come first, then those
 * with a link, each group shuffled; at most [size].
 */
internal fun pickHeroPool(
    candidates: List<Item>,
    details: Map<String, Item>,
    size: Int = HERO_POOL_SIZE,
    random: Random = Random,
): List<Item> {
    val local = ArrayList<Item>()
    val linked = ArrayList<Item>()
    for (candidate in candidates) {
        val detail = details[candidate.id] ?: continue
        val hero = candidate.copy(
            overview = detail.overview ?: candidate.overview,
            kind = candidate.kind ?: detail.kind,
            trailers = detail.trailers,
            extras = detail.extras,
        )
        when {
            localTrailer(detail.extras) != null -> local += hero
            detail.trailers.any { youTubeKey(it.url) != null } -> linked += hero
        }
    }
    return (local.shuffled(random) + linked.shuffled(random)).take(size)
}

/**
 * The episode Play on a series plays — a series is not itself playable, its
 * episodes are: the one Continue watching names first for it (the episode
 * the viewer is in, or the next after the last they finished; the feed lists
 * the most recent first), else the series' first episode. Null when the
 * series has no episodes. Web's episodeToPlay.
 */
internal fun episodeToPlay(seriesId: String, continueWatching: List<ContinueWatchingItem>, seasons: List<Season>): String? =
    continueWatching.firstOrNull { it.type == "episode" && it.parentId == seriesId }?.id ?: firstEpisode(seasons)

/** S01E01: the lowest episode of the lowest season from 1 on; the specials
 *  of season 0 only when there is nothing else. Web's firstEpisode. */
internal fun firstEpisode(seasons: List<Season>): String? {
    val withEpisodes = seasons.filter { it.episodes.isNotEmpty() }
    val regular = withEpisodes.filter { it.season >= 1 }
    val season = (regular.ifEmpty { withEpisodes }).minByOrNull { it.season } ?: return null
    return season.episodes.minByOrNull { it.episodeNumber ?: Int.MAX_VALUE }?.id
}

/**
 * The pool per server and account for an hour, as web keeps it per session:
 * a pool costs one details fetch per candidate. Per account, because a
 * person's rating cap decides which titles they see.
 */
internal object HeroPoolCache {
    private val ttl = 1.hours
    private var key: String? = null
    private var at: TimeSource.Monotonic.ValueTimeMark? = null
    private var pool: List<Item> = emptyList()

    fun get(key: String): List<Item>? {
        val since = at ?: return null
        return if (key == this.key && since.elapsedNow() < ttl && pool.isNotEmpty()) pool else null
    }

    fun put(key: String, pool: List<Item>) {
        if (pool.isEmpty()) return
        this.key = key
        this.at = TimeSource.Monotonic.markNow()
        this.pool = pool
    }
}
