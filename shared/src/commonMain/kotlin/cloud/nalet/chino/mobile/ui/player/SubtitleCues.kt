package cloud.nalet.chino.mobile.ui.player

/**
 * Subtitle cues for a player that draws subtitles itself (iOS: AVPlayer cannot
 * side-load a WebVTT file). Parses WebVTT — and SRT, as a .srt sidecar is
 * served as SRT even at its .vtt URL — into plain-text cues, and answers which
 * text is on screen at a time. A port of chino-tizen's parseSubtitleCues /
 * cueTextAt (src/lib/subtitles.ts), in milliseconds.
 */
data class SubtitleCue(
    /** Inclusive start, ms. */
    val startMs: Long,
    /** Exclusive end, ms. */
    val endMs: Long,
    /** Plain text, one line per line of the cue. */
    val text: String,
)

// "00:01:02.500", "00:01:02,500" (SRT), "01:02.500" (WebVTT without hours),
// "00:01:02". Cue settings may follow the end ("line:85% align:center").
private val TIMING = Regex(
    """^\s*((?:\d+:)?\d{1,2}:\d{1,2}(?:[.,]\d+)?)\s*-->\s*((?:\d+:)?\d{1,2}:\d{1,2}(?:[.,]\d+)?)""",
)
private val SRT_OVERRIDE = Regex("""\{\\[^}]*\}""")
private val MARKUP = Regex("""<[^>\n]*>""")
private val ENTITY = Regex("""&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);""")
private val COUNTER = Regex("""^\d+$""")

private val ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to "\u00A0", "lrm" to "\u200E", "rlm" to "\u200F",
)

private fun stampMs(stamp: String): Long {
    val parts = stamp.split('.', ',')
    var sec = 0L
    for (p in parts[0].split(':')) sec = sec * 60 + (p.toLongOrNull() ?: 0L)
    val fraction = parts.getOrNull(1).orEmpty()
    val ms = if (fraction.isEmpty()) 0L else fraction.take(3).padEnd(3, '0').toLong()
    return sec * 1000 + ms
}

private fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
    val e = m.groupValues[1]
    if (e.startsWith("#")) {
        val code = if (e.length > 1 && (e[1] == 'x' || e[1] == 'X')) e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull()
        if (code != null && code in 1..0x10FFFF) codePointToString(code) else m.value
    } else {
        ENTITIES[e.lowercase()] ?: m.value
    }
}

private fun codePointToString(code: Int): String =
    if (code < 0x10000) {
        code.toChar().toString()
    } else {
        val v = code - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }

/** A cue's text as plain lines: markup (<i>, <c.yellow>, <v Joe>, <font>,
 *  inline timestamps) and SRT override tags ({\an8}) removed, entities
 *  decoded, blank lines dropped. */
private fun cueText(lines: List<String>): String {
    val markupFree = MARKUP.replace(SRT_OVERRIDE.replace(lines.joinToString("\n"), ""), "")
    return decodeEntities(markupFree)
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}

/**
 * The cues of a WebVTT or SRT file, sorted by start (file order breaks ties).
 * The WEBVTT header, NOTE / STYLE / REGION blocks, cue identifiers and SRT
 * counters are skipped; a missing blank line between two SRT cues does not
 * swallow the next one; cues with no text or no duration are dropped.
 */
fun parseSubtitleCues(text: String): List<SubtitleCue> {
    val lines = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val cues = ArrayList<SubtitleCue>()
    var i = 0
    while (i < lines.size) {
        val m = TIMING.find(lines[i])
        if (m == null) {
            i++
            continue
        }
        val body = ArrayList<String>()
        var j = i + 1
        while (j < lines.size && lines[j].isNotBlank() && !TIMING.containsMatchIn(lines[j])) {
            body.add(lines[j])
            j++
        }
        // Ran into the next cue's timing: its SRT counter is the line before it.
        if (j < lines.size && TIMING.containsMatchIn(lines[j]) && body.isNotEmpty() && COUNTER.matches(body.last().trim())) {
            body.removeAt(body.size - 1)
        }
        val start = stampMs(m.groupValues[1])
        val end = stampMs(m.groupValues[2])
        val t = cueText(body)
        if (t.isNotEmpty() && end > start) cues.add(SubtitleCue(start, end, t))
        i = j
    }
    return cues.sortedBy { it.startMs }
}

/** The text on screen at [timeMs]: every cue that covers it, in order, one
 *  per line; "" when none does. */
fun cueTextAt(cues: List<SubtitleCue>, timeMs: Long): String {
    val shown = ArrayList<String>(2)
    for (c in cues) {
        if (c.startMs > timeMs) break
        if (timeMs < c.endMs) shown.add(c.text)
    }
    return shown.joinToString("\n")
}
