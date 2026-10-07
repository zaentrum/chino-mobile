package cloud.nalet.chino.mobile.data.api

import cloud.nalet.chino.mobile.data.model.CastMember
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.data.model.ItemsPage
import cloud.nalet.chino.mobile.data.model.Me
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The DTOs against the JSON chino-api sends, shapes copied from its handlers
 * and its own tests (router.go, people.go, series.go, katalog/client.go,
 * katalog/people_test.go), decoded with the app's [ChinoJson].
 */
class DtoDecodingTest {
    @Test
    fun anItemCarriesItsArtworkUrlsAndTheWholeCast() {
        val item = ChinoJson.decodeFromString<Item>(
            """
            {"id":"m1","type":"movie","title":"A Film","year":2001,"rating":7.5,
             "description":"About a film.","tagline":"It is one.","duration_ms":6000000,
             "poster_url":"/api/v1/items/m1/poster","backdrop_url":"/api/v1/items/m1/backdrop",
             "genres":["Drama"],
             "cast":[
               {"person_id":"actor-a","name":"Actor A","role":"actor","character":"Part A","order":0},
               {"person_id":"creator-a","name":"Creator A","role":"creator","episode_count":12},
               {"person_id":"writer-b","name":"Writer B","role":"writer","job":"Screenplay","order":0},
               {"name":"Unlinked A","role":"actor"}],
             "trailers":[{"site":"YouTube","external_id":"abc","url":"https://www.youtube.com/watch?v=abc","title":"Official Trailer"}],
             "segments":{"has_intro":true,"has_credits":true,"has_recap":false,"count":2},
             "watched_at":"2026-09-30T20:00:00Z"}
            """,
        )

        assertEquals("/api/v1/items/m1/poster", item.posterUrl)
        assertEquals("/api/v1/items/m1/backdrop", item.backdropUrl)
        assertEquals("About a film.", item.overview)
        assertEquals(
            listOf(
                CastMember(name = "Actor A", role = "actor", personId = "actor-a", character = "Part A", order = 0),
                CastMember(name = "Creator A", role = "creator", personId = "creator-a", episodeCount = 12),
                CastMember(name = "Writer B", role = "writer", personId = "writer-b", job = "Screenplay", order = 0),
                CastMember(name = "Unlinked A", role = "actor"),
            ),
            item.cast,
        )
        assertEquals("abc", item.trailers.single().externalId)
        assertTrue(item.roles.isEmpty())
        // chino-api leaves extras out when none plays, and an older one knows none.
        assertTrue(item.extras.isEmpty())
    }

    @Test
    fun aTitlesExtrasComeBesideItsTrailerLinks() {
        // chino-api's item detail with an extra, as its README shows it.
        val item = ChinoJson.decodeFromString<Item>(
            """
            {"id":"9c4e7a12-3b5d-4f60-8a91-0e2d4c6b8f13","type":"movie","title":"A Film",
             "trailers":[{"site":"YouTube","external_id":"x1","url":"https://www.youtube.com/watch?v=x1","title":"Official Trailer"}],
             "extras":[
               {"id":"1b5c2a8e-6f0d-4c3e-9a51-2d7f0c4b8e01","kind":"trailer","title":"Trailer","language":"en",
                "duration_ms":33000,"local":true,
                "play_path":"/api/v1/items/9c4e7a12-3b5d-4f60-8a91-0e2d4c6b8f13/extras/1b5c2a8e-6f0d-4c3e-9a51-2d7f0c4b8e01/play/master.m3u8"},
               {"id":"e2","kind":"teaser","title":"Season 2","season_number":2,"local":true,
                "play_path":"/api/v1/items/s/extras/e2/play/master.m3u8"}]}
            """,
        )

        assertEquals("https://www.youtube.com/watch?v=x1", item.trailers.single().url)
        val (trailer, teaser) = item.extras
        assertEquals("trailer", trailer.kind)
        assertEquals("Trailer", trailer.title)
        assertEquals("en", trailer.language)
        assertEquals(33_000L, trailer.durationMs)
        assertNull(trailer.seasonNumber)
        assertTrue(trailer.playable)
        assertEquals(
            "/api/v1/items/9c4e7a12-3b5d-4f60-8a91-0e2d4c6b8f13/extras/1b5c2a8e-6f0d-4c3e-9a51-2d7f0c4b8e01/play/master.m3u8",
            trailer.playPath,
        )
        assertEquals(2, teaser.seasonNumber)

        // An extra short of a field decodes, and the item with it; it just does not play.
        val short = ChinoJson.decodeFromString<Item>("""{"id":"m","title":"T","extras":[{"kind":"trailer","title":"Trailer"}]}""")
        assertFalse(short.extras.single().playable)
    }

    @Test
    fun aPersonCarriesTheirDetailsPortraitAndRolesPerTitle() {
        // chino-api's GET /people/p1, as katalog/people_test.go pins it.
        val person = ChinoJson.decodeFromString<PersonDetail>(
            """
            {"id":"p1","name":"Ada Example","has_profile":true,
             "profile_url":"/api/v1/people/p1/profile","sort_name":"Example, Ada",
             "also_known_as":["A. Example"],"birth_date":"1950-03-01","death_date":"2020-11-30",
             "birthplace":"Bern, Switzerland","known_for_department":"Acting",
             "biography":"Ada Example ist Schauspielerin.","biography_lang":"de",
             "tmdb_person_id":"12345","imdb_id":"nm0000123","items":[
             {"id":"s1","type":"series","title":"A Show","year":2010,"rating":8,
              "poster_url":"/api/v1/items/s1/poster","backdrop_url":"/api/v1/items/s1/backdrop","roles":["actor"]},
             {"id":"m1","type":"movie","title":"A Film","year":2001,"rating":7.5,
              "poster_url":"/api/v1/items/m1/poster","backdrop_url":"/api/v1/items/m1/backdrop","roles":["actor","director"]}]}
            """,
        )

        assertTrue(person.hasProfile)
        assertEquals("/api/v1/people/p1/profile", person.profileUrl)
        assertEquals(listOf("A. Example"), person.alsoKnownAs)
        assertEquals("1950-03-01", person.birthDate)
        assertEquals("2020-11-30", person.deathDate)
        assertEquals("Bern, Switzerland", person.birthplace)
        assertEquals("Acting", person.knownForDepartment)
        assertEquals("Ada Example ist Schauspielerin.", person.biography)
        assertEquals("de", person.biographyLang)
        assertEquals(listOf(listOf("actor"), listOf("actor", "director")), person.items.map { it.roles })
        assertEquals(8.0, person.items.first().rating)
    }

    @Test
    fun aPersonWithoutPortraitOrDetailsStillDecodes() {
        val person = ChinoJson.decodeFromString<PersonDetail>(
            """{"id":"p2","name":"Bo Writer","has_profile":false,"items":[]}""",
        )

        assertFalse(person.hasProfile)
        assertNull(person.profileUrl)
        assertNull(person.birthDate)
        assertNull(person.biography)
        assertTrue(person.items.isEmpty())
    }

    @Test
    fun peopleSearchRowsCarryThePortrait() {
        val people = ChinoJson.decodeFromString<PeopleResponse>(
            """
            {"people":[
              {"id":"p1","name":"Ada Example","credits":2,"has_profile":true,"profile_url":"/api/v1/people/p1/profile"},
              {"id":"p4","name":"Ada Other","has_profile":false}],"total":2}
            """,
        ).people

        assertEquals("/api/v1/people/p1/profile", people[0].profileUrl)
        assertTrue(people[0].hasProfile)
        // credits is omitted when 0.
        assertEquals(0, people[1].credits)
        assertNull(people[1].profileUrl)
    }

    @Test
    fun anUnlabelledSidecarNoLongerDropsEverySubtitle() = runTest {
        // router.go subtitlesList: katalog.Subtitle has `label,omitempty`.
        val server = FakeServer {
            respondJson(
                """
                {"subtitles":[
                  {"id":"s1","lang":"en","label":"English","format":"webvtt","url":"/api/v1/play/subs/s1.vtt"},
                  {"id":"s2","lang":"de","url":"/api/v1/play/subs/s2.vtt"}]}
                """,
            )
        }

        val subtitles = server.api.itemSubtitles("m1").subtitles

        assertEquals(listOf("s1", "s2"), subtitles.map { it.id })
        assertNull(subtitles[1].label)
    }

    @Test
    fun theNextEpisodeIsAnItemUnderNext() {
        val next = ChinoJson.decodeFromString<NextEpisodeResponse>(
            """{"next":{"id":"e2","type":"episode","title":"Two","season_number":1,"episode_number":2,"parent_id":"s1"},"anchor":"e1"}""",
        )
        assertEquals("e2", next.next?.id)
        assertEquals(2, next.next?.episodeNumber)
        assertEquals("e1", next.anchor)

        val end = ChinoJson.decodeFromString<NextEpisodeResponse>("""{"next":null,"reason":"end_of_series"}""")
        assertNull(end.next)
        assertEquals("end_of_series", end.reason)
    }

    @Test
    fun theItemListEnvelopesDecode() {
        val listed = ChinoJson.decodeFromString<ItemsPage>(
            """{"product":"chino","items":[{"id":"m1","title":"A Film"}],"source":"katalog"}""",
        )
        val similar = ChinoJson.decodeFromString<ItemsPage>("""{"items":[{"id":"m2","title":"B Film"}],"total":1}""")

        assertEquals("m1", listed.items.single().id)
        assertEquals("m2", similar.items.single().id)
    }

    @Test
    fun meIsTheSubjectOnly() {
        assertEquals("abc", ChinoJson.decodeFromString<Me>("""{"sub":"abc"}""").sub)
    }

    @Test
    fun playInfoForATranscodeListsTheLadderAndTheEmbeddedTracks() {
        // chino-stream internal/play/handler.go Info, behind chino-api's proxy.
        val info = ChinoJson.decodeFromString<PlayInfo>(
            """
            {"filename":"A Film.mkv","container":"matroska,webm","video_codec":"hevc","audio_codec":"aac",
             "width":1920,"height":1080,"duration_ms":6000000,"mode":"transcode",
             "reason":"video codec hevc not in client caps",
             "qualities":[{"name":"high","label":"High (source resolution)"},{"name":"medium","label":"Medium (720p)"}],
             "default_quality":"high",
             "audio_tracks":[{"index":1,"codec":"aac","language":"eng","default":true,"channels":2}],
             "subtitle_tracks":[{"index":3,"codec":"subrip","language":"ger"}],
             "encoder":"libx264"}
            """,
        )
        assertEquals(listOf("high", "medium"), info.qualities.map { it.name })
        assertTrue(info.rungs.isEmpty())
        assertEquals(3, info.subtitleTracks.single().index)
        assertEquals(1, info.audioTracks.single().index)
    }

    @Test
    fun playInfoForAPackagedTitleWithItsRungsAndIndexlessSubtitleRows() {
        // writePackagedInfo: qualities null, the rendition id as default, the
        // manifest's subtitle rows (no stream index) — and the rung ladder
        // the server is adding.
        val info = ChinoJson.decodeFromString<PlayInfo>(
            """
            {"filename":"A Film","container":"cmaf","video_codec":"hvc1","audio_codec":"aac","width":3840,"height":2160,
             "duration_ms":6000000,"mode":"packaged","reason":"pre-segmented CMAF on disk","qualities":null,
             "default_quality":"v0",
             "rungs":[{"id":"v0","height":2160,"codec":"hvc1.2.4.L153.B0","bitrate":16000000,"label":"4K"},{"id":"v1","height":1080}],
             "audio_tracks":[{"index":0,"codec":"mp4a","language":"eng","title":"English","default":true,"channels":2}],
             "subtitle_tracks":[{"id":"s0","path":"subs/0.vtt","language":"eng","format":"webvtt"}]}
            """,
        )
        assertTrue(info.qualities.isEmpty())
        assertEquals("v0", info.defaultQuality)
        assertEquals(listOf("v0" to 2160, "v1" to 1080), info.rungs.map { it.id to it.height })
        assertEquals("4K", info.rungs.first().label)
        assertNull(info.rungs[1].bitrate)
        assertNull(info.subtitleTracks.single().index)
    }

    @Test
    fun playInfoForAClientServedTheFiveOneCompanionsListsTheGroupByName() {
        // writePackagedInfo with `eac3` in the caps (unionAudioTracks): the
        // master's one group in its order, each track with its group and
        // rendition — the demo's Sintel.
        val info = ChinoJson.decodeFromString<PlayInfo>(
            """
            {"mode":"packaged","audio_codec":"eac3","default_quality":"auto","qualities":null,
             "audio_tracks":[
               {"channels":6,"codec":"ec-3","default":true,"group":"audio-surround","index":0,"language":"eng",
                "name":"English 5.1","rendition":"a1","title":"English 5.1"},
               {"channels":2,"codec":"mp4a.40.2","default":false,"group":"audio-surround","index":1,"language":"eng",
                "name":"English","rendition":"a0","title":"English"}]}
            """,
        )
        assertEquals(listOf("English 5.1", "English"), info.audioTracks.map { it.name })
        assertEquals(listOf("audio-surround", "audio-surround"), info.audioTracks.map { it.group })
        assertEquals(listOf("a1", "a0"), info.audioTracks.map { it.rendition })
        assertEquals(listOf("ec-3", "mp4a.40.2"), info.audioTracks.map { it.codec })
        assertEquals(listOf(true, false), info.audioTracks.map { it.default })
    }
}
