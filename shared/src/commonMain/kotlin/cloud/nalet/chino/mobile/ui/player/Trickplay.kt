package cloud.nalet.chino.mobile.ui.player

/**
 * Trickplay (scrub-preview thumbnails) parser + lookup helpers. Pure
 * Kotlin so it lives in commonMain and stays testable on every target —
 * the Android player owns the sprite rendering (androidMain), this file
 * only owns the parse + cue lookup.
 *
 * Mirrors chino-web's src/lib/trickplay.ts field-for-field. The analyzer
 * writes a WebVTT cue file alongside a set of sprite-sheet JPGs at
 * /api/v1/items/{id}/play/trickplay/. Each cue maps a time range to one
 * tile inside one sprite sheet:
 *
 *   00:01:20.000 --> 00:01:30.000
 *   sprite-0001.jpg#xywh=320,180,320,180
 *
 * On scrub we (1) translate the playhead to a timestamp, (2) find the
 * matching cue, (3) render the sprite with the right tile cropped in.
 */
data class TrickplayCue(
    /** Inclusive start, in milliseconds. */
    val startMs: Long,
    /** Exclusive end, in milliseconds. */
    val endMs: Long,
    /** Sprite-sheet filename, relative to the trickplay/ directory. */
    val sprite: String,
    /** Pixel offsets of the tile inside its sprite sheet. */
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

// Anchored to line start so a stray timestamp inside a cue payload can't
// be mistaken for a cue boundary. Web's regex is identical (sans the
// Kotlin escaping). Fractional seconds are optional.
private val CUE_TIMING_RE =
    Regex("""^(\d+):(\d{2}):(\d{2}(?:\.\d+)?)\s+-->\s+(\d+):(\d{2}):(\d{2}(?:\.\d+)?)""")
private val PAYLOAD_RE =
    Regex("""^(.+?)#xywh=(\d+),(\d+),(\d+),(\d+)\s*$""")

/**
 * Parses a WebVTT thumbnails file into a list of cues. Tolerant of
 * missing blank lines + a leading BOM; ignores cues whose payload doesn't
 * have the expected `sprite.jpg#xywh=x,y,w,h` shape. We don't enforce a
 * WEBVTT header — some tooling omits it and the cues still parse.
 *
 * Times are stored in milliseconds (the Android scrubber works in ms),
 * unlike web's seconds — parseFloat on the seconds field is multiplied
 * by 1000 and rounded so sub-second precision survives.
 */
fun parseTrickplayVtt(text: String): List<TrickplayCue> {
    val cues = ArrayList<TrickplayCue>()
    val lines = text.removePrefix("﻿").split(Regex("\r?\n"))
    var i = 0
    while (i < lines.size) {
        val m = CUE_TIMING_RE.find(lines[i])
        if (m == null) { i++; continue }
        val g = m.groupValues
        val startMs = hmsToMs(g[1], g[2], g[3])
        val endMs = hmsToMs(g[4], g[5], g[6])
        // The payload sits on the next non-empty line.
        var payload = ""
        var j = i + 1
        while (j < lines.size) {
            val t = lines[j].trim()
            if (t.isNotEmpty()) { payload = t; break }
            j++
        }
        val p = PAYLOAD_RE.find(payload)
        if (p != null) {
            val pg = p.groupValues
            cues.add(
                TrickplayCue(
                    startMs = startMs,
                    endMs = endMs,
                    sprite = pg[1],
                    x = pg[2].toInt(),
                    y = pg[3].toInt(),
                    w = pg[4].toInt(),
                    h = pg[5].toInt(),
                ),
            )
        }
        i++
    }
    return cues
}

private fun hmsToMs(h: String, m: String, s: String): Long {
    val seconds = h.toLong() * 3600 + m.toLong() * 60 + s.toDouble().toLong()
    val fracMs = ((s.toDouble() - s.toDouble().toLong()) * 1000.0)
    return seconds * 1000L + fracMs.toLong()
}

/**
 * Find the cue covering `tMs`. O(log n) via binary search — the scrub
 * handler fires on every drag delta, so the search has to stay cheap even
 * when the cue list runs into the thousands. Returns null when no cue
 * spans the time (before the first cue, after the last, or empty list).
 */
fun findTrickplayCue(cues: List<TrickplayCue>, tMs: Long): TrickplayCue? {
    if (cues.isEmpty()) return null
    var lo = 0
    var hi = cues.size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val c = cues[mid]
        when {
            tMs < c.startMs -> hi = mid - 1
            tMs >= c.endMs -> lo = mid + 1
            else -> return c
        }
    }
    return null
}
