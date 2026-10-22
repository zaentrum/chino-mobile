package cloud.nalet.chino.mobile.cast

import cloud.nalet.chino.mobile.data.api.SidecarSubtitle
import cloud.nalet.chino.mobile.data.model.Item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A title the phone plays from its download, cast to the TV: the TV gets it
 *  as a streamed title - the server's master and subtitles, every link
 *  signed with the TV's own token - and never a file of the phone's. */
class CastDownloadedTitleTest {
    private val base = "https://media.example.com/api"
    private val tv = CastToken("tv456", expiresAtMs = 1_800_000_000_000)
    private val caps = "avc:1080,hvc:2160,aac,mp3,opus,eac3"

    private val film = Item(
        id = "m1",
        title = "Sintel",
        kind = "movie",
        posterUrl = "/api/v1/items/m1/poster",
        backdropUrl = "/api/v1/items/m1/backdrop",
        durationMs = 888_000,
    )

    /** As the phone plays it from its download: the master it kept, the
     *  subtitles as files of the phone. */
    private val playedHere = CastMediaInput(
        apiBase = base,
        itemId = "m1",
        phoneMasterUrl = "https://media.example.com/api/v1/items/m1/play/master.m3u8?caps=avc:1080,aac,eac3",
        item = film,
        seriesTitle = null,
        durationMs = 888_000,
        startMs = 754_000,
        autoplay = false,
        sidecars = listOf(
            SidecarSubtitle(id = "s1", label = "English", lang = "eng", url = "file:///data/user/0/app/files/downloads/titles/m1~x/subs/eng-s1.vtt", format = "webvtt"),
        ),
        subtitleStart = CastSubtitleStart.Sidecar("s1", "eng", forced = false, sdh = false),
        writesProgress = true,
    )

    /** chino-api's list of the title's subtitles, its paths under /api. */
    private val serverSubs = listOf(
        SidecarSubtitle(id = "s1", label = "English", lang = "eng", url = "/api/v1/play/subs/s1.vtt", format = "webvtt"),
        SidecarSubtitle(id = "s2", label = null, lang = "ger", url = "/api/v1/play/subs/s2.vtt", format = "srt"),
    )

    @Test
    fun theTvIsSentTheServersMasterAndSubtitlesSignedForIt() {
        val input = fromTheServer(playedHere, sidecarsOnTheServer(base, serverSubs), caps)
        val media = castMediaFor(input, tv, master = null, load = "L1")
        assertEquals(
            "https://media.example.com/api/v1/items/m1/play/master.m3u8?stream=tv456&caps=avc:1080,aac",
            media.contentUrl,
        )
        assertEquals(
            listOf(
                "https://media.example.com/api/v1/play/subs/s1.vtt?stream=tv456",
                "https://media.example.com/api/v1/play/subs/s2.vtt?stream=tv456",
            ),
            media.textTracks.map { it.url },
        )
        // Where the phone was, paused as it was, the subtitle it showed.
        assertEquals(754_000, media.startMs)
        assertFalse(media.autoplay)
        assertEquals("s1", media.textTracks.first { it.id == media.activeTextTrackId }.sidecarId)
        // What it is called and its artwork, from the server.
        assertEquals("Sintel", media.metadata.title)
        assertTrue(media.metadata.images.all { it.url.startsWith("$base/v1/items/m1/") && "stream=tv456" in it.url })
    }

    @Test
    fun noLinkOfThePhonesGoesToTheTv() {
        val media = castMediaFor(fromTheServer(playedHere, sidecarsOnTheServer(base, serverSubs), caps), tv, master = null, load = "L1")
        val links = listOf(media.contentUrl) + media.textTracks.map { it.url } + media.metadata.images.map { it.url }
        assertTrue(links.none { it.startsWith("file:") || it.startsWith("content:") }, links.toString())
        assertTrue(links.all { it.startsWith("https://media.example.com/api/") })
        // Without the server's list - it did not answer - no subtitles, not
        // the phone's files.
        val bare = castMediaFor(fromTheServer(playedHere, emptyList(), caps), tv, master = null, load = "L1")
        assertTrue(bare.textTracks.isEmpty())
    }
}
