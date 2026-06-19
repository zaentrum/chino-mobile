package cloud.nalet.chino.mobile.ui.zap

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.URL

/** One card the prefetcher should warm: its master.m3u8 (already carrying the
 *  device caps + token) and the mid-scene seek the player will start on. */
data class ZapPrefetchRequest(val itemId: String, val masterUrl: String, val seekSec: Int)

/**
 * Client-side background prefetch for upcoming Zap cards. Given a small list of
 * upcoming cards it resolves each card's per-device HLS variant and writes the
 * init segment + the media segments covering the seek window [seekSec, seekSec
 * + WINDOW] into the shared [ZapMediaCache]. When the player swaps to that
 * card those bytes are already on disk, so the swipe begins instantly.
 *
 * It is deliberately a GOOD CITIZEN:
 *  - bounded fan-out: the caller only ever hands it the next [MAX_AHEAD] cards;
 *  - bounded payload: only the seek-window segments (~WINDOW seconds), never a
 *    whole movie;
 *  - capped concurrency: at most [MAX_CONCURRENT] segment downloads at once;
 *  - dedup: an id is warmed at most once per session (LRU eviction can drop
 *    bytes, but we don't re-issue work for the same id while the session lives);
 *  - cancellable: all work runs under one session [Job]; [cancel] kills every
 *    in-flight download when the user leaves Zap.
 *
 * This complements the SERVER-side warm (the prewarm POST stays as-is and tells
 * chino-stream to transcode the right segment); this class downloads the bytes
 * the server warmed.
 */
class ZapPrefetcher(context: Context) {
    private val appContext = context.applicationContext

    private val sessionJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + sessionJob)
    private val gate = Semaphore(MAX_CONCURRENT)

    /** ids already scheduled this session — dedup so a re-settle / refill that
     *  re-offers the same card doesn't re-download it. */
    private val scheduled = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Shared cache-backed factory used for the CacheWriter (downloads + writes
     *  through to the SimpleCache; reads served from cache are skipped). */
    private val cacheFactory: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(ZapMediaCache.cache(appContext))
        .setUpstreamDataSourceFactory(
            androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(ZapMediaCache.httpClient())
                .setUserAgent(PREFETCH_UA),
        )
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * Schedule a prefetch of up to [MAX_AHEAD] of the given upcoming cards that
     * haven't been warmed yet. Safe to call repeatedly (on feed-fill and on
     * each card settle) — already-scheduled ids are skipped.
     */
    fun prefetch(upcoming: List<ZapPrefetchRequest>) {
        var launched = 0
        for (req in upcoming) {
            if (launched >= MAX_AHEAD) break
            if (req.masterUrl.isBlank()) continue
            if (!scheduled.add(req.itemId)) continue
            launched++
            scope.launch {
                runCatching { warmCard(req) }
                    .onFailure {
                        // A failed warm shouldn't permanently blacklist the id —
                        // allow a later settle to retry it.
                        if (it !is kotlinx.coroutines.CancellationException) scheduled.remove(req.itemId)
                    }
            }
        }
    }

    /**
     * Cancel every in-flight + queued prefetch (call on leaving Zap). Cancels
     * the in-flight child jobs but keeps the scope alive so the SAME prefetcher
     * can warm again on a later Zap visit (it is mounted at app-shell scope).
     * Clears the dedup set so re-entering Zap can re-warm the new candidates.
     */
    fun cancel() {
        sessionJob.children.toList().forEach { it.cancel() }
        scheduled.clear()
    }

    /** Resolve one card and cache its init + seek-window segments. */
    private suspend fun warmCard(req: ZapPrefetchRequest) {
        when (val top = fetchPlaylist(req.masterUrl)) {
            // Some servers hand back a media playlist directly (single variant).
            is HlsMediaPlaylist -> cacheMediaPlaylist(top, req.seekSec)
            is HlsMultivariantPlaylist -> {
                // Pick the per-device variant the player would pick. chino-stream
                // emits the correctly-capped variant FIRST for the supplied caps,
                // and default ExoPlayer track selection starts at the first
                // variant, so the first media-playlist URL is the one to warm.
                val variantUrl = top.variants.firstOrNull()?.url
                    ?: top.mediaPlaylistUrls.firstOrNull()
                    ?: return
                val media = fetchPlaylist(variantUrl.toString()) as? HlsMediaPlaylist ?: return
                cacheMediaPlaylist(media, req.seekSec)
            }
            else -> return
        }
    }

    /** Cache the EXT-X-MAP init segment + the segments covering the seek window. */
    private suspend fun cacheMediaPlaylist(media: HlsMediaPlaylist, seekSec: Int) {
        val base = media.baseUri
        val windowStartUs = seekSec.toLong() * 1_000_000L
        val windowEndUs = windowStartUs + WINDOW_SEC * 1_000_000L

        // The init segment (EXT-X-MAP) is shared by all media segments; cache it
        // once. Any segment carries the same initializationSegment reference.
        media.segments.firstOrNull()?.initializationSegment?.let { init ->
            cacheSegment(resolve(base, init.url), init.byteRangeOffset, init.byteRangeLength)
        }

        var any = false
        for (seg in media.segments) {
            coroutineScopeEnsureActive()
            val segStartUs = seg.relativeStartTimeUs
            val segEndUs = segStartUs + seg.durationUs
            // Keep any segment that overlaps the [seekSec, seekSec+WINDOW) window.
            if (segEndUs <= windowStartUs || segStartUs >= windowEndUs) continue
            any = true
            cacheSegment(resolve(base, seg.url), seg.byteRangeOffset, seg.byteRangeLength)
        }
        // Defensive: if the window fell past the end (e.g. clamping), at least
        // warm the first media segment so the player isn't fully cold.
        if (!any) {
            media.segments.firstOrNull()?.let { seg ->
                cacheSegment(resolve(base, seg.url), seg.byteRangeOffset, seg.byteRangeLength)
            }
        }
    }

    /** Download (and write-through to the cache) a single segment / byte range,
     *  under the concurrency gate. Cancellable: leaving Zap cancels the scope,
     *  which calls [CacheWriter.cancel] to unblock the in-flight download. */
    private suspend fun cacheSegment(uri: String, byteOffset: Long, byteLength: Long) {
        gate.withPermit {
            coroutineScopeEnsureActive()
            val specBuilder = DataSpec.Builder().setUri(Uri.parse(uri))
            // A media3 byterange segment carries a non-default length/offset;
            // C.LENGTH_UNSET means "whole resource" — request it unbounded.
            if (byteLength != androidx.media3.common.C.LENGTH_UNSET.toLong()) {
                specBuilder.setPosition(byteOffset).setLength(byteLength)
            }
            val spec = specBuilder.build()
            val source = cacheFactory.createDataSourceForDownloading()
            val writer = CacheWriter(source, spec, null, null)
            try {
                kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                    // Tie blocking cache() to coroutine cancellation: the parent
                    // scope.cancel() interrupts this thread; cancel() the writer
                    // so cache() returns promptly with an interrupt.
                    writer.cache()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                writer.cancel()
                throw e
            } catch (_: Exception) {
                // A single bad segment shouldn't fail the whole card warm.
            }
        }
    }

    private suspend fun fetchPlaylist(url: String): HlsPlaylist? = withContext(Dispatchers.IO) {
        coroutineScopeEnsureActive()
        runCatching {
            val uri = Uri.parse(url)
            URL(url).openStream().use { input ->
                HlsPlaylistParser().parse(uri, input)
            }
        }.getOrNull()
    }

    /**
     * Resolve a playlist-relative segment/init reference against its base URI
     * using media3's OWN [UriUtil.resolveToUri] — the exact resolver
     * HlsMediaSource uses when it loads segments. Matching the resolver byte-for-
     * byte is what makes the prefetcher's cache key identical to the player's:
     * CacheKeyFactory.DEFAULT keys on the request URI string, so a mismatched
     * resolver (e.g. java.net.URL, which normalises differently) would write the
     * bytes under a key the player never looks up — a silent prefetch no-op.
     */
    private fun resolve(base: String, ref: String): String =
        runCatching { UriUtil.resolveToUri(base, ref).toString() }.getOrDefault(ref)

    private suspend fun coroutineScopeEnsureActive() {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
    }

    companion object {
        /** Warm at most this many not-yet-cached upcoming cards per call. */
        private const val MAX_AHEAD = 3
        /** At most this many segment downloads in flight at once. */
        private const val MAX_CONCURRENT = 2
        /** Seek-window length to cover from seekSec, in seconds (~one to two
         *  CMAF segments past the start so playback doesn't immediately stall). */
        private const val WINDOW_SEC = 12L
        private const val PREFETCH_UA = "chino-mobile/0.1 (Android; zap-prefetch)"
    }
}
