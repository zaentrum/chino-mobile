package cloud.nalet.chino.mobile.data

import cloud.nalet.chino.mobile.data.api.Preferences
import cloud.nalet.chino.mobile.data.api.PreferencesPatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the person has set for their account, kept by the server
 * (GET and PATCH /v1/me/preferences), so the phone and the TV agree. The
 * screens that use it call [refresh] as they open.
 *
 * A change shows at once and is saved on [scope], the app's, so it is saved
 * even should the screen be gone by then. Requests run one at a time, so the
 * server ends up with the last change made; a change a newer one has replaced
 * before its turn is never sent. Only the last change's answer shows: if it
 * can't be saved, the setting goes back to what the server last said and
 * [saveFailed] holds until the next change.
 */
class PreferencesRepository(
    private val load: suspend () -> Preferences,
    private val save: suspend (PreferencesPatch) -> Preferences,
    private val scope: CoroutineScope,
) {
    private val _preferences = MutableStateFlow<Preferences?>(null)

    /** Null until the server has answered, which the screens draw as off. */
    val preferences: StateFlow<Preferences?> = _preferences.asStateFlow()

    private val _saveFailed = MutableStateFlow(false)
    val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

    private val requests = Mutex()

    /** What the server last said; guarded by [requests]. */
    private var confirmed: Preferences? = null

    /** Counts changes and sign-outs: an answer older than the count is stale. */
    private val latest = MutableStateFlow(0)

    /** Asks the server. No answer leaves the setting as it is. */
    suspend fun refresh() {
        requests.withLock {
            val asked = latest.value
            val answer = try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return
            }
            if (latest.value == asked) {
                confirmed = answer
                _preferences.value = answer
            }
        }
    }

    fun setWatchlistByKind(value: Boolean) {
        val change = latest.updateAndGet { it + 1 }
        _preferences.value = (_preferences.value ?: Preferences()).copy(watchlistByKind = value)
        _saveFailed.value = false
        scope.launch {
            requests.withLock {
                if (latest.value != change) return@withLock
                try {
                    val saved = save(PreferencesPatch(watchlistByKind = value))
                    confirmed = saved
                    if (latest.value == change) _preferences.value = saved
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (latest.value == change) {
                        _preferences.value = confirmed
                        _saveFailed.value = true
                    }
                }
            }
        }
    }

    /** Forgets the setting: the account it belongs to is signed out. */
    suspend fun clear() {
        requests.withLock {
            latest.updateAndGet { it + 1 }
            confirmed = null
            _preferences.value = null
            _saveFailed.value = false
        }
    }
}
