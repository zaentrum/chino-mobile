package cloud.nalet.chino.mobile.data.downloads

import cloud.nalet.chino.mobile.data.api.SidecarSubtitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A package's master as chino-stream serves it: three rungs, one audio
 *  rendition, every URI signed with the token it was asked with. */
private val PACKAGE_MASTER = """
    #EXTM3U
    ## Media playlists by packager version v3.4.2; master assembled by the packager

    #EXT-X-INDEPENDENT-SEGMENTS

    #EXT-X-MEDIA:TYPE=AUDIO,URI="a0/playlist.m3u8?stream=T&caps=avc,hvc&v=7",GROUP-ID="audio",LANGUAGE="en",NAME="English",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="2"

    #EXT-X-STREAM-INF:BANDWIDTH=18125550,AVERAGE-BANDWIDTH=12604861,CODECS="hvc1.1.2.L180.90,mp4a.40.2",RESOLUTION=7680x4320,FRAME-RATE=24.000,VIDEO-RANGE=SDR,AUDIO="audio",CLOSED-CAPTIONS=NONE
    v0/playlist.m3u8?stream=T&caps=avc,hvc&v=7
    #EXT-X-STREAM-INF:BANDWIDTH=2405185,AVERAGE-BANDWIDTH=1691850,CODECS="hvc1.1.2.L93.90,mp4a.40.2",RESOLUTION=1280x720,FRAME-RATE=24.000,VIDEO-RANGE=SDR,AUDIO="audio",CLOSED-CAPTIONS=NONE
    v1/playlist.m3u8?stream=T&caps=avc,hvc&v=7
    #EXT-X-STREAM-INF:BANDWIDTH=1377165,AVERAGE-BANDWIDTH=990605,CODECS="hvc1.1.2.L90.90,mp4a.40.2",RESOLUTION=854x480,FRAME-RATE=24.000,VIDEO-RANGE=SDR,AUDIO="audio",CLOSED-CAPTIONS=NONE
    v3/playlist.m3u8?stream=T&caps=avc,hvc&v=7

    #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=830459,AVERAGE-BANDWIDTH=468428,CODECS="hvc1.1.2.L180.90",RESOLUTION=7680x4320,URI="v0/iframes.m3u8?stream=T"
""".trimIndent()

/** A film with a dub and its original, subtitles in the master, a rendition
 *  without a URI (muxed) and a variant listed twice. */
private val DUBBED_MASTER = """
    #EXTM3U
    #EXT-X-MEDIA:TYPE=AUDIO,URI="a0/playlist.m3u8",GROUP-ID="stereo",LANGUAGE="de",NAME="Deutsch",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="2"
    #EXT-X-MEDIA:TYPE=AUDIO,URI="a1/playlist.m3u8",GROUP-ID="stereo",LANGUAGE="en",NAME="English",DEFAULT=NO,CHANNELS="2"
    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="stereo",LANGUAGE="fr",NAME="Muxed"
    #EXT-X-MEDIA:TYPE=AUDIO,URI="a2/playlist.m3u8",GROUP-ID="stereo",LANGUAGE="it",NAME="Italiano",DEFAULT=NO,CHANNELS="2"
    #EXT-X-MEDIA:TYPE=SUBTITLES,URI="s0/playlist.m3u8",GROUP-ID="subs",LANGUAGE="de",NAME="Deutsch (forced)",FORCED=YES
    #EXT-X-STREAM-INF:BANDWIDTH=6000000,AVERAGE-BANDWIDTH=4000000,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1920x800,AUDIO="stereo",SUBTITLES="subs"
    v0/playlist.m3u8
    #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x536,AUDIO="stereo"
    v1/playlist.m3u8
    #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x536,AUDIO="stereo"
    v1/playlist.m3u8
""".trimIndent()

class HlsMasterTest {
    @Test
    fun variantsAndRenditionsAreReadInTheMastersOrder() {
        val m = parseHlsMaster(PACKAGE_MASTER)
        assertEquals(listOf(0, 1, 2), m.variants.map { it.index })
        assertEquals(
            listOf("v0/playlist.m3u8?stream=T&caps=avc,hvc&v=7", "v1/playlist.m3u8?stream=T&caps=avc,hvc&v=7", "v3/playlist.m3u8?stream=T&caps=avc,hvc&v=7"),
            m.variants.map { it.uri },
        )
        val v1 = m.variants[1]
        assertEquals(2_405_185L, v1.bandwidth)
        assertEquals(1_691_850L, v1.averageBandwidth)
        assertEquals(1280, v1.width)
        assertEquals(720, v1.height)
        // A quoted list keeps its commas.
        assertEquals("hvc1.1.2.L93.90,mp4a.40.2", v1.codecs)
        assertEquals("audio", v1.audioGroup)
        val a = m.audios.single()
        assertEquals("a0/playlist.m3u8?stream=T&caps=avc,hvc&v=7", a.uri)
        assertEquals("en", a.language)
        assertEquals("English", a.name)
        assertEquals(true, a.default)
        assertEquals("2", a.channels)
        // I-frame playlists are not variants.
        assertEquals(3, m.variants.size)
    }

    @Test
    fun aVariantListedTwiceCountsOnceAndARenditionWithoutAUriNotAtAll() {
        // Media3 numbers them so; the stream keys a download keeps are its.
        val m = parseHlsMaster(DUBBED_MASTER)
        assertEquals(listOf("v0/playlist.m3u8", "v1/playlist.m3u8"), m.variants.map { it.uri })
        assertEquals(listOf("de", "en", "it"), m.audios.map { it.language })
        assertEquals(listOf(0, 1, 2), m.audios.map { it.index })
        assertEquals(true, m.subtitles.single().forced)
        assertNull(m.variants[1].averageBandwidth)
        assertEquals(3_000_000L, m.variants[1].sizeBitrate)
        assertEquals(m.audios, m.audiosOf(m.variants[0]))
    }

    @Test
    fun aTextThatIsNoMasterHasNothing() {
        val m = parseHlsMaster("<html>gateway</html>")
        assertEquals(0, m.variants.size + m.audios.size + m.subtitles.size)
    }
}

class DownloadChoiceTest {
    @Test
    fun sizeClassesNameAPictureAsThePlayersMenuDoes() {
        assertEquals(720, sizeClass(1280, 720))
        // A 2.39:1 film's rungs are named for the box that holds them.
        assertEquals(720, sizeClass(1280, 536))
        assertEquals(1080, sizeClass(1920, 818))
        assertEquals(2160, sizeClass(3840, 1606))
        // A DCI frame, 10 % wider than 16:9.
        assertEquals(2160, sizeClass(4096, 2160))
        assertEquals(480, sizeClass(854, 480))
        assertEquals(4320, sizeClass(7680, 4320))
        assertNull(sizeClass(1280, null))
        assertEquals("720p", qualityName(720))
    }

    @Test
    fun theQualityPicksTheTallestRenditionNotTallerThanIt() {
        val variants = parseHlsMaster(PACKAGE_MASTER).variants
        assertEquals("v1/playlist.m3u8?stream=T&caps=avc,hvc&v=7", pickVariant(variants, 720)?.uri)
        // No 1080p rung: the 720p one, not the 8K one.
        assertEquals(720, pickVariant(variants, 1080)?.height)
        assertEquals(4320, pickVariant(variants, 4320)?.height)
        assertEquals(480, pickVariant(variants, 480)?.height)
        // Every rung taller than asked: the shortest.
        assertEquals(480, pickVariant(variants, 360)?.height)
        assertNull(pickVariant(emptyList(), 720))
    }

    @Test
    fun theViewersLanguageAndTheOriginalAreKept() {
        val audios = parseHlsMaster(DUBBED_MASTER).audios
        // English preferred; the German original (the master's default) too.
        assertEquals(listOf("en", "de"), pickAudios(audios, "eng").map { it.language })
        // The preferred is the original: once.
        assertEquals(listOf("de"), pickAudios(audios, "ger").map { it.language })
        // "Original": the title's own.
        assertEquals(listOf("de"), pickAudios(audios, "orig").map { it.language })
        // A language the title lacks: its own.
        assertEquals(listOf("de"), pickAudios(audios, "jpn").map { it.language })
        assertEquals(emptyList(), pickAudios(emptyList(), "eng"))
    }

    @Test
    fun subtitlesInTheViewersLanguagesAndForcedOnesForTheAudioKept() {
        fun sub(id: String, lang: String, format: String? = "webvtt", forced: Boolean = false, label: String? = null) =
            SidecarSubtitle(id = id, lang = lang, url = "/api/v1/play/subs/$id.vtt", format = format, forced = forced, label = label)
        val sidecars = listOf(
            sub("en", "eng"),
            sub("en-sdh", "en", label = "English SDH"),
            sub("de-forced", "ger", forced = true),
            sub("de", "deu"),
            sub("fr", "fre"),
            sub("it-forced", "ita", label = "Italian (forced)"),
            sub("en-pgs", "eng", format = "pgs"),
            sub("srt", "spa", format = "srt"),
        )
        // English audio preferred, German original kept, Spanish subtitles
        // set: English and Spanish ones, the German forced one; no PGS, no
        // full German, no French.
        assertEquals(
            listOf("en", "en-sdh", "de-forced", "srt"),
            pickSubtitles(sidecars, audioLanguages = listOf("en", "de"), audioPref = "eng", subtitlePref = "spa").map { it.id },
        )
        // Subtitles off, the title's own audio: only the forced ones in it.
        assertEquals(
            listOf("it-forced"),
            pickSubtitles(sidecars, audioLanguages = listOf("it"), audioPref = "orig", subtitlePref = "off").map { it.id },
        )
    }

    @Test
    fun theSizeComesFromTheMastersBitRateOverTheLength() {
        val v720 = parseHlsMaster(PACKAGE_MASTER).variants[1]
        // A two-hour film: 1,691,850 bit/s for 7,200 s.
        assertEquals(1_691_850L * 7_200 / 8, estimateBytes(v720, audioCount = 1, durationMs = 7_200_000))
        // A second audio rendition is not in the variant's rate.
        assertEquals((1_691_850L + EXTRA_AUDIO_BPS) * 7_200 / 8, estimateBytes(v720, audioCount = 2, durationMs = 7_200_000))
        assertNull(estimateBytes(v720, 1, null))
        assertNull(estimateBytes(v720, 1, 0))
        assertEquals(1_000_000_000L * 3600 / 8 / 1000, bytesPerHour(1_000_000))
    }

    @Test
    fun sizesReadInGigabytesWithOneDecimalAndInMegabytesBelow() {
        assertEquals("1.4 GB", formatSize(1_440_000_000))
        assertEquals("820 MB", formatSize(820_000_000))
        assertEquals("1.0 GB", formatSize(999_600_000))
        assertEquals("12.4 GB", formatSize(12_400_000_000))
        assertEquals("1 MB", formatSize(1_000))
        assertEquals("0 MB", formatSize(0))
    }
}
