package cloud.nalet.chino.mobile.data.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtworkUrlTest {
    @Test
    fun aServerPathHangsOffTheOriginWithTheStreamToken() {
        assertEquals(
            "https://media.example.com/api/v1/people/p1/profile?stream=abc.def",
            artworkUrl("https://media.example.com/api/", "/api/v1/people/p1/profile", "abc.def"),
        )
        // The base without its trailing slash, as the screens trim it.
        assertEquals(
            "https://media.example.com/api/v1/items/m1/poster?stream=abc.def",
            artworkUrl("https://media.example.com/api", "/api/v1/items/m1/poster", "abc.def"),
        )
    }

    @Test
    fun aServerUnderASubPathKeepsItsPrefix() {
        assertEquals(
            "https://example.org/media/api/v1/items/m1/poster?stream=t",
            artworkUrl("https://example.org/media/api/", "/api/v1/items/m1/poster", "t"),
        )
    }

    @Test
    fun anAbsoluteUrlOrOneWithAQueryKeepsItsShape() {
        assertEquals(
            "https://cdn.example.com/p.jpg?w=300&stream=t",
            artworkUrl("https://media.example.com/api", "https://cdn.example.com/p.jpg?w=300", "t"),
        )
    }

    @Test
    fun theTokenIsEncodedAndMissingOnesLeaveTheUrlBare() {
        assertEquals(
            "https://media.example.com/api/v1/items/m1/poster?stream=a%2Bb%2Fc%3D",
            artworkUrl("https://media.example.com/api", "/api/v1/items/m1/poster", "a+b/c="),
        )
        assertEquals(
            "https://media.example.com/api/v1/items/m1/poster",
            artworkUrl("https://media.example.com/api", "/api/v1/items/m1/poster", null),
        )
    }

    @Test
    fun noPathNoUrl() {
        assertNull(artworkUrl("https://media.example.com/api", null, "t"))
        assertNull(artworkUrl("https://media.example.com/api", "  ", "t"))
    }
}
