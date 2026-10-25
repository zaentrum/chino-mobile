package cloud.nalet.chino.mobile.data

import cloud.nalet.chino.mobile.data.api.ApiStatusException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * The subtitle languages the server is set up for (GET
 * /subtitles/languages: BCP 47 tags in its setting's order), asked for once
 * and kept for the session: the subtitles menu lists them after the
 * viewer's own language, before "more languages". A server from before the
 * route (404) has none, and is not asked again; no answer, or another
 * refusal, counts as none for now, and the next menu asks again.
 *
 * What the server answered is also kept on the phone ([keep]) and comes
 * back with the app ([kept]): a download's menu after a start with the
 * server out of reach has the server's languages, as online.
 */
class ServerSubtitleLanguages(
    /** What the server named the last time it answered, kept on the phone;
     *  null where it never answered. */
    private val kept: List<String>? = null,
    /** Keeps an answer on the phone, for the next start. */
    private val keep: suspend (List<String>) -> Unit = {},
    private val fetch: suspend () -> List<String>,
) {
    private val lock = Mutex()

    @Volatile
    private var known: List<String>? = null

    /** The server's languages, asked for the first time they are wanted;
     *  none for now where it did not answer (the menu's rule). */
    suspend fun get(): List<String> = answered().orEmpty()

    /**
     * What the server answered - its languages, none for a server that names
     * none or has no such route (404) - or null where it did not answer, or
     * refused otherwise: not knowing is not "none". Settings asks this way,
     * where none means every language; asked again next time.
     */
    suspend fun answered(): List<String>? = lock.withLock {
        known ?: try {
            fetch().filter { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiStatusException) {
            if (e.status == 404) emptyList() else null
        } catch (e: Exception) {
            null
        }?.also { answer ->
            known = answer
            if (answer != kept) runCatching { keep(answer) }
        }
    }

    /** What the server said, without asking: this session's answer, else
     *  the one kept on the phone from an earlier one - a title playing from
     *  the phone, with no server to ask. */
    val current: List<String> get() = known ?: kept.orEmpty()
}
