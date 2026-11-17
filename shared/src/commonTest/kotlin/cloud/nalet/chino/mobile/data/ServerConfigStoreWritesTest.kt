package cloud.nalet.chino.mobile.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The server store is one blob, and each write of it reads it first: two
 * writes of different fields at once - a server's subtitle languages and an
 * account's Separate movies and shows, both read as settings open - keep both.
 * Another server saved takes the old one's accounts' choices with it.
 */
class ServerConfigStoreWritesTest {
    /** The store in memory, whose write lets others run before it lands, as
     *  the platforms' writes do. */
    private class SlowStore : BaseServerConfigStore() {
        private val state = MutableStateFlow(ServerConfigBlob())
        override fun readBlobBlocking(): ServerConfigBlob = state.value
        override suspend fun writeBlob(blob: ServerConfigBlob) {
            yield()
            state.value = blob
        }
        override val blobFlow = state
    }

    @Test
    fun twoWritesOfDifferentFieldsAtOnceKeepBoth() = runTest {
        val store = SlowStore()
        val server = "https://media.example.com"
        launch { store.saveSubtitleLanguages(server, listOf("en", "de")) }
        launch { store.saveWatchlistByKind(server, "anna", true) }
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("en", "de"), store.subtitleLanguagesBlocking(server))
        assertEquals(true, store.watchlistByKindBlocking(server, "anna"))
    }

    @Test
    fun anotherServerSavedTakesTheOldOnesChoicesWithIt() = runTest {
        val store = SlowStore()
        val one = ServerConfig(baseUrl = "https://media.example.com", issuer = "", clientId = "chino")
        val two = ServerConfig(baseUrl = "https://other.example.org", issuer = "", clientId = "chino")
        store.save(one)
        store.saveWatchlistByKind(one.baseUrl, "anna", true)

        // The same server saved again - its config refreshed - keeps them.
        store.save(one)
        assertEquals(true, store.watchlistByKindBlocking(one.baseUrl, "anna"))

        store.save(two)
        assertNull(store.watchlistByKindBlocking(one.baseUrl, "anna"))
    }

    @Test
    fun whatAServerSaidItOffersIsKeptForItAloneAndGoesWithIt() = runTest {
        val store = SlowStore()
        val one = ServerConfig(baseUrl = "https://media.example.com/api/", issuer = "", clientId = "chino")
        val two = ServerConfig(baseUrl = "https://other.example.org/api/", issuer = "", clientId = "chino")
        val oneBase = one.baseUrl.trimEnd('/')
        store.save(one)
        assertNull(store.watchlistKindsBlocking(oneBase))
        store.saveWatchlistKinds(oneBase, true)
        assertEquals(true, store.watchlistKindsBlocking(oneBase))
        // Never another server's.
        assertNull(store.watchlistKindsBlocking(two.baseUrl.trimEnd('/')))

        // Its config refreshed: kept.
        store.save(one.copy(issuer = "https://id.example.com"))
        assertEquals(true, store.watchlistKindsBlocking(oneBase))

        // A change of server: gone.
        store.save(two)
        assertNull(store.watchlistKindsBlocking(oneBase))
        assertNull(store.watchlistKindsBlocking(two.baseUrl.trimEnd('/')))

        // The server forgotten: gone too.
        store.saveWatchlistKinds(two.baseUrl.trimEnd('/'), false)
        assertEquals(false, store.watchlistKindsBlocking(two.baseUrl.trimEnd('/')))
        store.clear()
        assertNull(store.watchlistKindsBlocking(two.baseUrl.trimEnd('/')))
    }

    @Test
    fun whatTheServerOffersAndAnAccountsChoiceWrittenAtOnceAreBothKept() = runTest {
        val store = SlowStore()
        val server = "https://media.example.com"
        launch { store.saveWatchlistKinds(server, true) }
        launch { store.saveWatchlistByKind(server, "anna", true) }
        testScheduler.advanceUntilIdle()

        assertEquals(true, store.watchlistKindsBlocking(server))
        assertEquals(true, store.watchlistByKindBlocking(server, "anna"))
    }

    @Test
    fun twoAccountsChoicesWrittenAtOnceAreBothKept() = runTest {
        val store = SlowStore()
        val server = "https://media.example.com"
        launch { store.saveWatchlistByKind(server, "anna", true) }
        launch { store.saveWatchlistByKind(server, "ben", false) }
        testScheduler.advanceUntilIdle()

        assertEquals(true, store.watchlistByKindBlocking(server, "anna"))
        assertEquals(false, store.watchlistByKindBlocking(server, "ben"))
    }

    @Test
    fun theSameServersAddressWithOrWithoutItsTrailingSlashKeepsWhatWasKeptForIt() = runTest {
        val store = SlowStore()
        val base = "https://media.example.com/api"
        store.save(ServerConfig(baseUrl = base, issuer = "", clientId = "chino"))
        store.saveWatchlistKinds(base, true)
        store.saveWatchlistByKind(base, "anna", true)

        store.save(ServerConfig(baseUrl = "$base/", issuer = "", clientId = "chino"))

        assertEquals(true, store.watchlistKindsBlocking(base))
        assertEquals(true, store.watchlistByKindBlocking(base, "anna"))
    }
}
