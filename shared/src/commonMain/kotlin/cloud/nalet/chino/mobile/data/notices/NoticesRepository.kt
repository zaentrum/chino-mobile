package cloud.nalet.chino.mobile.data.notices

import cloud.nalet.chino.mobile.currentTimeMillis
import cloud.nalet.chino.mobile.data.api.ChinoApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The signed-in person's notices, shared by the bell in the top bar and the
 * list it opens: chino-api's /v1/notices, asked for again now and then
 * ([refresh]) and changed the moment the person reads or deletes one, then
 * sent. A change chino-api does not make — portal-api did not answer, the
 * notice is gone — is undone by asking for the list again, as the portal's
 * bell does.
 *
 * Best effort throughout, and nothing here throws: [state]'s list is null —
 * nothing shown — until chino-api answers with notices available, again when
 * it answers that they are not, and after [clear] until the next account's
 * own come. An answer that does not come keeps what is shown.
 */
class NoticesRepository(
    private val api: ChinoApi,
    /** Application-lifetime scope (AppContainer.appScope): a change is sent
     *  even when the screen that made it is gone. */
    private val scope: CoroutineScope,
    private val now: () -> Long = ::currentTimeMillis,
) {
    private val _state = MutableStateFlow(NoticesState())
    val state: StateFlow<NoticesState> = _state.asStateFlow()

    /** Asks chino-api for the list again. An answer asked for before a
     *  [clear] is for an account signed out since, and dropped. */
    suspend fun refresh() {
        val asked = _state.value.session
        val answer = try {
            noticesAnswer(api.notices())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        _state.update { if (it.session == asked) it.copy(list = answer) else it }
    }

    /** Reads [notice] — opening it does — and sends that; null when there
     *  is nothing to send. */
    fun read(notice: Notice): Job? {
        if (notice.readAt != null) return null
        val at = formatInstant(now())
        return change({ markRead(it, notice.id, at) }) { api.readNotice(notice.id) }
    }

    /** Reads every notice, and sends that. */
    fun readAll(): Job? {
        val at = formatInstant(now())
        return change({ markAllRead(it, at) }) { api.readAllNotices() }
    }

    /** Deletes [notice], and sends that. */
    fun delete(notice: Notice): Job? = change({ removeNotice(it, notice.id) }) { api.deleteNotice(notice.id) }

    /** Forgets the notices shown: the account they are for is signed out,
     *  switched away from or deleted. */
    fun clear() {
        _state.update { NoticesState(session = it.session + 1) }
    }

    /** Shows [local] at once and sends [remote]; asks for the list again
     *  when the change is not made. Nothing changes while nothing is shown. */
    private fun change(local: (NoticeList) -> NoticeList, remote: suspend () -> Unit): Job? {
        if (_state.value.list == null) return null
        _state.update { it.copy(list = it.list?.let(local)) }
        return scope.launch {
            try {
                remote()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                refresh()
            }
        }
    }
}

/** What the bell and the list show: [list] null shows nothing. [session]
 *  counts the times the notices were forgotten — an answer asked for in an
 *  earlier session is dropped. */
data class NoticesState(val list: NoticeList? = null, val session: Int = 0)
