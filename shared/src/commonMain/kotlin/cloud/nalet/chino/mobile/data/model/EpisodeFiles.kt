package cloud.nalet.chino.mobile.data.model

/**
 * What says which file an episode plays, as chino-api sends it on whatever
 * lists episodes. One file can hold several episodes (a double-length finale
 * listed as two): the file is its holder's, the first episode it holds, whose
 * [covers] are the others in episode order and whose [episodeEnd] is the last
 * episode number the file holds; each of the others has [coveredBy], the
 * holder's id. Every other episode has none of the three.
 */
interface FileMember {
    val id: String
    val episodeNumber: Int?
    val coveredBy: String?
    val covers: List<String>
    val episodeEnd: Int?
}

/**
 * One file's entry: the one thing that plays. [episode] is the holder, the
 * episode it is listed, played and marked under; [covered] the other episodes
 * the file holds, in order (empty for most files); [first] and [last] the
 * first and the last episode number it holds.
 */
data class FileEntry<E : FileMember>(val episode: E, val covered: List<E>, val first: Int, val last: Int) {
    /** Every episode the file holds: its holder, then the covered ones. */
    val held: List<E> get() = listOf(episode) + covered

    /** Every id the entry answers to. */
    val ids: List<String> get() = held.map { it.id }
}

/**
 * The entries of a list of episodes in order, one per file: the episodes a
 * file holds besides its holder fold into the holder's entry. An episode
 * whose holder is not in the list keeps an entry of its own (it still plays
 * its holder's file). The file is the truth: its episodes are listed in its
 * entry, and none of them plays on its own.
 */
fun <E : FileMember> fileEntries(episodes: List<E>): List<FileEntry<E>> {
    val here = episodes.mapTo(HashSet()) { it.id }
    val holderOf = HashMap<String, String>()
    for (e in episodes) for (c in e.covers) if (c != e.id) holderOf[c] = e.id
    for (e in episodes) {
        val h = e.coveredBy
        if (!h.isNullOrEmpty() && h != e.id && h in here) holderOf[e.id] = h
    }
    val coveredOf = LinkedHashMap<String, MutableList<E>>()
    for (e in episodes) holderOf[e.id]?.let { coveredOf.getOrPut(it) { mutableListOf() }.add(e) }
    return episodes.filter { it.id !in holderOf }.map { e ->
        val covered = coveredOf[e.id].orEmpty()
        val first = e.episodeNumber ?: 0
        val last = maxOf(first, e.episodeEnd ?: first, covered.maxOfOrNull { it.episodeNumber ?: 0 } ?: first)
        FileEntry(e, covered, first, last)
    }
}

/**
 * A list of watched rows, newest first, one row per file: chino-api marks
 * every episode of a file watched together, so the history holds a row for
 * each. The file keeps the place of its first row and is shown as its holder
 * when the list has the holder's row, else as the row it was.
 */
fun <E : FileMember> oneRowPerFile(rows: List<E>): List<E> {
    val byId = rows.associateBy { it.id }
    val holderOf = HashMap<String, String>()
    for (r in rows) for (c in r.covers) if (c != r.id) holderOf[c] = r.id
    for (r in rows) {
        val h = r.coveredBy
        if (!h.isNullOrEmpty() && h != r.id) holderOf[r.id] = h
    }
    val seen = HashSet<String>()
    val out = ArrayList<E>(rows.size)
    for (r in rows) {
        val file = holderOf[r.id] ?: r.id
        if (seen.add(file)) out.add(byId[file] ?: r)
    }
    return out
}

private fun two(n: Int) = n.toString().padStart(2, '0')

/**
 * S03E18, or S03E18–21 for a file that holds several episodes ([last] past
 * [first]). A part that is not known is left out: "E18" without a season,
 * "S03" without an episode, "" without either.
 */
fun episodeCode(season: Int?, first: Int?, last: Int? = null): String = buildString {
    season?.let { append('S').append(two(it)) }
    first?.let {
        append('E').append(two(it))
        if (last != null && last > it) append('–').append(two(last))
    }
}
