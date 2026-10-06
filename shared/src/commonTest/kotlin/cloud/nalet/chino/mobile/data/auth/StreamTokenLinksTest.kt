package cloud.nalet.chino.mobile.data.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A link's stream token: read, swapped for the one valid now, and left out
 *  where the link should name the same image whatever token signs it. */
class StreamTokenLinksTest {
    private val poster = "https://media.example.com/api/v1/items/m1/poster"
    private val master = "https://media.example.com/api/v1/items/m1/play/master.m3u8"

    @Test
    fun aLinksTokenIsItsStreamParameter() {
        assertEquals("abc", streamTokenOf("$poster?stream=abc"))
        assertEquals("abc", streamTokenOf("$master?caps=avc&stream=abc&q=v1"))
        // A token's own '=' (base64 padding) is the token's.
        assertEquals("ab==", streamTokenOf("$poster?stream=ab=="))
        assertNull(streamTokenOf(poster))
        assertNull(streamTokenOf("$poster?streams=abc"))
        assertNull(streamTokenOf("$poster?w=200"))
    }

    @Test
    fun signingSwapsTheTokenAndKeepsEveryOtherPartInItsPlace() {
        assertEquals("$master?stream=new&caps=avc:4096,hvc&q=v1", withStreamToken("$master?stream=old&caps=avc:4096,hvc&q=v1", "new"))
        assertEquals("$poster?w=200&stream=new", withStreamToken("$poster?w=200&stream=old", "new"))
        // A link without one gets one.
        assertEquals("$poster?stream=new", withStreamToken(poster, "new"))
        assertEquals("$poster?w=200&stream=new", withStreamToken("$poster?w=200", "new"))
    }

    @Test
    fun withoutItsTokenALinkNamesTheSameImageWhateverTokenSignsIt() {
        val link = "$poster?w=200&stream=t1"
        assertEquals("$poster?w=200", withoutStreamToken(link))
        assertEquals(withoutStreamToken(link), withoutStreamToken(withStreamToken(link, "t2")))
        assertEquals(poster, withoutStreamToken("$poster?stream=t1"))
        assertEquals("$master?caps=avc&q=v1", withoutStreamToken("$master?stream=t1&caps=avc&q=v1"))
        assertEquals(poster, withoutStreamToken(poster))
    }
}
