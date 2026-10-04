package cloud.nalet.chino.mobile.ui.zap

/**
 * Where a Zap card starts in its HLS master, which is what its bytes are
 * warmed for: the master's first variant — chino-stream lists first the one
 * a client is to start on, for the caps the URL carries — and, of the audio,
 * the rendition that variant starts with: the DEFAULT one of the audio group
 * it names, else that group's first.
 *
 * chino-stream warms exactly that when the master is fetched (ladder.go,
 * served), hls.js and AVPlayer start there, and chino-web's prefetch warms
 * it (lib/zapPrefetch.ts). The Android prefetcher warms it, and the Android
 * card starts on it (ZapPreviewPlayer, FirstVariantTrackSelection).
 */

/**
 * The audio rendition a client starts on: of [renditions] (a master's
 * TYPE=AUDIO renditions that have a URI, in master order), the DEFAULT one
 * of [firstVariantGroup] — the AUDIO group of the master's first variant —
 * else that group's first. Null when the first variant names no group (its
 * audio is in its own segments) or the group has no rendition.
 */
fun <R> startAudioRendition(
    firstVariantGroup: String?,
    renditions: List<R>,
    group: (R) -> String?,
    isDefault: (R) -> Boolean,
): R? {
    if (firstVariantGroup.isNullOrEmpty()) return null
    val inGroup = renditions.filter { group(it) == firstVariantGroup }
    return inGroup.firstOrNull(isDefault) ?: inGroup.firstOrNull()
}
