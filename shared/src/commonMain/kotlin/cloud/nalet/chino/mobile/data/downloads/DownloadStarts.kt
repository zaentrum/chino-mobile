package cloud.nalet.chino.mobile.data.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Downloads asked for on a page, worked out and handed to the platform in
 * the app's [scope]: a page left before the server answered - a season of
 * twenty episodes takes a while - still gets every one of them. What is
 * being worked out ([working]) and why one could not start ([failed])
 * outlive the page as well, so its controls say so when it is opened again.
 */
class DownloadStarts(
    private val scope: CoroutineScope,
    /** Works [itemId]'s download out and starts it; why it could not, or
     *  null once it is on its way. */
    private val startOne: suspend (itemId: String) -> DownloadFailure?,
) {
    private val _working = MutableStateFlow<Set<String>>(emptySet())

    /** The titles being worked out now: their controls show them queued. */
    val working: StateFlow<Set<String>> = _working.asStateFlow()

    private val _failed = MutableStateFlow<Map<String, DownloadFailure>>(emptyMap())

    /** Why a title asked for could not start, until it is asked for again. */
    val failed: StateFlow<Map<String, DownloadFailure>> = _failed.asStateFlow()

    fun start(itemId: String) = startAll(listOf(itemId))

    /** Starts [ids]' downloads, one after the other in their order - the
     *  order the platform's manager runs them in. One already being worked
     *  out is not asked for twice. */
    fun startAll(ids: List<String>) {
        var fresh = emptyList<String>()
        _working.update { now ->
            fresh = ids.distinct().filterNot { it in now }
            now + fresh
        }
        if (fresh.isEmpty()) return
        _failed.update { it - fresh.toSet() }
        scope.launch {
            try {
                for (id in fresh) {
                    val failure = try {
                        startOne(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failureOf(e)
                    }
                    if (failure != null) _failed.update { it + (id to failure) }
                    _working.update { it - id }
                }
            } finally {
                _working.update { it - fresh.toSet() }
            }
        }
    }
}
