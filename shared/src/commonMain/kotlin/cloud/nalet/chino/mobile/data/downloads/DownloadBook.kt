package cloud.nalet.chino.mobile.data.downloads

import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.api.ChinoApi
import kotlinx.coroutines.CancellationException

/*
 * Whose the downloads on the phone are, as pure rules over the titles
 * (DownloadedTitle by its key): an account claims a title, a second account
 * claims the same one rather than fetching it again, and the media goes with
 * the last claim. What each account sees, what the server's word takes off
 * the phone, and which downloads may run now. The platform keeps the files
 * and the media; these decide what happens to them.
 */

/** How long a download plays without the server saying the account may
 *  watch it: past it, it waits for the server before it plays again. */
const val CHECK_EVERY_MS = 30L * 24 * 60 * 60 * 1000

/** Media3's stop reasons, as the downloads use them: 0 runs. */
const val STOP_NONE = 0

/** None of its accounts is signed in on its server: it waits for one. */
const val STOP_OWNER_AWAY = 1

/** The phone ran out of room: it waits for "try again". */
const val STOP_NO_SPACE = 2

/** The server turned downloads off: it waits for the server. */
const val STOP_SERVER_OFF = 3

/** A change of the titles on the phone: [titles] as they are now, and the
 *  keys of the downloads that go with it - their last claim went - media,
 *  folder and all. */
data class BookChange(
    val titles: Map<String, DownloadedTitle>,
    val removed: List<String> = emptyList(),
)

/**
 * [owner]'s claim on [title]: added to the title another account has on the
 * phone already, else a title of its own. A claim the account has stays as
 * it was. [replace]: the title's metadata is [title]'s - a download that
 * starts over, with nothing of the old one on the phone.
 */
fun claim(
    titles: Map<String, DownloadedTitle>,
    title: DownloadedTitle,
    owner: Owner,
    now: Long,
    savedPositionSec: Int?,
    replace: Boolean = false,
): BookChange {
    require(title.server == owner.server) { "a title of another server" }
    val existing = titles[title.key]
    val base = if (existing == null || replace) title.copy(owners = existing?.owners.orEmpty()) else existing
    val mine = base.claimOf(owner.subject) ?: OwnerClaim(owner.subject, addedAtMs = now, savedPositionSec = savedPositionSec, checkedAtMs = now)
    val claimed = base.copy(owners = base.owners.filterNot { it.subject == owner.subject } + mine)
    return BookChange(titles + (claimed.key to claimed))
}

/** [subject]'s claim on the title [key] goes; the title with it, when it
 *  was the last one. */
fun unclaim(titles: Map<String, DownloadedTitle>, key: String, subject: String): BookChange {
    val title = titles[key] ?: return BookChange(titles)
    val left = title.owners.filterNot { it.subject == subject }
    return if (left.isEmpty()) {
        BookChange(titles - key, removed = listOf(key))
    } else {
        BookChange(titles + (key to title.copy(owners = left)))
    }
}

/** Every claim of [owner] goes - an explicit sign-out or removal of the
 *  account on this phone - and the titles whose last claim it was. */
fun unclaimAll(titles: Map<String, DownloadedTitle>, owner: Owner): BookChange {
    var book = BookChange(titles)
    for (t in titles.values) {
        if (t.ownedBy(owner)) {
            val next = unclaim(book.titles, t.key, owner.subject)
            book = BookChange(next.titles, book.removed + next.removed)
        }
    }
    return book
}

/** A server change: every title of [server] goes, whoever it was for. */
fun dropServer(titles: Map<String, DownloadedTitle>, server: String): BookChange {
    val gone = titles.values.filter { it.server == server }.map { it.key }
    return BookChange(titles - gone.toSet(), removed = gone)
}

/** The titles [owner] sees; none for nobody signed in. */
fun visibleTo(titles: Map<String, DownloadedTitle>, owner: Owner?): List<DownloadedTitle> =
    if (owner == null) emptyList() else titles.values.filter { it.ownedBy(owner) }

/** [owner]'s title of [itemId], if it has one on the phone. */
fun titleOf(titles: Map<String, DownloadedTitle>, owner: Owner?, itemId: String): DownloadedTitle? =
    owner?.let { titles[downloadKey(it.server, itemId)]?.takeIf { t -> t.ownedBy(it) } }

/** Whether [claim] waits for the server before its title plays again: it
 *  was not checked for [CHECK_EVERY_MS]. */
fun checkDue(claim: OwnerClaim, now: Long): Boolean = now - claim.checkedAtMs >= CHECK_EVERY_MS

/** How far apart the two answers that a title is no longer the account's
 *  must be before it is deleted: one wrong answer of a server having a bad
 *  moment never takes downloads off the phone. */
const val GONE_CONFIRM_MS = 60L * 60 * 1000

/** What the server's answer to a check came to: the titles as they are now,
 *  those it took off the phone for the account, and those it only marked -
 *  the first answer that they are not the account's any more. Not
 *  [answered]: the answer left out every title asked, which counts as none,
 *  and nothing changed. */
data class AvailabilityChange(
    val change: BookChange,
    val deleted: List<DownloadedTitle>,
    val marked: List<DownloadedTitle> = emptyList(),
    val answered: Boolean = true,
)

/**
 * The server's answer to which of [asked] (item ids) [owner] may watch now:
 * [available] are checked as of [now], and any mark on them goes. The others
 * are no longer the account's - the title left the library, or its cap no
 * longer allows it - by two answers at least [GONE_CONFIRM_MS] apart: the
 * first only marks the claim ([OwnerClaim.goneAtMs]) and changes nothing
 * else - it still plays, and its last good check stands; the second, an
 * hour or more after the first, takes the claim, the media with the last
 * one. Only an answer the server gave the owning account decides this; no
 * answer decides nothing - and an answer that leaves out every title asked
 * is none: a library imported anew or reset answers so for a while, and
 * that takes nothing off the phone, nor counts as a first answer.
 */
fun applyAvailability(
    titles: Map<String, DownloadedTitle>,
    owner: Owner,
    asked: Collection<String>,
    available: Set<String>,
    now: Long,
): AvailabilityChange {
    val askedSet = asked.toSet()
    val ownedAsked = titles.values.filter { it.ownedBy(owner) && it.itemId in askedSet && it.claimOf(owner.subject) != null }
    if (ownedAsked.isNotEmpty() && ownedAsked.none { it.itemId in available }) {
        return AvailabilityChange(BookChange(titles), emptyList(), emptyList(), answered = false)
    }
    var book = BookChange(titles)
    val deleted = ArrayList<DownloadedTitle>()
    val marked = ArrayList<DownloadedTitle>()
    for (t in titles.values) {
        if (!t.ownedBy(owner) || t.itemId !in askedSet) continue
        val claim = t.claimOf(owner.subject) ?: continue
        val goneSince = claim.goneAtMs
        when {
            t.itemId in available -> {
                val checked = t.withClaim(owner.subject) { it.copy(checkedAtMs = now, goneAtMs = null) }
                book = BookChange(book.titles + (t.key to checked), book.removed)
            }
            goneSince == null -> {
                val first = t.withClaim(owner.subject) { it.copy(goneAtMs = now) }
                marked += first
                book = BookChange(book.titles + (t.key to first), book.removed)
            }
            now - goneSince >= GONE_CONFIRM_MS -> {
                deleted += t
                val next = unclaim(book.titles, t.key, owner.subject)
                book = BookChange(next.titles, book.removed + next.removed)
            }
            // Said again within the hour: marked as it was, since the first.
            else -> marked += t
        }
    }
    return AvailabilityChange(book, deleted, marked)
}

/** [title] with [subject]'s claim changed by [change]. */
private fun DownloadedTitle.withClaim(subject: String, change: (OwnerClaim) -> OwnerClaim): DownloadedTitle =
    copy(owners = owners.map { if (it.subject == subject) change(it) else it })

/**
 * The stop reason a download should have now: it runs only for an account
 * signed in on its server ([active]) - another account's waits, unsigned,
 * and fetches nothing - and only while the server offers downloads; one the
 * phone ran out of room for waits for "try again".
 */
fun stopReasonFor(title: DownloadedTitle?, active: Owner?, serverOffersDownloads: Boolean): Int = when {
    title == null || active == null || !title.ownedBy(active) -> STOP_OWNER_AWAY
    !serverOffersDownloads -> STOP_SERVER_OFF
    title.failure == DownloadFailure.NoSpace -> STOP_NO_SPACE
    else -> STOP_NONE
}

/** How many titles one POST /me/available asks about. */
const val MAX_AVAILABLE_IDS = 500

/**
 * The server's answer to which of [ids] the account with [bearer] may watch
 * now (POST /me/available), asked [MAX_AVAILABLE_IDS] at a time; null when
 * it gave none - no answer, a refusal of the request: neither says that a
 * title is not the account's. A server from before the route (404, 405 for
 * it) is asked title by title instead, as its title route answers them
 * ([titleByTitle]): a title it answers 404 or 410 for is not the account's.
 */
suspend fun askAvailable(api: ChinoApi, ids: List<String>, bearer: String?): Set<String>? {
    val out = HashSet<String>()
    for (chunk in ids.chunked(MAX_AVAILABLE_IDS)) {
        val answer = try {
            api.available(chunk, bearer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiStatusException) {
            if (e.status == 404 || e.status == 405) return titleByTitle(api, ids, bearer)
            return null
        } catch (e: Exception) {
            return null
        }
        out += answer
    }
    return out
}

/** At most this many titles are asked about one by one, on a server from
 *  before POST /me/available: more is no answer. */
const val MAX_TITLE_BY_TITLE = 100

/** [ids] asked about one by one (GET /items/{id}, the title route a capped
 *  account is held to its cap on): those the server answers 2xx for; null
 *  for any other answer than 404 or 410, or none - and where it answers 404
 *  or 410 for every one, which says nothing of any of them. */
private suspend fun titleByTitle(api: ChinoApi, ids: List<String>, bearer: String?): Set<String>? {
    if (ids.size > MAX_TITLE_BY_TITLE) return null
    val out = HashSet<String>()
    for (id in ids) {
        try {
            api.getItem(id, bearer)
            out += id
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiStatusException) {
            if (e.status != 404 && e.status != 410) return null
        } catch (e: Exception) {
            return null
        }
    }
    return out.takeIf { it.isNotEmpty() || ids.isEmpty() }
}
