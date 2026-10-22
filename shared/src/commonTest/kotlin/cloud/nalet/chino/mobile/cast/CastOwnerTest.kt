package cloud.nalet.chino.mobile.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** This phone's owner id of an account on a server: random, kept, its own. */
class CastOwnerTest {
    private class Store : CastOwnerStore {
        val kept = HashMap<String, String>()
        override fun read(key: String): String? = kept[key]
        override fun write(key: String, value: String) {
            kept[key] = value
        }
    }

    private val base = "https://media.example.com/api"
    private var minted = 0
    private fun mint(): String = "random-${++minted}"

    @Test
    fun madeOnceAndKeptFromThenOn() {
        val store = Store()
        val first = castOwnerId(store, base, "3f2a-sub", ::mint)
        assertEquals(first, castOwnerId(store, "$base/", "3f2a-sub", ::mint))
        assertEquals(1, minted)
    }

    @Test
    fun anotherAccountOrServerHasItsOwn() {
        val store = Store()
        val mine = castOwnerId(store, base, "3f2a-sub", ::mint)
        assertNotEquals(mine, castOwnerId(store, base, "another-sub", ::mint))
        assertNotEquals(mine, castOwnerId(store, "https://other.example.org/api", "3f2a-sub", ::mint))
    }

    @Test
    fun anotherPhoneOfTheSameAccountHasItsOwn() {
        // Two phones: two stores, two random ids for the one account.
        var n = 0
        val phoneA = castOwnerId(Store(), base, "3f2a-sub") { "a-${++n}" }
        val phoneB = castOwnerId(Store(), base, "3f2a-sub") { "b-${++n}" }
        assertNotEquals(phoneA, phoneB)
    }

    @Test
    fun theStoreHoldsNeitherTheAccountNorTheServer() {
        val store = Store()
        castOwnerId(store, base, "3f2a-sub", ::mint)
        val key = store.kept.keys.single()
        assertFalse(key.contains("3f2a"))
        assertFalse(key.contains("example"))
        assertTrue(key.length >= 32)
    }
}
