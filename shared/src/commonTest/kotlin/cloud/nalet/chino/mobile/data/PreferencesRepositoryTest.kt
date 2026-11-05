package cloud.nalet.chino.mobile.data

import cloud.nalet.chino.mobile.data.api.Preferences
import cloud.nalet.chino.mobile.data.api.PreferencesPatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/** The account's settings: a change shows at once, and goes back with a line
 *  if it can't be saved. */
class PreferencesRepositoryTest {
    private val on = Preferences(watchlistByKind = true)
    private val off = Preferences(watchlistByKind = false)
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun repository(
        load: suspend () -> Preferences = { off },
        save: suspend (PreferencesPatch) -> Preferences = { Preferences(it.watchlistByKind) },
    ) = PreferencesRepository(load, save, scope)

    @Test
    fun nothingIsKnownUntilTheServerAnswersAndNoAnswerChangesNothing() = runTest {
        var fail = true
        val repo = repository(load = { if (fail) throw Exception("no connection") else on })
        assertNull(repo.preferences.value)
        repo.refresh()
        assertNull(repo.preferences.value)
        fail = false
        repo.refresh()
        assertEquals(on, repo.preferences.value)
        fail = true
        repo.refresh()
        assertEquals(on, repo.preferences.value)
    }

    @Test
    fun aChangeShowsBeforeTheServerAnswers() = runTest {
        val answer = CompletableDeferred<Preferences>()
        val sent = mutableListOf<PreferencesPatch>()
        val repo = repository(save = { sent += it; answer.await() })
        repo.refresh()
        repo.setWatchlistByKind(true)
        assertEquals(on, repo.preferences.value)
        answer.complete(on)
        assertEquals(on, repo.preferences.value)
        assertEquals(listOf(PreferencesPatch(watchlistByKind = true)), sent)
        assertFalse(repo.saveFailed.value)
    }

    @Test
    fun aChangeThatCantBeSavedGoesBackAndSaysSoUntilTheNextChange() = runTest {
        var fail = true
        val repo = repository(save = { if (fail) throw Exception("no connection") else Preferences(it.watchlistByKind) })
        repo.refresh()
        repo.setWatchlistByKind(true)
        assertEquals(off, repo.preferences.value)
        assertTrue(repo.saveFailed.value)
        fail = false
        repo.setWatchlistByKind(true)
        assertEquals(on, repo.preferences.value)
        assertFalse(repo.saveFailed.value)
    }

    @Test
    fun changesMadeQuicklyEndWithTheLastSentInOrder() = runTest {
        val first = CompletableDeferred<Preferences>()
        val sent = mutableListOf<Boolean>()
        val repo = repository(save = { patch ->
            sent += patch.watchlistByKind
            if (sent.size == 1) first.await() else Preferences(patch.watchlistByKind)
        })
        repo.refresh()
        repo.setWatchlistByKind(true)
        repo.setWatchlistByKind(false)
        // The first is on its way; the second waits its turn and shows.
        assertEquals(off, repo.preferences.value)
        first.complete(on)
        assertEquals(off, repo.preferences.value)
        assertEquals(listOf(true, false), sent)
    }

    @Test
    fun aChangeReplacedBeforeItsTurnIsNeverSent() = runTest {
        val first = CompletableDeferred<Preferences>()
        val sent = mutableListOf<Boolean>()
        val repo = repository(save = { patch ->
            sent += patch.watchlistByKind
            if (sent.size == 1) first.await() else Preferences(patch.watchlistByKind)
        })
        repo.refresh()
        repo.setWatchlistByKind(true)
        repo.setWatchlistByKind(false)
        repo.setWatchlistByKind(true)
        first.complete(on)
        assertEquals(on, repo.preferences.value)
        assertEquals(listOf(true, true), sent)
    }

    @Test
    fun anOlderChangeFailingDoesntUndoANewerOne() = runTest {
        val first = CompletableDeferred<Preferences>()
        var saves = 0
        val repo = repository(save = { patch ->
            if (++saves == 1) first.await() else Preferences(patch.watchlistByKind)
        })
        repo.refresh()
        repo.setWatchlistByKind(true)
        repo.setWatchlistByKind(false)
        first.completeExceptionally(Exception("no connection"))
        assertEquals(off, repo.preferences.value)
        assertFalse(repo.saveFailed.value)
    }

    @Test
    fun anAnswerReadBeforeAChangeDoesntOverwriteIt() = runTest {
        val answer = CompletableDeferred<Preferences>()
        val started = CompletableDeferred<Unit>()
        val repo = repository(load = { started.complete(Unit); answer.await() })
        val refresh = scope.launch { repo.refresh() }
        started.await()
        repo.setWatchlistByKind(true)
        answer.complete(off)
        refresh.join()
        assertEquals(on, repo.preferences.value)
    }

    @Test
    fun signingOutForgetsTheSettingAndTheFailure() = runTest {
        val repo = repository(save = { throw Exception("no connection") }, load = { on })
        repo.refresh()
        repo.setWatchlistByKind(false)
        assertTrue(repo.saveFailed.value)
        repo.clear()
        assertNull(repo.preferences.value)
        assertFalse(repo.saveFailed.value)
    }
}
