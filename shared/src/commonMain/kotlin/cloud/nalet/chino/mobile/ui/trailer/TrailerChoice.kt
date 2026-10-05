package cloud.nalet.chino.mobile.ui.trailer

import cloud.nalet.chino.mobile.data.model.Extra
import cloud.nalet.chino.mobile.data.model.Item
import cloud.nalet.chino.mobile.data.model.Trailer

/*
 * Which trailer the Trailer button plays, by the rules every client keeps
 * (web, mobile, TV): a trailer this server plays first, else the title's
 * trailer link, else no button. Pure, so commonTest runs it everywhere.
 */

/** The kinds of extra the Trailer button plays, the first preferred: a
 *  trailer, then a teaser. */
val TRAILER_KINDS: List<String> = listOf("trailer", "teaser")

/**
 * The title's own trailer among its [extras] (in the server's order, as a
 * viewer sees them), or null when none plays: of the playable extras, a
 * trailer before a teaser ([TRAILER_KINDS]); of those of one kind, the first
 * for the whole title before the first of one season.
 */
fun localTrailer(extras: List<Extra>): Extra? {
    for (kind in TRAILER_KINDS) {
        val ofKind = extras.filter { it.playable && it.kind.equals(kind, ignoreCase = true) }
        val pick = ofKind.firstOrNull { it.seasonNumber == null } ?: ofKind.firstOrNull()
        if (pick != null) return pick
    }
    return null
}

/** Of the title's trailer links, the most likely "Official Trailer" on
 *  YouTube, else one called a trailer, else the first; YouTube's before any
 *  other site's — chino-web's pickTrailer and the TV's. Null without links. */
fun pickTrailer(trailers: List<Trailer>): Trailer? {
    if (trailers.isEmpty()) return null
    val yt = trailers.filter { (it.site ?: "").contains("youtube", ignoreCase = true) }
    val pool = if (yt.isNotEmpty()) yt else trailers
    return pool.firstOrNull {
        val t = it.title.orEmpty()
        t.contains("official", ignoreCase = true) && t.contains("trailer", ignoreCase = true)
    } ?: pool.firstOrNull { it.title.orEmpty().contains("trailer", ignoreCase = true) }
        ?: pool.first()
}

/** What the Trailer button does. */
sealed interface TrailerChoice {
    /** Plays [extra] in the app, on the trailer screen. */
    data class Local(val extra: Extra) : TrailerChoice

    /** Opens [trailer]'s link outside the app. */
    data class Link(val trailer: Trailer) : TrailerChoice
}

/** The Trailer button of [item]: its trailer from this server
 *  ([localTrailer]), else its link ([pickTrailer]), else null — no button. */
fun trailerChoice(item: Item): TrailerChoice? =
    localTrailer(item.extras)?.let { TrailerChoice.Local(it) }
        ?: pickTrailer(item.trailers)?.let { TrailerChoice.Link(it) }

/** What a button that opens [link] says: "Watch on YouTube". */
fun trailerLinkLabel(link: Trailer): String =
    link.site?.takeIf { it.isNotBlank() }?.let { "Watch on $it" } ?: "Watch the trailer"
