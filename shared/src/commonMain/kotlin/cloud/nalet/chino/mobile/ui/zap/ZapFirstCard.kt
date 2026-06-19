package cloud.nalet.chino.mobile.ui.zap

import cloud.nalet.chino.mobile.data.model.Item

/**
 * The DETERMINISTIC first Zap card — shared by the Zap screen (card[0]) and the
 * app-start warm so both warm + play the SAME bytes.
 *
 * The first card is intentionally NOT randomly sampled: it is the top-ranked
 * packaged candidate ([ZapFeed.topRankedCandidate]) at a FIXED seek ratio
 * ([ZAP_FIRST_CARD_RATIO]). The master.m3u8 URL is built the exact same way the
 * full PlayerScreen / [ZapScreenModel] build it (same base, same shared session
 * stream token, same caps, same teaser quality), so the segment URLs chino-stream
 * rewrites — and therefore the on-disk cache keys — are identical between the
 * app-start warm and the surface the user actually plays.
 *
 * Subsequent cards (index >= 1) keep the existing random ε-greedy sampling.
 */
object ZapFirstCard {
    /** Keep in lockstep with [ZapScreenModel.TEASER_QUALITY] and the warm. */
    const val TEASER_QUALITY = "medium"

    /** The deterministic [Item] for card[0], or null if the pool has no
     *  zappable top candidate. */
    fun pick(feed: ZapFeed): Resolved? {
        val item = feed.topRankedCandidate() ?: return null
        val mid = pickZapFirstCardMidpoint(item.durationMs)
        // Mirror the ingest filter: skip items too short to land mid-scene.
        if (mid.source == MidpointSource.FALLBACK || mid.seekSec <= 0) return null
        return Resolved(item, mid.seekSec, mid.source)
    }

    /** Build the master.m3u8 URL for [id] identically to [ZapScreenModel]. */
    fun masterUrl(baseUrl: String, id: String, token: String, caps: String): String = buildString {
        append("${baseUrl.trimEnd('/')}/v1/items/$id/play/master.m3u8?stream=$token")
        if (caps.isNotEmpty()) append("&caps=$caps")
        append("&q=$TEASER_QUALITY")
    }

    data class Resolved(val item: Item, val seekSec: Int, val source: MidpointSource)
}
