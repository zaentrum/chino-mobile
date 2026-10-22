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
 */
class ServerSubtitleLanguages(private val fetch: suspend () -> List<String>) {
    private val lock = Mutex()

    @Volatile
    private var known: List<String>? = null

    /** The server's languages, asked for the first time they are wanted. */
    suspend fun get(): List<String> = lock.withLock {
        known ?: try {
            fetch().filter { it.isNotBlank() }.also { known = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiStatusException) {
            if (e.status == 404) emptyList<String>().also { known = it } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** What the server said so far, without asking: a title playing from
     *  the phone, with no server to ask. */
    val current: List<String> get() = known.orEmpty()
}
