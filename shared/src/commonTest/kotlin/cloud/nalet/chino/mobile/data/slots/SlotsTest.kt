package cloud.nalet.chino.mobile.data.slots

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What an addon's slot row may make the app do — chino-web's
 *  extensions.test.ts, on a phone: the app has no page a link is relative
 *  to, so a link is a path or an absolute URL on the server, as portal-api
 *  serves them. */
class SlotsTest {
    private val api = "https://media.example/api/"

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun aLinkOnTheServerItselfAPathOrAbsoluteOnTheSameOrigin() {
        assertEquals("https://media.example/portal/app/sample?q=zz", slotLinkHref("/portal/app/sample?q=zz", api))
        assertEquals("https://media.example/portal/app/sample", slotLinkHref("https://media.example/portal/app/sample", api))
        assertEquals("https://media.example/portal/", slotLinkHref("https://media.example:443/portal/", api))
        // Relative to a page the web app is on; the phone is on none.
        assertNull(slotLinkHref("?q=other", api))
    }

    @Test
    fun aScriptUrlDrawsNothingHoweverItIsSpelled() {
        for (raw in listOf(
            "javascript:void(document.title=\"x\")",
            "JavaScript:alert(1)",
            " javascript:alert(1)",
            "java\tscript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "vbscript:msgbox(1)",
        )) {
            assertNull(slotLinkHref(raw, api), raw)
        }
    }

    @Test
    fun anotherOriginDrawsNothing() {
        for (raw in listOf(
            "https://addon.invalid/landing",
            "//addon.invalid/landing",
            "\\\\addon.invalid/landing",
            "http://media.example/portal/",
            "https://media.example:8443/portal/",
            "https://user:secret@media.example/portal/",
            "ftp://media.example/file",
            "mailto:someone@media.example",
        )) {
            assertNull(slotLinkHref(raw, api), raw)
        }
    }

    @Test
    fun noUrlOrAnEmptyOneDrawsNothing() {
        for (raw in listOf(null, "", "   ")) assertNull(slotLinkHref(raw, api), raw.toString())
    }

    @Test
    fun qIsFilledInEncodedBeforeTheUrlIsChecked() {
        assertEquals(
            "https://media.example/portal/app/sample?q=zz%26qq%3D1%20%3Cx%3E%20%22y%22%23w",
            slotLinkHref("/portal/app/sample?q={q}", api, mapOf("q" to "zz&qq=1 <x> \"y\"#w")),
        )
        // A query cannot turn a link into a script or another host.
        assertNull(slotLinkHref("{q}", api, mapOf("q" to "javascript:alert(1)")))
        assertNull(slotLinkHref("https://{q}/x", api, mapOf("q" to "addon.invalid")))
        assertEquals("/x?a=1%202&b=", substitute("/x?a={a}&b={b}", mapOf("a" to "1 2")))
        assertEquals("/x?c=", substitute("/x?c={constructor}", emptyMap()))
    }

    @Test
    fun anActionPostsOnlyToThePortalsAppProxyOnThisServer() {
        assertEquals(
            "https://media.example/api/portal/apps/sample/collect?q=a%20b",
            slotActionUrl("/api/portal/apps/sample/collect?q={q}", "POST", api, mapOf("q" to "a b")),
        )
        assertEquals(
            "https://media.example/api/portal/apps/sample",
            slotActionUrl("https://media.example/api/portal/apps/sample", "", api),
        )
        // No method means POST; the case does not matter.
        assertEquals("https://media.example/api/portal/apps/sample/x", slotActionUrl("/api/portal/apps/sample/x", null, api))
        assertEquals("https://media.example/api/portal/apps/sample/x", slotActionUrl("/api/portal/apps/sample/x", "post", api))
    }

    @Test
    fun anActionWithAnyOtherMethodDrawsNothing() {
        for (method in listOf("GET", "DELETE", "PUT", "PATCH", "post ; DELETE")) {
            assertNull(slotActionUrl("/api/portal/apps/sample/x", method, api), method)
        }
    }

    @Test
    fun theBearerNeverLeavesTheServerNorGoesToChinoApiOrThePortalItself() {
        for (raw in listOf(
            "https://addon-sim.invalid/collect",
            "//addon-sim.invalid/api/portal/apps/sample/x",
            "http://media.example/api/portal/apps/sample/x",
            "/api/v1/me/watchlists",
            "/api/portal/addons/sample",
            "/portal/app/sample",
            "/api/portal/apps/",
            "/api/portal/apps//x",
            // Climbing out of the proxy, plainly or encoded, is refused as written.
            "/api/portal/apps/sample/../../addons/sample",
            "/api/portal/apps/sample/%2e%2e/%2E%2E/addons",
            "/api/portal/apps/sample/.%2e/addons",
            "/api/portal/apps/./sample",
            "/api/portal/apps/sample/..%2f..%2faddons",
            "/api/portal/apps/sample%5c..%5c..%5caddons",
            "javascript:fetch(\"/api/portal/apps/sample/x\")",
        )) {
            assertNull(slotActionUrl(raw, "POST", api), raw)
        }
        // Nor can a query climb out.
        assertNull(slotActionUrl("/api/portal/apps/sample/{q}", "POST", api, mapOf("q" to "..")))
        assertNull(slotActionUrl("/api/portal/apps/sample/{q}", "POST", api, mapOf("q" to "../../addons")))
    }

    @Test
    fun onlyLinksAndActionsAreKinds() {
        assertEquals(SlotKind.Link, slotKind("link"))
        assertEquals(SlotKind.Action, slotKind(" Action "))
        for (raw in listOf("iframe", "script", "", null)) assertNull(slotKind(raw), raw.toString())
    }

    @Test
    fun theRowsAReviewSawRenderedAndWhatIsLeftOfThem() {
        val rows = json(
            """
            [
              {"key":"sim.kebab","kind":"link","label":"kebab icon list-video","icon":"list-video","url":"/portal/app/sim?q={q}","enabled":true},
              {"key":"sim.js","kind":"link","label":"javascript: link","icon":"zap","url":"javascript:void(document.title=\"x\")","enabled":true},
              {"key":"sim.offorigin","kind":"action","label":"action to another origin","icon":"send","url":"https://addon-sim.invalid/collect?q={q}","method":"DELETE","enabled":true},
              {"key":"sim.iframe","kind":"iframe","label":"kind iframe","icon":"frame","url":"/api/portal/apps/sim/frame","enabled":true},
              {"key":"sim.offsite","kind":"link","label":"off-site link","icon":"puzzle","url":"https://addon-sim.invalid/landing","enabled":true},
              {"key":"sim.action","kind":"action","label":"Add It","icon":"radar","url":"/api/portal/apps/sim/collect?q={q}","method":"POST","enabled":true},
              {"key":"sim.off","kind":"link","label":"disabled","url":"/portal/app/sim","enabled":false},
              {"key":"sim.nolabel","kind":"link","label":"  ","url":"/portal/app/sim","enabled":true},
              null,
              "not a row"
            ]
            """,
        )
        assertEquals(
            listOf(
                SlotButton("sim.kebab", SlotKind.Link, "kebab icon list-video", "list-video", "https://media.example/portal/app/sim?q=zz%20top"),
                SlotButton("sim.action", SlotKind.Action, "Add It", "radar", "https://media.example/api/portal/apps/sim/collect?q=zz%20top"),
            ),
            slotButtons(rows, api, mapOf("q" to "zz top")),
        )
        assertEquals(emptyList(), slotButtons(json("""{"not":"an array"}"""), api))
        assertEquals(emptyList(), slotButtons(null, api))
    }

    @Test
    fun aFieldOfTheWrongTypeIsNoField() {
        val rows = json(
            """
            [
              {"key":"a","kind":"link","label":"Enabled As Text","url":"/portal/app/x","enabled":"true"},
              {"key":"b","kind":"link","label":"Not Enabled","url":"/portal/app/x"},
              {"key":"c","kind":"link","label":7,"url":"/portal/app/x","enabled":true},
              {"key":"d","kind":"link","label":"URL As Number","url":7,"enabled":true},
              {"key":"e","kind":"action","label":"Method As Number","url":"/api/portal/apps/x/y","method":7,"enabled":true},
              {"key":"f","kind":"action","label":"Method Null","url":"/api/portal/apps/x/y","method":null,"enabled":true},
              {"kind":"link","label":"No Key","url":"/portal/app/x","enabled":true}
            ]
            """,
        )
        assertEquals(
            listOf(
                SlotButton("f", SlotKind.Action, "Method Null", "puzzle", "https://media.example/api/portal/apps/x/y"),
                SlotButton("row-6", SlotKind.Link, "No Key", "puzzle", "https://media.example/portal/app/x"),
            ),
            slotButtons(rows, api),
        )
    }

    @Test
    fun iconsThePortalsNineteenNamesAsThePortalStoresThemOrAsLucideSpellsThem() {
        assertEquals(19, SLOT_ICON_NAMES.size)
        for (name in SLOT_ICON_NAMES) assertEquals(name, slotIconName(name))
        assertEquals("list-video", slotIconName("ListVideo"))
        assertEquals("layout-grid", slotIconName("layoutGrid"))
        assertEquals("file-text", slotIconName(" FileText "))
        assertEquals("puzzle", slotIconName("Puzzle"))
        assertEquals("tv", slotIconName("TV"))
    }

    @Test
    fun iconsAnyOtherNameIsThePuzzle() {
        for (raw in listOf("icon", "Icon", "createLucideIcon", "rocket", "zap", "glyph:c", "", null)) {
            assertEquals("puzzle", slotIconName(raw), raw.toString())
        }
    }

    @Test
    fun eachRowGetsItsPaletteIcon() {
        val rows = json(
            """
            [
              {"key":"a","kind":"link","label":"L","icon":"ListVideo","url":"/portal/app/x","enabled":true},
              {"key":"b","kind":"link","label":"L","icon":"icon","url":"/portal/app/x","enabled":true},
              {"key":"c","kind":"link","label":"L","url":"/portal/app/x","enabled":true},
              {"key":"d","kind":"link","label":"L","icon":3,"url":"/portal/app/x","enabled":true}
            ]
            """,
        )
        assertEquals(listOf("list-video", "puzzle", "puzzle", "puzzle"), slotButtons(rows, api).map { it.icon })
    }
}
