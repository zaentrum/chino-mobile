package cloud.nalet.chino.mobile

import kotlin.test.Test
import kotlin.test.assertEquals

/** The language list sent as Accept-Language loses its extensions the same
 *  way on every API level the app runs on. */
class PlatformLocaleAndroidTest {
    @Test
    fun aTagLosesItsExtensionsAndPrivateUse() {
        assertEquals("en-US", withoutExtensions("en-US-u-mu-celsius"))
        assertEquals("de-CH", withoutExtensions("de-CH-u-ca-gregory-x-work"))
        assertEquals("ja-JP", withoutExtensions("ja-JP-u-ca-japanese"))
        assertEquals("sr-Latn-RS", withoutExtensions("sr-Latn-RS-t-en"))
    }

    @Test
    fun aTagWithoutExtensionsStaysAsItIs() {
        assertEquals("fr", withoutExtensions("fr"))
        assertEquals("zh-Hant-TW", withoutExtensions("zh-Hant-TW"))
        assertEquals("de-CH-1996", withoutExtensions("de-CH-1996"))
    }

    @Test
    fun aPrivateUseOnlyTagLeavesNothing() {
        assertEquals("", withoutExtensions("x-private"))
    }
}
