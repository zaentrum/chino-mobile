package cloud.nalet.chino.mobile.ui.player

import cloud.nalet.chino.mobile.data.api.Segment
import cloud.nalet.chino.mobile.data.model.Item

/**
 * The player's rules about segments, the up-next countdown and the heading —
 * the same on Android and iOS (chino-web's and the TV's): pure, so both
 * players read the same answers and the tests pin them.
 */

/** Segment kinds that get a manual "Skip …" pill. Matches web's
 *  skipSegment('intro' | 'credits' | 'recap'). Post-credits previews are
 *  excluded — they get the "Next episode" card instead (see
 *  [isPostCreditsPreview]). */
val SKIPPABLE_KINDS: Set<String> = setOf("intro", "recap", "credits")

/**
 * Position-based reclassification of a next-episode preview. The analyzer
 * mislabels post-credits "next time on…" teasers as `recap` (a "Previously
 * on…" opener), so a recap that STARTS at or after the credits is really a
 * post-roll preview, and an explicit `preview` kind always is. Such segments
 * get the "Next episode" card treatment (play the teaser, offer to jump)
 * instead of the skip-recap treatment. A genuine opening recap starts near
 * 0:00 and keeps the skip behaviour. Mirrors the TV implementation exactly.
 */
fun isPostCreditsPreview(seg: Segment, all: List<Segment>): Boolean = when (seg.kind.lowercase()) {
    "preview" -> true
    "recap" -> {
        val creditsStart = all
            .filter { it.kind.equals("credits", ignoreCase = true) }
            .minByOrNull { it.startMs }?.startMs
        creditsStart != null && seg.startMs >= creditsStart
    }
    else -> false
}

/** Whether [ms] (the PLAYED head, not a scrub preview) is inside a credits
 *  segment. */
fun inCredits(segments: List<Segment>, ms: Long): Boolean =
    segments.any { it.kind.equals("credits", ignoreCase = true) && ms >= it.startMs && ms < it.endMs }

/** The intro / recap / credits segment under [ms] that offers a manual skip,
 *  or null. A post-credits preview is not skippable. */
fun skippableSegmentAt(segments: List<Segment>, ms: Long): Segment? = segments.firstOrNull { seg ->
    ms >= seg.startMs && ms < seg.endMs &&
        seg.kind.lowercase() in SKIPPABLE_KINDS &&
        !isPostCreditsPreview(seg, segments)
}

/** The post-credits preview under [ms], or null — only meaningful when a
 *  next episode exists ([hasNext]). */
fun previewSegmentAt(segments: List<Segment>, ms: Long, hasNext: Boolean): Segment? {
    if (!hasNext) return null
    return segments.firstOrNull { seg -> ms >= seg.startMs && ms < seg.endMs && isPostCreditsPreview(seg, segments) }
}

/** Within the last 20 s, or past 95 % — whichever is later, so a short clip
 *  does not arm in its first half. Mirrors web's credits-or-95 % trigger. */
fun nearEnd(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0 && positionMs > 0 &&
        positionMs >= maxOf(durationMs - 20_000L, (durationMs * 0.95).toLong())

/** Where the up-next countdown arms: in the credits, or — for a title
 *  without a credits segment — near the end. */
fun atEnd(segments: List<Segment>, positionMs: Long, durationMs: Long): Boolean {
    val hasCredits = segments.any { it.kind.equals("credits", ignoreCase = true) }
    return inCredits(segments, positionMs) || (!hasCredits && nearEnd(positionMs, durationMs))
}

/** The start of the first credits segment, or null. */
fun creditsStartMs(segments: List<Segment>): Long? =
    segments.firstOrNull { it.kind.equals("credits", ignoreCase = true) }?.startMs

/** ~30 s before the cut-over (credits start, or 30 s from the end when
 *  unsegmented): when the next episode's stream is warmed, so chino-stream
 *  has a head start before the countdown. */
fun inPrewarmZone(positionMs: Long, creditsStartMs: Long?, durationMs: Long): Boolean =
    positionMs > 0 && when {
        creditsStartMs != null -> positionMs >= creditsStartMs - 30_000L
        durationMs > 0 -> positionMs >= durationMs - 30_000L
        else -> false
    }

/** Web wording for the manual skip pill ("Skip Intro" / "Skip Recap" /
 *  "Skip Credits"); an unknown kind reads "Skip <Kind>". */
fun skipSegmentLabel(kind: String): String = when (kind.lowercase()) {
    "intro" -> "Skip Intro"
    "recap" -> "Skip Recap"
    "credits" -> "Skip Credits"
    else -> "Skip ${kind.replaceFirstChar { it.uppercase() }}"
}

/** Friendly scrub-preview label for a segment — mirrors web's
 *  segmentDisplayLabel: a human chapter label, else the kind capitalised.
 *  Detector labels (numeric tags, dash-joined ranges) fall back to the kind so
 *  the preview does not surface raw analyzer noise. */
fun segmentDisplayLabel(seg: Segment): String {
    val friendlyKind = when (seg.kind.lowercase()) {
        "intro" -> "Intro"
        "credits" -> "Credits"
        "recap" -> "Recap"
        "chapter" -> "Chapter"
        else -> seg.kind.replaceFirstChar { it.uppercase() }
    }
    val raw = seg.label?.trim().orEmpty()
    if (seg.kind.equals("chapter", ignoreCase = true) && raw.isNotEmpty()) return raw
    if (raw.isEmpty()) return friendlyKind
    if (raw.contains('-')) return friendlyKind
    if (raw.first() in '0'..'9') return friendlyKind
    return raw
}

/**
 * The player heading. An episode (kind "episode", or any item with a season or
 * episode number) reads `{seriesTitle} — S01E02 · {episodeTitle}`, or
 * `S01E02 · {episodeTitle}` until the series title is known; anything else
 * its plain title. Mirrors chino-web's heading.
 */
fun composePlayerTitle(item: Item?, seriesTitle: String?): String {
    val episodeTitle = item?.title ?: "Playing"
    val isEpisode = item?.kind.equals("episode", ignoreCase = true) ||
        item?.seasonNumber != null || item?.episodeNumber != null
    if (item == null || !isEpisode) return episodeTitle
    val se = buildString {
        item.seasonNumber?.let { append("S").append(it.toString().padStart(2, '0')) }
        item.episodeNumber?.let { append("E").append(it.toString().padStart(2, '0')) }
    }
    val tail = if (se.isNotEmpty()) "$se · $episodeTitle" else episodeTitle
    return if (!seriesTitle.isNullOrBlank()) "$seriesTitle — $tail" else tail
}

/** "1:02:03" past an hour, else "2:03". */
fun formatTime(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    val ss = s.toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
}
