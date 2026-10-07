package cloud.nalet.chino.mobile.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An extra's captions menu: its master's subtitle renditions, named as a
 *  title's tracks are, each one AVPlayer draws — and the subtitle rule picks
 *  among them as it does among a title's. */
class MasterSubtitleChoicesTest {
    private val renditions = listOf(
        SubtitleRendition(index = 0, name = "English", language = "en", forced = false),
        SubtitleRendition(index = 1, name = "German", language = "de", forced = false),
        SubtitleRendition(index = 2, name = "German", language = "de", forced = true),
        SubtitleRendition(index = 3, name = "English SDH", language = "en", forced = false),
    )

    @Test
    fun theRenditionsByLanguageEachDrawnByThePlayer() {
        val choices = masterSubtitleChoices(renditions)
        assertEquals(listOf("English", "German", "German (forced)", "English SDH"), choices.map { it.label })
        assertEquals(listOf("en", "de", "de", "en"), choices.map { it.lang })
        assertEquals(listOf(false, false, true, false), choices.map { it.forced })
        // No cue file: AVPlayer draws the rendition, nothing is fetched.
        assertTrue(choices.all { it.available && it.url.isEmpty() && it.kind == SubtitleKind.Text })
    }

    @Test
    fun aRowNamesItsRendition() {
        val choices = masterSubtitleChoices(renditions)
        assertEquals(listOf(0, 1, 2, 3), choices.map { masterSubtitleIndex(it.id) })
        assertNull(masterSubtitleIndex(null))
        // A title's sidecar or embedded track is no rendition.
        assertNull(masterSubtitleIndex("emb-3"))
        assertNull(masterSubtitleIndex("4f1c"))
    }

    @Test
    fun theDefaultRulePicksAFullOneInTheLanguageTheViewerFollows() {
        val choices = masterSubtitleChoices(renditions)
        // German audio, English subtitles wanted: English on.
        assertEquals("English", autoSubtitleChoice(choices, audioLang = "de", subtitlePref = "en", audioPref = "orig")?.label)
        // English audio, German subtitles wanted: the full German one, not the forced.
        assertEquals("German", autoSubtitleChoice(choices, audioLang = "en", subtitlePref = "de", audioPref = "orig")?.label)
        // Audio in the language wanted: none, there is no forced English.
        assertNull(autoSubtitleChoice(choices, audioLang = "en", subtitlePref = "en", audioPref = "orig"))
    }

    @Test
    fun whereNoneWouldComeOnTheForcedRenditionInTheAudiosLanguage() {
        val choices = masterSubtitleChoices(renditions)
        // German audio, subtitles off in Settings: the forced German one.
        assertEquals("German (forced)", autoSubtitleChoice(choices, audioLang = "de", subtitlePref = "off", audioPref = "orig")?.label)
        assertEquals("German (forced)", autoSubtitleChoice(choices, audioLang = "ger", subtitlePref = "de", audioPref = "orig")?.label)
    }
}
