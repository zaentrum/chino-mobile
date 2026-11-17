package cloud.nalet.chino.mobile.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The phone's server store on Android holds what it read from its file once
 * and what it wrote last. DataStore's own flow emits a value for each write,
 * and the emission for one write can come after a later write has landed:
 * such an emission is never read, and a read-modify-write after it never
 * writes the older blob back.
 */
class AndroidServerConfigStoreTest {
    /** The store's file in memory: an edit lands at once; the flow emits
     *  what the test hands it, when it does - as DataStore's own emission
     *  for an earlier edit can come late. [edits]: the file after each. */
    private class LateEmissions(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        @Volatile
        var onDisk: Preferences = initial
            private set
        val edits: MutableList<Preferences> = java.util.Collections.synchronizedList(mutableListOf())
        val emissions = MutableSharedFlow<Preferences>(replay = 16).apply { tryEmit(initial) }

        override val data: Flow<Preferences> = emissions

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(onDisk).also {
                onDisk = it
                edits += it
            }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun LateEmissions.blobOnDisk(): ServerConfigBlob =
        json.decodeFromString(ServerConfigBlob.serializer(), onDisk[stringPreferencesKey("blob_v1")]!!)

    /** Whether [what] holds throughout [ms]: time for anything that would
     *  follow an emission to do so. */
    private fun holdsFor(ms: Long, what: () -> Boolean): Boolean {
        val end = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < end) {
            if (!what()) return false
            Thread.sleep(5)
        }
        return what()
    }

    private val server = ServerConfig(baseUrl = "https://media.example.com/api/", issuer = "https://id.example.com", clientId = "chino")
    private val base = "https://media.example.com/api"

    @Test
    fun anEmissionForAnOlderWriteThatComesAfterANewerOneIsNotRead() {
        val file = LateEmissions()
        val store = AndroidServerConfigStore(file)
        runBlocking {
            store.save(server)
            store.saveSubtitleLanguages(base, listOf("en", "de"))
        }
        // DataStore's emission for the first write, after the second landed.
        file.emissions.tryEmit(file.edits.first())

        assertTrue(holdsFor(500) { store.subtitleLanguagesBlocking(base) == listOf("en", "de") })
        assertEquals(server, store.currentBlocking())
    }

    @Test
    fun aReadModifyWriteAfterSuchAnEmissionKeepsTheNewerBlob() {
        val file = LateEmissions()
        val store = AndroidServerConfigStore(file)
        runBlocking {
            store.save(server)
            store.saveSubtitleLanguages(base, listOf("en", "de"))
        }
        file.emissions.tryEmit(file.edits.first())
        Thread.sleep(300)

        runBlocking { store.saveWatchlistByKind(base, "anna", true) }

        val written = file.blobOnDisk()
        assertEquals(listOf("en", "de"), written.subtitleLanguages?.languages)
        assertEquals(mapOf("anna" to true), written.watchlistByKind?.accounts)
        assertEquals(server, written.config)
    }

    @Test
    fun aBlobWhoseKeptFieldsLackAFieldStillReadsWholly() {
        // Written before a field was added, or with one missing: what the
        // server said, and an account's choice, without their fields.
        val raw = """{"config":{"base_url":"https://media.example.com/api/","issuer":"https://id.example.com","client_id":"chino"},""" +
            """"recents":["https://media.example.com"],"features":{"base_url":"$base"},"watchlist_by_kind":{"accounts":{"anna":true}}}"""
        val store = AndroidServerConfigStore(LateEmissions(preferencesOf(stringPreferencesKey("blob_v1") to raw)))

        assertEquals(server, store.currentBlocking())
        assertEquals(listOf("https://media.example.com"), runBlocking { store.recents() })
        assertEquals(false, store.watchlistKindsBlocking(base))
        assertNull(store.watchlistByKindBlocking(base, "anna"))
    }
}
