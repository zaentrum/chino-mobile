package cloud.nalet.chino.mobile.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The cue files the iOS overlay draws: WebVTT and SRT parsed to plain text,
 *  and the text on screen at a time (chino-tizen's subtitles.test.ts cases). */
class SubtitleCuesTest {
    @Test
    fun webVttHeaderIdsCueSettingsMarkupAndEntities() {
        val vtt = listOf(
            "﻿WEBVTT",
            "X-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000",
            "",
            "NOTE a comment",
            "",
            "STYLE",
            "::cue { color: yellow }",
            "",
            "intro-1",
            "00:00:01.000 --> 00:00:03.500 line:85% align:center",
            "<v Joe><i>Hello</i> &amp; welcome</v>",
            "",
            "01:02.250 --> 01:04.000",
            "<c.yellow>Two</c>",
            "lines &lt;3",
            "",
        ).joinToString("\n")

        assertEquals(
            listOf(
                SubtitleCue(1_000, 3_500, "Hello & welcome"),
                SubtitleCue(62_250, 64_000, "Two\nlines <3"),
            ),
            parseSubtitleCues(vtt),
        )
    }

    @Test
    fun srtCountersCommaDecimalsCrlfOverrideTagsAMissingBlankLine() {
        val srt = listOf(
            "1",
            "00:00:05,000 --> 00:00:07,250",
            "{\\an8}<font color=\"#ffff00\">Top</font>",
            "2",
            "00:00:08,000 --> 00:00:09,000",
            "Next",
            "",
            "3",
            "00:00:10,000 --> 00:00:10,000",
            "Zero length",
            "",
        ).joinToString("\r\n")

        assertEquals(
            listOf(SubtitleCue(5_000, 7_250, "Top"), SubtitleCue(8_000, 9_000, "Next")),
            parseSubtitleCues(srt),
        )
    }

    @Test
    fun hoursFractionsAndNumericEntities() {
        val cues = parseSubtitleCues(
            "WEBVTT\n\n01:00:00.5 --> 01:00:02.25\nCaf&#233; &#x1F600; &nbsp;x &unknown;\n",
        )
        assertEquals(listOf(SubtitleCue(3_600_500, 3_602_250, "Café 😀  x &unknown;")), cues)
    }

    @Test
    fun cuesComeSortedAndEmptyOrBrokenFilesGiveNone() {
        val cues = parseSubtitleCues(
            "WEBVTT\n\n00:00:09.000 --> 00:00:10.000\nlater\n\n00:00:01.000 --> 00:00:02.000\nearlier\n",
        )
        assertEquals(listOf("earlier", "later"), cues.map { it.text })
        assertTrue(parseSubtitleCues("").isEmpty())
        assertTrue(parseSubtitleCues("<html>404 page not found</html>").isEmpty())
        assertTrue(parseSubtitleCues("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<i></i>\n").isEmpty())
    }

    @Test
    fun theTextOnScreenCoveringCuesInOrderEndExclusive() {
        val cues = parseSubtitleCues(
            listOf("WEBVTT", "", "00:00:01.000 --> 00:00:05.000", "A", "", "00:00:02.000 --> 00:00:03.000", "B", "")
                .joinToString("\n"),
        )
        assertEquals("", cueTextAt(cues, 500))
        assertEquals("A", cueTextAt(cues, 1_000))
        assertEquals("A\nB", cueTextAt(cues, 2_500))
        assertEquals("A", cueTextAt(cues, 3_000))
        assertEquals("", cueTextAt(cues, 5_000))
    }

    @Test
    fun theMockServersSidecarDrawsInTime() {
        // The shape chino-api's /play/subs/{id}.vtt serves for an English
        // sidecar: a cue every four seconds, three seconds long.
        fun ts(sec: Int) = "00:00:" + sec.toString().padStart(2, '0') + ".000"
        val vtt = buildString {
            append("WEBVTT\n\n")
            for (t in 2 until 30 step 4) {
                append("${ts(t)} --> ${ts(t + 3)} line:85% align:center\n")
                append("<i>English</i> subtitle &amp; second $t\n\n")
            }
        }
        val cues = parseSubtitleCues(vtt)
        assertEquals(7, cues.size)
        assertEquals("English subtitle & second 10", cueTextAt(cues, 11_999))
        assertEquals("", cueTextAt(cues, 13_000))
    }
}
