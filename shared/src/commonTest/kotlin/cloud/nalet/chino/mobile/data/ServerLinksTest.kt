package cloud.nalet.chino.mobile.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Where an addon's link may take the person: a page of the server the app
 *  is signed in to — what portal-api's rule takes, and nothing it refuses,
 *  checked again here, where the link is opened. */
class ServerLinksTest {
    private val api = "https://media.example.org/api/"

    @Test
    fun aPathOrAnAbsoluteUrlOnTheServersOriginLeadsThere() {
        for ((link, want) in listOf(
            "/portal/app/example" to "https://media.example.org/portal/app/example",
            "/portal/app/example?q=x#/ready" to "https://media.example.org/portal/app/example?q=x#/ready",
            "https://media.example.org/portal/app/example" to "https://media.example.org/portal/app/example",
            "HTTPS://MEDIA.EXAMPLE.ORG/x" to "https://media.example.org/x",
            "  /x  " to "https://media.example.org/x",
            // The default port is the same origin.
            "https://media.example.org:443/portal/" to "https://media.example.org/portal/",
            "https://media.example.org" to "https://media.example.org/",
            "https://media.example.org?q=1" to "https://media.example.org/?q=1",
        )) {
            assertEquals(want, ownServerHref(link, api), link)
        }
    }

    @Test
    fun aScriptAnotherOriginOrAPageRelativeLinkLeadsNowhere() {
        for (link in listOf(
            "",
            "   ",
            "javascript:alert(document.cookie)",
            "JavaScript:alert(1)",
            " javascript:alert(1)",
            "java\tscript:alert(1)",
            "java\nscript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "vbscript:msgbox(1)",
            "https://elsewhere.example/x",
            "http://media.example.org/x", // another scheme is another origin
            "https://media.example.org:8443/x", // and another port
            "https://media.example.org.elsewhere.example/x",
            "https://elsewhere.example#@media.example.org/x",
            "//elsewhere.example/x",
            "//media.example.org/x",
            "/\\elsewhere.example/x",
            "\\\\elsewhere.example/x",
            "https://user:pass@media.example.org/x",
            "https://media.example.org@elsewhere.example/x",
            "ftp://media.example.org/file",
            "mailto:someone@example.org",
            // Relative to a page: the app has none, and portal-api serves no
            // such link.
            "portal/app/example",
            "?q=x",
            "#/x",
        )) {
            assertNull(ownServerHref(link, api), link)
        }
    }

    @Test
    fun aSpaceABackslashOrAControlCharacterLeadsNowhere() {
        // A browser drops tabs and newlines inside a URL and reads "\" as "/".
        for (link in listOf(
            "/x y",
            "/x\ty",
            "/x\u0000",
            "/x\u007F",
            "/x\\y",
            "https://media.example.org\t.elsewhere.example/x",
            "https://media.example.org\n/x",
        )) {
            assertNull(ownServerHref(link, api), link)
        }
    }

    @Test
    fun aPortThatIsNoneLeadsNowhere() {
        for (link in listOf(
            "https://media.example.org:0/x",
            "https://media.example.org:99999/x",
            "https://media.example.org:44a/x",
            "https://media.example.org:443:443/x",
        )) {
            assertNull(ownServerHref(link, api), link)
        }
    }

    @Test
    fun aServerOnAnotherPortOrAnIpv6AddressKeepsItsOwnOrigin() {
        val withPort = "https://media.example.org:8443/api/"
        assertEquals("https://media.example.org:8443/x", ownServerHref("/x", withPort))
        assertEquals("https://media.example.org:8443/x", ownServerHref("https://media.example.org:8443/x", withPort))
        assertNull(ownServerHref("https://media.example.org/x", withPort))

        val v6 = "http://[::1]:8080/api/"
        assertEquals("http://[::1]:8080/x", ownServerHref("/x", v6))
        assertEquals("http://[::1]:8080/x", ownServerHref("http://[::1]:8080/x", v6))
        assertNull(ownServerHref("http://[::1]/x", v6))
    }

    @Test
    fun withoutAServerNoLinkLeadsAnywhere() {
        assertNull(ownServerHref("/x", ""))
        assertNull(ownServerHref("/x", "media.example.org"))
    }

    @Test
    fun whatAUrlMayNotCarryAsItIsIsPercentEncoded() {
        assertEquals(
            "https://media.example.org/films/Am%C3%A9lie?q=%7Bx%7D%7Cy%5B%5D",
            ownServerHref("/films/Amélie?q={x}|y[]", api),
        )
        // One fragment; a "%" that starts no escape is a percent sign.
        assertEquals("https://media.example.org/x#a%23b", ownServerHref("/x#a#b", api))
        assertEquals("https://media.example.org/x?q=100%25&r=%41", ownServerHref("/x?q=100%&r=%41", api))
    }

    @Test
    fun aComponentIsEncodedAsJavaScriptEncodesIt() {
        assertEquals("zz%26qq%3D1%20%3Cx%3E%20%22y%22%23w", encodeUriComponent("zz&qq=1 <x> \"y\"#w"))
        assertEquals("AC%2FDC", encodeUriComponent("AC/DC"))
        assertEquals("Am%C3%A9lie", encodeUriComponent("Amélie"))
        assertEquals("-_.!~*'()", encodeUriComponent("-_.!~*'()"))
    }
}
