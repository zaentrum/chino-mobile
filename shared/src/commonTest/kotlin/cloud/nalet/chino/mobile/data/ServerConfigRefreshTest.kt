package cloud.nalet.chino.mobile.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The stored server settings are held against what the server says now. */
class ServerConfigRefreshTest {
    private class MemoryStore(initial: ServerConfig?) : ServerConfigStore {
        private val state = MutableStateFlow(initial)
        var saves = 0
        override val config: Flow<ServerConfig?> = state
        override suspend fun current(): ServerConfig? = state.value
        override fun currentBlocking(): ServerConfig? = state.value
        override suspend fun recents(): List<String> = emptyList()
        override suspend fun addRecent(url: String) = Unit
        override suspend fun save(config: ServerConfig) {
            saves++
            state.value = config
        }
        override suspend fun clear() {
            state.value = null
        }
    }

    private val stored = ServerConfig(
        baseUrl = "https://media.example.org/api",
        issuer = "https://id.example.org/realms/home",
        clientId = "chino",
        authEndpoint = "https://id.example.org/realms/home/protocol/openid-connect/auth",
        tokenEndpoint = "https://id.example.org/realms/home/protocol/openid-connect/token",
    )

    @Test
    fun theServerNamingAnotherClientIsStoredAndSaysSo() = runTest {
        // The operator set up a client of the server's own for the app: the
        // next sign-in has to go through it, not the one stored at connect.
        val store = MemoryStore(stored)
        val asked = mutableListOf<String>()
        val now = stored.copy(clientId = "chino-home")

        val refresh = refreshServerConfig(store) { origin -> asked += origin; BootstrapResult.Ok(now) }

        assertEquals(ServerRefresh.Changed, refresh)
        assertEquals(listOf("https://media.example.org"), asked, "the server's origin is asked, not its API path")
        assertEquals(now, store.current())
        assertEquals(1, store.saves)
    }

    @Test
    fun theSameSettingsAreLeftAsTheyAre() = runTest {
        val store = MemoryStore(stored)
        assertEquals(ServerRefresh.Unchanged, refreshServerConfig(store) { BootstrapResult.Ok(stored.copy()) })
        assertEquals(0, store.saves)
    }

    @Test
    fun aServerThatCannotBeAskedChangesNothing() = runTest {
        val store = MemoryStore(stored)
        val unreachable = refreshServerConfig(store) { BootstrapResult.Fail(BootstrapResult.Fail.Kind.UNREACHABLE) }
        val thrown = refreshServerConfig(store) { error("no route to host") }
        assertEquals(ServerRefresh.Unknown, unreachable)
        assertEquals(ServerRefresh.Unknown, thrown)
        assertEquals(stored, store.current())
        assertEquals(0, store.saves)
    }

    @Test
    fun withoutAConnectedServerNothingIsAsked() = runTest {
        var asked = 0
        assertEquals(ServerRefresh.Unknown, refreshServerConfig(MemoryStore(null)) { asked++; BootstrapResult.Ok(stored) })
        assertEquals(0, asked)
    }
}
