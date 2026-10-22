package cloud.nalet.chino.mobile.data.downloads

import cloud.nalet.chino.mobile.currentTimeMillis
import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.api.ProgressBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A position played on this phone, for one account on one server: what
 * POST /items/{id}/progress sends, and when it was played.
 */
@Serializable
data class PlayedPosition(
    /** The API base of the server it goes to. */
    val server: String,
    @SerialName("account_id") val accountId: String,
    @SerialName("item_id") val itemId: String,
    @SerialName("position_sec") val positionSec: Int,
    @SerialName("duration_sec") val durationSec: Int,
    /** When it was played, ms since the epoch. */
    @SerialName("at") val atMs: Long,
    /** How many times sending it has failed so far. */
    val attempts: Int = 0,
) {
    fun sameTitle(other: PlayedPosition): Boolean =
        server == other.server && accountId == other.accountId && itemId == other.itemId
}

/** What the phone keeps of positions: the ones still to send, in the order
 *  they were played, and the last one played of each title - where a
 *  download resumes without the server. */
@Serializable
data class ProgressBook(
    val queue: List<PlayedPosition> = emptyList(),
    val last: List<PlayedPosition> = emptyList(),
)

/** Where the [ProgressBook] is kept: a file on Android, memory elsewhere. */
interface ProgressBookStore {
    suspend fun read(): ProgressBook
    suspend fun write(book: ProgressBook)
}

/** A store that keeps the book for as long as the process runs. */
class MemoryProgressBookStore : ProgressBookStore {
    private var book = ProgressBook()
    override suspend fun read(): ProgressBook = book
    override suspend fun write(book: ProgressBook) {
        this.book = book
    }
}

/** What sending one position came to. */
enum class SendOutcome {
    /** The server has it. */
    Sent,

    /** The server cannot take any now: no answer came (no connection, the
     *  server down behind its proxy) or the sign-in was not accepted. Kept,
     *  with the ones after it, for when it can - however long that takes. */
    Wait,

    /** The server answered that it could not take this one now (a server
     *  error, a timeout, too many requests): kept and sent again later, up
     *  to [MAX_ATTEMPTS] times; the ones after it go on. */
    Retry,

    /** The server refused it for good (the title is gone, not the viewer's
     *  to see, not a position it takes): dropped. */
    Drop,
}

/** How many times a position the server answers for but does not take is
 *  sent before it is dropped. */
const val MAX_ATTEMPTS = 10

/** What a flush did: [sent] and [dropped] positions, [left] still queued. */
data class FlushResult(val sent: Int, val dropped: Int, val left: Int)

/**
 * The positions played on this phone that the server does not have yet,
 * kept across restarts ([ProgressBookStore]) and sent in the order they
 * were played once the server can be reached ([flush]). One per title: a
 * later position of the same title takes its place, with its time, as the
 * server keeps one per title. Nothing but positions goes out - the server's
 * rule makes a title finished from where it was saved.
 */
class ProgressQueue(
    private val store: ProgressBookStore,
) {
    /** Guards the book; never held over a request. */
    private val mutex = Mutex()

    /** One flush at a time: two would send the same positions twice. */
    private val flushing = Mutex()

    /** Keeps [p] as the last position played of its title. */
    suspend fun remember(p: PlayedPosition) = edit { book ->
        book.copy(last = book.last.filterNot { it.sameTitle(p) } + p)
    }

    /** Queues [p] to be sent; it takes the place of an earlier one of the
     *  same title. True when it is the first one waiting for its account on
     *  its server: nothing was queued for them before. */
    suspend fun enqueue(p: PlayedPosition): Boolean = mutex.withLock {
        val book = store.read()
        val first = book.queue.none { it.server == p.server && it.accountId == p.accountId }
        store.write(book.copy(queue = (book.queue.filterNot { it.sameTitle(p) } + p).sortedBy { it.atMs }))
        first
    }

    /** Takes a queued position of [p]'s title off the queue: [p], newer,
     *  reached the server. */
    suspend fun supersede(p: PlayedPosition) = edit { book ->
        book.copy(queue = book.queue.filterNot { it.sameTitle(p) && it.atMs <= p.atMs })
    }

    /** The last position played on this phone of [itemId], for [accountId]
     *  on [server]; null when none was. */
    suspend fun lastPlayed(server: String, accountId: String, itemId: String): PlayedPosition? = mutex.withLock {
        store.read().last.firstOrNull { it.server == server && it.accountId == accountId && it.itemId == itemId }
    }

    /** The positions still to send for [accountId] on [server], oldest first. */
    suspend fun pending(server: String, accountId: String): List<PlayedPosition> = mutex.withLock {
        store.read().queue.filter { it.server == server && it.accountId == accountId }
    }

    /**
     * Sends [accountId]'s queued positions for [server] through [send], the
     * oldest first. One sent or refused for good leaves the queue; one the
     * server cannot take now stays, counted, and the ones after it go on -
     * dropped once it has failed [MAX_ATTEMPTS] times; when the server can
     * take none ([SendOutcome.Wait]) the flush stops with the rest kept,
     * uncounted. The queue is not locked while a position is on its way: the
     * player keeps saving, and a position played again meanwhile stays for
     * the next flush. Other accounts' and servers' positions stay: they go
     * with their own sign-in.
     */
    suspend fun flush(server: String, accountId: String, send: suspend (PlayedPosition) -> SendOutcome): FlushResult =
        flushing.withLock {
            var sent = 0
            var dropped = 0
            val pending = mutex.withLock { store.read().queue.filter { it.server == server && it.accountId == accountId } }
            for (p in pending) {
                when (send(p)) {
                    SendOutcome.Sent -> {
                        sent++
                        edit { book -> book.copy(queue = book.queue.filterNot { it == p }) }
                    }
                    SendOutcome.Drop -> {
                        dropped++
                        edit { book -> book.copy(queue = book.queue.filterNot { it == p }) }
                    }
                    SendOutcome.Retry -> {
                        val gaveUp = p.attempts + 1 >= MAX_ATTEMPTS
                        if (gaveUp) dropped++
                        edit { book ->
                            book.copy(
                                queue = if (gaveUp) {
                                    book.queue.filterNot { it == p }
                                } else {
                                    book.queue.map { if (it == p) it.copy(attempts = it.attempts + 1) else it }
                                },
                            )
                        }
                    }
                    SendOutcome.Wait -> break
                }
            }
            FlushResult(sent, dropped, left = pending(server, accountId).size)
        }

    private suspend fun edit(change: (ProgressBook) -> ProgressBook) = mutex.withLock {
        store.write(change(store.read()))
    }
}

/** What a failed POST of a position means for it: no answer, the server
 *  down behind its proxy (502-504) or a sign-in it did not accept (401)
 *  wait for the server; another server error, a timeout or too many
 *  requests (408, 429) is tried again, counted; any other refusal - the
 *  title gone, not the viewer's, not a position the server takes - drops
 *  it. */
fun sendOutcomeOf(e: Throwable): SendOutcome = when {
    e !is ApiStatusException -> SendOutcome.Wait
    e.status == 401 || e.status in SERVER_DOWN -> SendOutcome.Wait
    e.status >= 500 || e.status == 408 || e.status == 429 -> SendOutcome.Retry
    else -> SendOutcome.Drop
}

/** What a proxy answers for a server behind it that is down. */
private val SERVER_DOWN = 502..504

/**
 * Sends the player's positions, and keeps the ones the server does not get.
 * A position goes to the server at once ([post]); one that cannot - no
 * connection, the server down - is queued ([ProgressQueue]) and sent later
 * by [flush], when the server can be reached again. Every position is also
 * the title's last one played on this phone.
 */
class ProgressSync(
    private val queue: ProgressQueue,
    private val post: suspend (itemId: String, body: ProgressBody) -> Unit,
    /** The account signed in now, or null. */
    private val account: suspend () -> String?,
    /** The connected server's API base. */
    private val server: () -> String,
    private val scope: CoroutineScope,
    /** Called when the first position waiting for the server is queued
     *  (for the account, on the server): the platform sends the queue once
     *  a connection is back, even with the app closed, and again while the
     *  server does not take it. */
    private val onQueued: () -> Unit = {},
    /** Called with whether the server answered a position. */
    private val onReach: (Boolean) -> Unit = {},
    private val now: () -> Long = { currentTimeMillis() },
) {
    /** Saves a position of [itemId], in the app's scope: it outlives the
     *  player that played it. */
    fun save(itemId: String, positionSec: Int, durationSec: Int) {
        scope.launch { runCatching { saveNow(itemId, positionSec, durationSec) } }
    }

    /** [save], waited for: what became of the position. */
    suspend fun saveNow(itemId: String, positionSec: Int, durationSec: Int): SendOutcome? {
        val accountId = account() ?: return null
        val p = PlayedPosition(server(), accountId, itemId, positionSec, durationSec, now())
        queue.remember(p)
        val outcome = send(p)
        when (outcome) {
            SendOutcome.Sent, SendOutcome.Drop -> queue.supersede(p)
            SendOutcome.Retry, SendOutcome.Wait -> if (queue.enqueue(p)) onQueued()
        }
        return outcome
    }

    /** Sends what is queued for the account signed in now. Null when nobody
     *  is signed in. */
    suspend fun flush(): FlushResult? {
        val accountId = account() ?: return null
        return queue.flush(server(), accountId) { send(it) }
    }

    /** The last position played on this phone of [itemId], in seconds. */
    suspend fun lastPlayedSec(itemId: String): Int? {
        val accountId = account() ?: return null
        return queue.lastPlayed(server(), accountId, itemId)?.positionSec
    }

    /** Whether positions of the account signed in now are still to send. */
    suspend fun hasPending(): Boolean {
        val accountId = account() ?: return false
        return queue.pending(server(), accountId).isNotEmpty()
    }

    /** Whether positions of [itemId] are still to send: the phone's is newer
     *  than what the server has. */
    suspend fun hasUnsent(itemId: String): Boolean {
        val accountId = account() ?: return false
        return queue.pending(server(), accountId).any { it.itemId == itemId }
    }

    private suspend fun send(p: PlayedPosition): SendOutcome = try {
        post(p.itemId, ProgressBody(positionSec = p.positionSec, durationSec = p.durationSec))
        onReach(true)
        SendOutcome.Sent
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // An answer, even a refusal, is the server reached; a proxy's word
        // that the server behind it is down is not.
        onReach(e is ApiStatusException && e.status !in SERVER_DOWN)
        sendOutcomeOf(e)
    }
}
