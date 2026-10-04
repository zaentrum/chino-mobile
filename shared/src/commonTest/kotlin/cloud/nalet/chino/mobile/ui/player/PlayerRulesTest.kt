package cloud.nalet.chino.mobile.ui.player

import cloud.nalet.chino.mobile.data.api.PlayInfo
import cloud.nalet.chino.mobile.data.api.PlaybackRung
import cloud.nalet.chino.mobile.data.api.QualityRung
import cloud.nalet.chino.mobile.data.api.Segment
import cloud.nalet.chino.mobile.data.model.Item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules both players share: segments, the up-next trigger, the heading,
 *  the quality menu and the master URL. */
class PlayerRulesTest {
    private val episode = listOf(
        Segment("intro", 4_000, 30_000),
        Segment("credits", 205_000, 230_000),
        // A post-credits "next time on…" teaser the analyzer called a recap.
        Segment("recap", 230_000, 240_000),
    )

    @Test
    fun aRecapAfterTheCreditsIsAPreviewOneBeforeIsSkippable() {
        val opening = Segment("recap", 0, 20_000)
        assertFalse(isPostCreditsPreview(opening, episode + opening))
        assertTrue(isPostCreditsPreview(episode[2], episode))
        assertTrue(isPostCreditsPreview(Segment("preview", 10, 20), emptyList()))
        // Without credits a recap is a recap.
        assertFalse(isPostCreditsPreview(Segment("recap", 230_000, 240_000), emptyList()))
    }

    @Test
    fun theSkipPillFollowsThePlayedHead() {
        assertEquals("intro", skippableSegmentAt(episode, 4_000)?.kind)
        assertNull(skippableSegmentAt(episode, 30_000)) // end exclusive
        assertEquals("credits", skippableSegmentAt(episode, 229_999)?.kind)
        assertNull(skippableSegmentAt(episode, 235_000)) // the preview is not skipped
        assertEquals("Skip Intro", skipSegmentLabel("intro"))
        assertEquals("Skip Credits", skipSegmentLabel("CREDITS"))
        assertEquals("Skip Opening", skipSegmentLabel("opening"))
    }

    @Test
    fun thePreviewCardOnlyWithANextEpisode() {
        assertEquals("recap", previewSegmentAt(episode, 235_000, hasNext = true)?.kind)
        assertNull(previewSegmentAt(episode, 235_000, hasNext = false))
        assertNull(previewSegmentAt(episode, 100_000, hasNext = true))
    }

    @Test
    fun upNextArmsInTheCreditsOrNearTheEndOfAnUnsegmentedTitle() {
        assertTrue(atEnd(episode, 206_000, 240_000))
        assertFalse(atEnd(episode, 200_000, 240_000))
        // Credits exist, so the 20 s window does not arm on its own.
        assertFalse(atEnd(episode, 235_000, 240_000))
        // Unsegmented: the later of 20 s from the end and 95 %.
        assertTrue(atEnd(emptyList(), 228_000, 240_000))
        assertFalse(atEnd(emptyList(), 227_000, 240_000))
        assertTrue(nearEnd(38_000, 40_000)) // a 40 s clip arms at 95 %, not at 20 s
        assertFalse(nearEnd(21_000, 40_000))
        assertFalse(nearEnd(0, 0))
    }

    @Test
    fun theNextEpisodeIsWarmedThirtySecondsAhead() {
        assertEquals(205_000L, creditsStartMs(episode))
        assertTrue(inPrewarmZone(175_000, 205_000, 240_000))
        assertFalse(inPrewarmZone(174_999, 205_000, 240_000))
        assertTrue(inPrewarmZone(210_000, null, 240_000))
        assertFalse(inPrewarmZone(0, null, 240_000))
        assertFalse(inPrewarmZone(10_000, null, 0))
    }

    @Test
    fun scrubPreviewLabelsSkipDetectorNoise() {
        assertEquals("Intro", segmentDisplayLabel(Segment("intro", 0, 1)))
        assertEquals("The Heist", segmentDisplayLabel(Segment("chapter", 0, 1, label = "The Heist")))
        assertEquals("Credits", segmentDisplayLabel(Segment("credits", 0, 1, label = "00:10-00:40")))
        assertEquals("Recap", segmentDisplayLabel(Segment("recap", 0, 1, label = "12 seg")))
        assertEquals("Previously", segmentDisplayLabel(Segment("recap", 0, 1, label = "Previously")))
    }

    @Test
    fun theHeadingNamesTheSeriesAndTheEpisode() {
        val ep = Item(id = "e1", title = "Pilot", kind = "episode", seasonNumber = 1, episodeNumber = 2, parentId = "s1")
        assertEquals("Test Series — S01E02 · Pilot", composePlayerTitle(ep, "Test Series"))
        assertEquals("S01E02 · Pilot", composePlayerTitle(ep, null))
        assertEquals("A Film", composePlayerTitle(Item(id = "m1", title = "A Film", kind = "movie"), "ignored"))
        assertEquals("Playing", composePlayerTitle(null, null))
    }

    @Test
    fun timeReadsLikeTheOtherClients() {
        assertEquals("0:00", formatTime(0))
        assertEquals("3:07", formatTime(187))
        assertEquals("1:02:03", formatTime(3723))
        assertEquals("10:00:00", formatTime(36_000))
    }

    @Test
    fun packagedRungsOfferAutoAndEachRenditionTallestFirst() {
        val ladder = qualityLadder(
            PlayInfo(
                mode = "packaged",
                defaultQuality = "v0",
                rungs = listOf(
                    PlaybackRung(id = "v1", height = 360, bitrate = 600_000),
                    PlaybackRung(id = "v0", height = 1080, codec = "hvc1.2.4.L120.B0", label = "1080p HDR"),
                    PlaybackRung(id = "v2"),
                ),
            ),
        )
        assertEquals(AUTO_QUALITY, ladder.initial)
        assertEquals(
            listOf("auto" to "Auto", "v0" to "1080p HDR", "v1" to "360p", "v2" to "v2"),
            ladder.options.map { it.id to it.label },
        )
        assertTrue(ladder.pickable)
    }

    @Test
    fun theOnTheFlyLadderAsTheServerNamesIt() {
        val ladder = qualityLadder(
            PlayInfo(
                mode = "transcode",
                qualities = listOf(
                    QualityRung("high", "High (source resolution)"),
                    QualityRung("medium", "Medium (720p)"),
                    QualityRung("low", ""),
                ),
                defaultQuality = "high",
            ),
        )
        assertEquals("high", ladder.initial)
        assertEquals(listOf("High (source resolution)", "Medium (720p)", "480p"), ladder.options.map { it.label })
        // A packaged title the server lists no rungs for: nothing to pick, its
        // default rendition requested — what the Android player sends today.
        val packaged = qualityLadder(PlayInfo(mode = "packaged", defaultQuality = "v0"))
        assertFalse(packaged.pickable)
        assertEquals("v0", packaged.initial)
        assertEquals("high", qualityLadder(null).initial)
    }

    @Test
    fun theMasterUrlIsTheAndroidPlayers() {
        assertEquals(
            "https://media.example.org/api/v1/items/m1/play/master.m3u8?stream=tok&caps=avc,aac,mp3&q=high",
            buildMasterUrl("https://media.example.org/api/", "m1", "tok", "high", "avc,aac,mp3"),
        )
        assertEquals(
            "https://media.example.org/api/v1/items/e1/play/master.m3u8?stream=tok&q=auto",
            buildMasterUrl("https://media.example.org/api", "e1", "tok", AUTO_QUALITY, ""),
        )
    }
}
