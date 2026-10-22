package cloud.nalet.chino.mobile.cast

import cloud.nalet.chino.mobile.data.api.SidecarSubtitle
import cloud.nalet.chino.mobile.ui.player.MasterSubtitle
import cloud.nalet.chino.mobile.ui.player.parseHlsMaster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which audio and which subtitle the TV plays, of the tracks it lists once
 *  it has loaded the title. */
class CastTracksTest {
    // The TV's tracks: its video, the master's audio (stereo and 5.1 of
    // English, Japanese), the master's subtitles, the phone's sidecars.
    private val video = TvTrack(1, TvTrackType.Video, null, null)
    private val enStereo = TvTrack(2, TvTrackType.Audio, "en", "English")
    private val en51 = TvTrack(3, TvTrackType.Audio, "en", "English 5.1")
    private val ja = TvTrack(4, TvTrackType.Audio, "ja", "Japanese")
    private val masterEn = TvTrack(5, TvTrackType.Text, "en", "English")
    private val masterEnForced = TvTrack(6, TvTrackType.Text, "en", "English (forced)", forced = true)
    private val sidecarDe = TvTrack(1000, TvTrackType.Text, "de", "German", sidecarId = "s2")
    private val tracks = listOf(video, enStereo, en51, ja, masterEn, masterEnForced, sidecarDe)

    private val auto = CastSubtitleStart.Auto(subtitlePref = "en")

    private fun wish(audio: String?, subtitle: CastSubtitleStart = auto, pref: String? = "orig") = CastTrackWish(audio, pref, subtitle)

    @Test
    fun theAudioThePhonePlayedPlaysOnTheTv() {
        val picks = castTrackPicks(tracks, active = listOf(1, 2), wish(audio = "jpn"))
        assertEquals(ja, picks.audio)
        assertEquals(listOf(1L, 4L, 5L), castActiveTracks(tracks, active = listOf(1, 2), wish(audio = "jpn")))
    }

    @Test
    fun theTrackPlayingStaysWhereItIsTheLanguageAlready() {
        // English plays in stereo: not swapped for the 5.1 one.
        assertEquals(enStereo, castTrackPicks(tracks, active = listOf(1, 2), wish(audio = "eng")).audio)
        assertEquals(en51, castTrackPicks(tracks, active = listOf(1, 3), wish(audio = "eng")).audio)
    }

    @Test
    fun withoutThePhonesLanguageTheSettingsOneThenTheTvsOwn() {
        assertEquals(ja, castTrackPicks(tracks, active = listOf(2), wish(audio = "fre", pref = "jpn")).audio)
        assertEquals(enStereo, castTrackPicks(tracks, active = listOf(2), wish(audio = null, pref = "orig")).audio)
        assertEquals(enStereo, castTrackPicks(tracks, active = emptyList(), wish(audio = null, pref = null)).audio)
    }

    @Test
    fun theDefaultRuleReadsTheAudioTheTvPlays() {
        // Japanese audio on the TV, English subtitles in Settings: the full English.
        assertEquals(masterEn, castTrackPicks(tracks, listOf(2), wish(audio = "jpn")).text)
        // English audio: the forced English, the lines in another language.
        assertEquals(masterEnForced, castTrackPicks(tracks, listOf(2), wish(audio = "eng")).text)
        // The phone did not know its audio: the TV's tells.
        assertEquals(masterEn, castTrackPicks(tracks, listOf(4), wish(audio = null)).text)
    }

    @Test
    fun anEnglishAudioPreferenceNeverKeepsGermanSubtitlesOffOverEnglishAudio() {
        // Audio preferred "en", English playing, German subtitles preferred:
        // German comes on - only the audio playing counts (docs/chino).
        val german = wish(audio = "eng", subtitle = CastSubtitleStart.Auto(subtitlePref = "de"), pref = "en")
        val picks = castTrackPicks(tracks, listOf(2), german)
        assertEquals(enStereo, picks.audio)
        assertEquals(sidecarDe, picks.text)
    }

    @Test
    fun thePhonesSidecarOrTheTvsRenditionOfIt() {
        val german = wish(audio = "eng", subtitle = CastSubtitleStart.Sidecar("s2", "ger", forced = false))
        assertEquals(sidecarDe, castTrackPicks(tracks, listOf(2), german).text)
        // The English sidecar was not side-loaded: the master carries it.
        val english = wish(audio = "eng", subtitle = CastSubtitleStart.Sidecar("s1", "eng", forced = false))
        assertEquals(masterEn, castTrackPicks(tracks, listOf(2), english).text)
        val forced = wish(audio = "eng", subtitle = CastSubtitleStart.Sidecar("s4", "eng", forced = true))
        assertEquals(masterEnForced, castTrackPicks(tracks, listOf(2), forced).text)
        assertNull(castTrackPicks(tracks, listOf(2), wish(audio = "eng", subtitle = CastSubtitleStart.Off)).text)
    }

    @Test
    fun nothingIsSentWhereTheTracksAreOnAlready() {
        assertNull(castActiveTracks(tracks, active = listOf(1, 2, 6), wish(audio = "eng")))
        // Off, and none on: nothing either.
        assertNull(castActiveTracks(tracks, active = listOf(1, 2), wish(audio = "eng", subtitle = CastSubtitleStart.Off)))
        // A subtitle on that should be off: off, the video and audio kept.
        assertEquals(listOf(1L, 2L), castActiveTracks(tracks, active = listOf(1, 2, 5), wish(audio = "eng", subtitle = CastSubtitleStart.Off)))
    }

    @Test
    fun theMastersSubtitlesAreReadOffIt() {
        val master = parseHlsMaster(
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",DEFAULT=NO,AUTOSELECT=NO,URI="s0/index.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English (forced)",LANGUAGE="en",FORCED=YES,AUTOSELECT=YES,URI="s1/index.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",SUBTITLES="subs"
            v0/index.m3u8
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                MasterSubtitle(name = "English", language = "en", forced = false, default = false, autoselect = false),
                MasterSubtitle(name = "English (forced)", language = "en", forced = true, default = false, autoselect = true),
            ),
            master.subtitles,
        )
        assertEquals(1, master.variants.size)
    }

    @Test
    fun aSideLoadedSubtitleTheLoadTurnedOnStaysOn() {
        // English and English SDH side-loaded; the load turned the SDH one on.
        val ours = listOf(
            TvTrack(2, TvTrackType.Audio, "en", "English"),
            TvTrack(1000, TvTrackType.Text, "en", "English", sidecarId = "s1"),
            TvTrack(1001, TvTrackType.Text, "en", "English · SDH", sidecarId = "s3", sdh = true),
        )
        val sdh = wish(audio = "eng", subtitle = CastSubtitleStart.Sidecar("s3", "eng", forced = false, sdh = true))
        assertNull(castActiveTracks(ours, active = listOf(2, 1001), wish = sdh))
        // The default rule would pick plain English for that audio: the side-loaded one on stays.
        assertNull(castActiveTracks(ours, active = listOf(2, 1001), wish = wish(audio = "jpn")))
    }

    @Test
    fun theTvsSideLoadedTracksAreKnownByTheLoadsIds() {
        // As the TV lists them after the load: no sidecar ids of their own.
        val reported = listOf(
            TvTrack(2, TvTrackType.Audio, "en", "English"),
            TvTrack(1000, TvTrackType.Text, "en", "English"),
            TvTrack(1001, TvTrackType.Text, "en", "English · SDH"),
            TvTrack(5, TvTrackType.Text, "de", "German (forced)"),
        )
        val media = castMediaFor(
            CastMediaInput(
                apiBase = "https://media.example.com/api", itemId = "m1",
                phoneMasterUrl = "https://media.example.com/api/v1/items/m1/play/master.m3u8?stream=p",
                item = null, seriesTitle = null, durationMs = 5_400_000, startMs = 0, autoplay = true,
                sidecars = listOf(
                    SidecarSubtitle(id = "s1", lang = "eng", label = "English", url = "https://media.example.com/api/v1/play/subs/s1.vtt"),
                    SidecarSubtitle(id = "s3", lang = "eng", label = "English SDH", url = "https://media.example.com/api/v1/play/subs/s3.vtt"),
                ),
                subtitleStart = CastSubtitleStart.Sidecar("s3", "eng", forced = false, sdh = true),
                writesProgress = true,
            ),
            CastToken("tv", 0), master = null, load = "L1",
        )
        val master = parseHlsMaster(
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="German (forced)",LANGUAGE="de",FORCED=YES,URI="s0/index.m3u8"
            """.trimIndent(),
        )
        val known = withWhatThePhoneKnows(reported, media, master)
        assertEquals(listOf(null, "s1", "s3", null), known.map { it.sidecarId })
        assertEquals(listOf(false, false, true, false), known.map { it.sdh })
        assertEquals(true, known.last().forced)
        // The load turned SDH (1001) on; after it, nothing is changed.
        assertEquals(1001L, media.activeTextTrackId)
        assertNull(castActiveTracks(known, active = listOf(2, 1001), wish = CastTrackWish("eng", "orig", CastSubtitleStart.Sidecar("s3", "eng", false, true))))
    }

    @Test
    fun twoSubtitlesOfOneLanguageAreTwoToTheDeDuplication() {
        fun sub(id: String, lang: String, label: String?) =
            SidecarSubtitle(id = id, lang = lang, label = label, url = "https://media.example.com/api/v1/play/subs/$id.vtt")
        val sidecars = listOf(sub("s1", "eng", "English"), sub("s3", "eng", "English SDH"), sub("s5", "pt-BR", null), sub("s7", "zh-Hant", null))
        // The master carries one plain English, one European Portuguese, one Simplified Chinese.
        val master = listOf(
            MasterSubtitle("English", "en", forced = false, default = false, autoselect = false),
            MasterSubtitle("Português", "pt-PT", forced = false, default = false, autoselect = false),
            MasterSubtitle("中文", "zh-Hans", forced = false, default = false, autoselect = false),
        )
        // Only plain English is the master's again: SDH, Brazilian, Traditional stay.
        assertEquals(listOf("s3", "s5", "s7"), sidecarsBesideTheMaster(sidecars, master).map { it.id })
        // An SDH rendition in the master, by its characteristics: the SDH sidecar goes too.
        val withSdh = master + MasterSubtitle("English", "en", forced = false, default = false, autoselect = false, characteristics = "public.accessibility.transcribes-spoken-dialog,public.accessibility.describes-music-and-sound")
        assertEquals(listOf("s5", "s7"), sidecarsBesideTheMaster(sidecars, withSdh).map { it.id })
    }

    @Test
    fun theMastersRenditionOfTheViewersSdhIsTheSdhOne() {
        val tracks = listOf(
            TvTrack(2, TvTrackType.Audio, "en", "English"),
            TvTrack(5, TvTrackType.Text, "en", "English"),
            TvTrack(6, TvTrackType.Text, "en", "English SDH", sdh = true),
        )
        val sdh = wish(audio = "eng", subtitle = CastSubtitleStart.Sidecar("s3", "eng", forced = false, sdh = true))
        assertEquals(listOf(2L, 6L), castActiveTracks(tracks, active = listOf(2), wish = sdh))
    }

    @Test
    fun theTvsSubtitlesAreNamedAsThePhonesMenusNameThem() {
        val tv = listOf(
            video,
            en51,
            masterEn,
            masterEnForced,
            TvTrack(7, TvTrackType.Text, "en", "English · SDH", sdh = true),
            TvTrack(8, TvTrackType.Text, "zh-Hans", null),
            // One the phone side-loaded: named by the phone already.
            TvTrack(1001, TvTrackType.Text, "de", "German · foreign-language parts only", forced = true, sidecarId = "s4"),
        )
        assertEquals(
            mapOf(
                5L to "English",
                6L to "English · foreign-language parts only",
                7L to "English · for deaf and hard of hearing",
                8L to "Chinese (Simplified)",
                1001L to "German · foreign-language parts only",
            ),
            tvSubtitleNames(tv),
        )
    }

    @Test
    fun aSidecarTheMasterCarriesIsLeftToTheMaster() {
        fun sub(id: String, lang: String, label: String? = null, forced: Boolean = false) =
            SidecarSubtitle(id = id, lang = lang, label = label, url = "https://media.example.com/api/v1/play/subs/$id.vtt", forced = forced)
        val sidecars = listOf(sub("s1", "eng"), sub("s2", "ger"), sub("s4", "eng", label = "English (forced)"))
        val master = listOf(
            MasterSubtitle("English", "en", forced = false, default = false, autoselect = false),
            MasterSubtitle("English (forced)", "en", forced = true, default = false, autoselect = true),
        )
        assertEquals(listOf("s2"), sidecarsBesideTheMaster(sidecars, master).map { it.id })
        // A master without subtitles: every sidecar.
        assertEquals(listOf("s1", "s2", "s4"), sidecarsBesideTheMaster(sidecars, emptyList()).map { it.id })
        // Only forced English in the master: the full English sidecar stays.
        assertEquals(listOf("s1", "s2"), sidecarsBesideTheMaster(sidecars, master.drop(1)).map { it.id })
    }
}
