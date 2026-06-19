package cloud.nalet.chino.mobile.ui.zap

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Process-singleton on-disk media cache shared by the Zap preview player and
 * the [ZapPrefetcher]. The init segment + the seek-window media segments the
 * prefetcher writes here serve locally when the player swaps to that card, so
 * a swipe (and opening Zap on app-start, once the first card is warmed) begins
 * without a network round-trip.
 *
 * SimpleCache holds an exclusive lock on its directory and throws if a second
 * instance is constructed against the same dir in the same process, so it MUST
 * be built exactly once — hence this object + the double-checked [cache]. The
 * cache is bounded by an LRU evictor at [MAX_BYTES] (~256 MB) so the teaser
 * bytes never grow unbounded on disk.
 *
 * The cache key for both reader (player) and writer (prefetcher) is the
 * default one (the request URI), so a segment the prefetcher wrote under its
 * resolved URL is found by the player requesting that same URL — "correct
 * format per device" falls out because the master.m3u8 already encodes the
 * device caps, so both paths resolve to the same per-device variant URL.
 */
object ZapMediaCache {
    /** ~256 MB on-disk ceiling for cached Zap teaser bytes. */
    private const val MAX_BYTES: Long = 256L * 1024 * 1024
    private const val CACHE_DIR = "zap-cache"

    @Volatile
    private var instance: SimpleCache? = null

    @Volatile
    private var sharedClient: okhttp3.OkHttpClient? = null

    /** The single SimpleCache for this process. Built once, lazily. */
    fun cache(context: Context): Cache {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: SimpleCache(
                File(context.applicationContext.cacheDir, CACHE_DIR),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { instance = it }
        }
    }

    /**
     * One OkHttp client shared by the player upstream and the prefetcher so
     * connection pooling / TLS sessions are reused across both paths. Timeouts
     * mirror the player's existing stream client.
     */
    fun httpClient(): okhttp3.OkHttpClient {
        sharedClient?.let { return it }
        return synchronized(this) {
            sharedClient ?: okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build()
                .also { sharedClient = it }
        }
    }

    /**
     * Upstream HTTP -> on-disk-cache DataSource.Factory for the PLAYER.
     * Reads hit the cache first (so prefetched segments serve locally), misses
     * fall through to the network and are written back so a re-watch is warm.
     */
    fun playerDataSourceFactory(context: Context, userAgent: String): DataSource.Factory {
        val upstream = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(httpClient())
            .setUserAgent(userAgent)
        return CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(upstream)
            // Don't poison the cache on a partial/aborted read; just refetch.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
