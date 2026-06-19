package cloud.nalet.chino.mobile.ui.zap

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cloud.nalet.chino.mobile.currentTimeMillis
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.model.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** One card the pager renders. randomRatio is rolled once per card so
 *  scrolling back up keeps the same scene; features fill in once the per-card
 *  detail fetch resolves (genres/cast are detail-only). The live playhead is
 *  fed back from the preview surface so expand resumes from the exact scene. */
class ZapCardState(
    val item: Item,
    val masterUrl: String,
    val seekSec: Int,
    val source: MidpointSource,
    /** Backdrop image shown full-bleed UNDER the preview surface during the
     *  video cold-start, then faded out once the player renders its first frame
     *  (mirrors chino-web's ZapCard backdrop→poster cold-start image). */
    val backdropUrl: String,
    /** Poster fallback used when the backdrop fails to load. */
    val posterUrl: String,
) {
    var features: ZapFeatures = ZapFeatures(type = item.kind)
    /** Wall-clock ms the card last became the active page (dwell measurement). */
    var activeSince: Long = 0L
    /** Most recent playhead the preview reported, in whole seconds. */
    var currentPositionSec: Int = 0
}

sealed interface ZapUiState {
    data object Loading : ZapUiState
    data object Empty : ZapUiState
    data class Active(val cards: List<ZapCardState>) : ZapUiState
}

/**
 * Orchestrates the mobile Zap reels feed. Owns the feed (ZapFeed), the
 * preference vector (ZapPreferences), and the zap_* telemetry funnel; the
 * ZapScreen owns the VerticalPager + per-card preview players and consumes
 * [state].cards. Builds the per-card master.m3u8 URL the same way the full
 * PlayerScreen does ($base/v1/items/{id}/play/master.m3u8?stream=&caps=&q=).
 *
 * Surfing must NOT touch progress / watched / continue-watching — only an
 * expand promotes a card into the real player at the scene the teaser reached.
 *
 * Mirrors chino-androidtv's ZapViewModel funnel; the UX is web-style (a pager
 * over [feed.queue]) rather than TV's single-player history, so card-left
 * classification fires on page settle (see [onPageSettled]).
 */
class ZapScreenModel(
    private val container: AppContainer,
    private val random: Random = Random.Default,
) : ScreenModel {
    private val api = container.chinoApi
    private val telemetry = container.telemetry
    private val baseUrl = container.config.apiBaseUrl.trimEnd('/')

    private val prefs = ZapPreferences()
    private val feed = ZapFeed(
        api = api,
        // Pool-level exploit ranking is type-only (list items lack genres/cast).
        scoreItem = { item -> prefs.score(ZapFeatures(type = item.kind)) },
        random = random,
    )

    private val _state = MutableStateFlow<ZapUiState>(ZapUiState.Loading)
    val state: StateFlow<ZapUiState> = _state.asStateFlow()

    private var token: String = ""
    private val caps: String = zapCodecCaps()

    // Pager-backed card list. Cards are appended as the queue refills; cards
    // already left (scrolled past) are NOT removed so scroll-back keeps them.
    private val cards = ArrayList<ZapCardState>()
    private var lastActiveIndex = -1
    // Impression / save dedup — session-scoped, mirrors web's *FiredRef sets.
    private val impressionsFired = HashSet<String>()
    private val prewarmed = HashSet<String>()
    // Client-side prefetch dedup — an id is handed to the platform prefetcher
    // at most once per session (mirrors prewarmed).
    private val prefetched = HashSet<String>()
    private val saved = HashSet<String>()

    init {
        telemetry.event("zap_session_start", extra = mapOf("source" to "nav"))
        load()
    }

    private fun load() {
        screenModelScope.launch {
            token = withContext(Dispatchers.Default) { runCatching { container.streamTokenManager.valid() }.getOrDefault("") }
            withContext(Dispatchers.Default) { feed.load() }
            ingestQueue()
            if (cards.isEmpty()) {
                _state.value = ZapUiState.Empty
                return@launch
            }
            _state.value = ZapUiState.Active(cards.toList())
            // First card becomes active immediately (page 0) — settle it so
            // the impression fires and we prewarm the next card.
            onPageSettled(0)
            // Client-side prefetch of the first upcoming cards so the very first
            // swipe (and re-entering Zap) begins from on-disk bytes.
            prefetchAhead(0)
        }
    }

    /** Drain whatever the feed currently has queued into the pager-backed
     *  card list (idempotent — only appends ids not already materialised).
     *
     *  card[0] is DETERMINISTIC: the top-ranked packaged candidate at the fixed
     *  [ZAP_FIRST_CARD_RATIO] seek — identical to what [ZapAppStartWarm] warmed,
     *  so the very first card plays from on-disk bytes. Cards at index >= 1 keep
     *  the existing random ε-greedy sampling. */
    private fun ingestQueue() {
        val have = cards.mapTo(HashSet()) { it.item.id }
        // First materialisation: pin the deterministic first card at index 0 so
        // it matches the app-start warm exactly (same candidate + seek + URL).
        if (cards.isEmpty()) {
            ZapFirstCard.pick(feed)?.let { first ->
                cards.add(
                    ZapCardState(
                        item = first.item,
                        masterUrl = buildMasterUrl(first.item.id),
                        seekSec = first.seekSec,
                        source = first.source,
                        backdropUrl = buildBackdropUrl(first.item.id),
                        posterUrl = buildPosterUrl(first.item.id),
                    ),
                )
                have.add(first.item.id)
                feed.markShown(first.item.id)
            }
        }
        for (item in feed.queue) {
            if (item.id in have) continue
            val ratio = random.nextDouble()
            val mid = pickZapMidpoint(item.durationMs, emptyList(), ratio)
            // Skip un-zappable items (too short / no usable mid-scene) so the
            // reels feed only surfaces real channel-surf teasers — mirrors
            // chino-web (ZapCard drops fallback / seek<=0 cards). markShown so
            // the feed won't re-sample the same id back into the queue.
            if (mid.source == MidpointSource.FALLBACK || mid.seekSec <= 0) {
                feed.markShown(item.id)
                continue
            }
            cards.add(
                ZapCardState(
                    item = item,
                    masterUrl = buildMasterUrl(item.id),
                    seekSec = mid.seekSec,
                    source = mid.source,
                    backdropUrl = buildBackdropUrl(item.id),
                    posterUrl = buildPosterUrl(item.id),
                ),
            )
            // Mark shown so the feed won't re-sample the same id into the
            // queue; the card stays in the pager regardless.
            feed.markShown(item.id)
        }
    }

    /**
     * Called by ZapScreen whenever the pager settles on a new page. Drives the
     * whole funnel: classify the card we just left (dwell → skip_fast/skip/
     * dwell), bump prefs, fire telemetry, top the queue up, fire the new
     * card's impression, resolve its detail features, and prewarm the next.
     */
    fun onPageSettled(index: Int) {
        val card = cards.getOrNull(index) ?: return
        if (index == lastActiveIndex) return

        // Classify + report the outgoing card (only when moving forward or
        // back to a genuinely different card).
        reportDwell(cards.getOrNull(lastActiveIndex))

        lastActiveIndex = index
        card.activeSince = currentTimeMillis()

        // Impression (deduped — pager fling can settle/re-settle the same id).
        if (impressionsFired.add(card.item.id)) {
            telemetry.event(
                "zap_impression",
                itemId = card.item.id,
                extra = baseExtra(card) + ("seek_source" to card.source.name.lowercase()),
            )
        }
        resolveFeatures(card)

        // Keep the queue topped up and materialise any new cards so the pager
        // has somewhere to scroll. Refill from a background dispatcher.
        screenModelScope.launch {
            withContext(Dispatchers.Default) { feed.refill() }
            val before = cards.size
            ingestQueue()
            if (cards.size != before) _state.value = ZapUiState.Active(cards.toList())
            // Warm the upcoming cards from the settled index. Runs AFTER ingest
            // so any newly-materialised cards are included; a single call per
            // settle (the prefetcher dedups by id, so this also covers the
            // already-present cards ahead of the player).
            prefetchAhead(index)
        }

        prewarmNext(index)
    }

    /** Report the live playhead so expand resumes from the exact scene. */
    fun onPositionUpdate(index: Int, positionSec: Int) {
        cards.getOrNull(index)?.currentPositionSec = positionSec
    }

    /** The teaser played to the natural end of source. */
    fun onComplete(index: Int) {
        val card = cards.getOrNull(index) ?: return
        prefs.update(card.features, ZapPreferences.COMPLETE)
        telemetry.event("zap_complete", itemId = card.item.id, extra = baseExtra(card))
    }

    /** Tap-to-expand → hand off to the full player. Returns the absolute resume
     *  position to pass through to PlayerScreen (the playhead the teaser
     *  reached, or the scene start if it never advanced). */
    fun onExpand(index: Int): Pair<String, Int>? {
        val card = cards.getOrNull(index) ?: return null
        prefs.update(card.features, ZapPreferences.EXPAND)
        telemetry.event("zap_expand", itemId = card.item.id, extra = baseExtra(card))
        val resume = if (card.currentPositionSec > 0) card.currentPositionSec else card.seekSec
        return card.item.id to resume
    }

    /** Sound on/off toggle telemetry (mirrors web's zap_mute_toggle). The
     *  muted state itself is lifted into ZapScreen and applies to every card. */
    fun onMuteToggle(muted: Boolean) {
        val card = cards.getOrNull(lastActiveIndex) ?: return
        telemetry.event(
            "zap_mute_toggle",
            itemId = card.item.id,
            extra = baseExtra(card) + ("muted" to muted.toString()),
        )
    }

    /** True after [onSaveToggle] adds the id — drives the bookmark fill. */
    fun isSaved(id: String): Boolean = id in saved

    /** Save/watchlist toggle. Mirrors web's onSaveToggle: a save bumps prefs
     *  by the SAVE strength and writes through to the watchlist; telemetry
     *  fires for both directions. */
    fun onSaveToggle(index: Int) {
        val card = cards.getOrNull(index) ?: return
        val id = card.item.id
        val nowSaved = id !in saved
        if (nowSaved) {
            saved.add(id)
            prefs.update(card.features, ZapPreferences.SAVE)
        } else {
            saved.remove(id)
        }
        telemetry.event(
            "zap_save",
            itemId = id,
            extra = baseExtra(card) + ("saved" to nowSaved.toString()),
        )
        // Reuse the shared watchlist path so a Zap save shows up on Detail.
        screenModelScope.launch {
            runCatching { container.userFlags.setWatchlist(id, nowSaved) }
        }
        _state.value = ZapUiState.Active(cards.toList())
    }

    /**
     * Hand the next [PREFETCH_AHEAD] not-yet-warmed upcoming cards to the
     * platform prefetcher so their init + seek-window segments are downloaded
     * into the on-disk media cache before the user swipes. Deduped per session
     * here (the platform side dedups again and bounds concurrency); a no-op on
     * platforms without a prefetcher (iOS). This is the CLIENT side of the warm
     * — [prewarmNext] still tells the SERVER to transcode the right segment.
     */
    private fun prefetchAhead(fromIndex: Int) {
        val upcoming = ArrayList<ZapPrefetchCard>(PREFETCH_AHEAD)
        var i = fromIndex + 1
        while (i < cards.size && upcoming.size < PREFETCH_AHEAD) {
            val card = cards[i]
            if (prefetched.add(card.item.id)) {
                upcoming.add(ZapPrefetchCard(card.item.id, card.masterUrl, card.seekSec))
            }
            i++
        }
        if (upcoming.isNotEmpty()) ZapPrefetchBridge.prefetch(upcoming)
    }

    /** Fire-and-forget warm of the next card's transcode pipeline, deduped
     *  per session (web's prewarmedRef pattern). */
    private fun prewarmNext(index: Int) {
        val next = cards.getOrNull(index + 1) ?: return
        if (!prewarmed.add(next.item.id)) return
        screenModelScope.launch {
            runCatching {
                // Pass the next card's mid-scene seek so chino-stream warms the
                // segment the player will actually start on, not segment 0.
                api.prewarm(next.item.id, caps = caps.ifEmpty { null }, quality = TEASER_QUALITY, seekSec = next.seekSec)
            }
        }
    }

    /** Fetch detail for genres/cast so dwell/expand signals learn the full
     *  feature vector (non-gating — the teaser already plays on the percent
     *  midpoint). */
    private fun resolveFeatures(card: ZapCardState) {
        screenModelScope.launch {
            val detail = withContext(Dispatchers.Default) {
                runCatching { api.getItem(card.item.id) }.getOrNull()
            } ?: return@launch
            val castNames = detail.cast
                .filter { it.role == null || it.role.equals("actor", ignoreCase = true) }
                .map { it.name }
            card.features = ZapFeatures(
                type = detail.kind ?: card.item.kind,
                genres = detail.genres,
                castNames = castNames,
            )
        }
    }

    /** Classify how long the outgoing card was watched and learn from it. */
    private fun reportDwell(card: ZapCardState?) {
        if (card == null || card.activeSince == 0L) return
        val dwellMs = currentTimeMillis() - card.activeSince
        card.activeSince = 0L
        if (dwellMs < ZapPreferences.DWELL_NOISE_FLOOR_MS) return
        val (kind, strength) = when {
            dwellMs < ZapPreferences.FAST_SKIP_MS -> "zap_skip_fast" to ZapPreferences.SKIP_FAST
            dwellMs < ZapPreferences.DWELL_MS -> "zap_skip" to ZapPreferences.SKIP_NORMAL
            else -> "zap_dwell" to ZapPreferences.DWELL_LONG
        }
        prefs.update(card.features, strength)
        telemetry.event(kind, itemId = card.item.id, extra = baseExtra(card) + ("dwell_ms" to dwellMs.toString()))
    }

    private fun baseExtra(card: ZapCardState): Map<String, String> = buildMap {
        card.features.type?.let { put("type", it) }
        if (card.features.genres.isNotEmpty()) put("genres", card.features.genres.joinToString(","))
    }

    // Built via the shared [ZapFirstCard.masterUrl] so the Zap screen and the
    // app-start warm produce byte-identical URLs (same cache keys).
    private fun buildMasterUrl(id: String): String =
        ZapFirstCard.masterUrl(baseUrl, id, token, caps)

    // Artwork URLs use the same signed-stream-token pattern as Hero / Detail /
    // poster shelves ($base/v1/items/{id}/backdrop|poster?stream=). Built with
    // the session stream token so the cold-start image loads while the video
    // is still buffering.
    private fun buildBackdropUrl(id: String): String =
        "$baseUrl/v1/items/$id/backdrop?stream=$token"

    private fun buildPosterUrl(id: String): String =
        "$baseUrl/v1/items/$id/poster?stream=$token"

    private var closed = false

    /** Final dwell + session close so the funnel is complete. Idempotent and
     *  public because this ScreenModel is created with `remember {}` (not
     *  Voyager's rememberScreenModel), so Voyager never calls [onDispose] —
     *  ZapScreen drives this from a DisposableEffect when the tab is left. */
    fun closeSession() {
        if (closed) return
        closed = true
        // Stop any in-flight client prefetch the moment Zap is left.
        ZapPrefetchBridge.cancel()
        reportDwell(cards.getOrNull(lastActiveIndex))
        telemetry.event("zap_session_end")
    }

    override fun onDispose() = closeSession()

    companion object {
        // Shared with the app-start warm + first-card URL builder.
        private const val TEASER_QUALITY = ZapFirstCard.TEASER_QUALITY
        /** How many upcoming cards to client-prefetch ahead of the player. */
        private const val PREFETCH_AHEAD = 3
    }
}
