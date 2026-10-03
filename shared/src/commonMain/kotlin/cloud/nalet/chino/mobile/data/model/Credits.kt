package cloud.nalet.chino.mobile.data.model

/*
 * A title's credits as the detail page shows them — the actors, then the crew
 * grouped by role under a label — and a person's roles on a title as the
 * person page names them. A port of chino-web's lib/credits.ts, so the two
 * clients group, order and name credits the same way. Pure: CreditsTest.
 */

/**
 * The roles chino has names for, in the order the detail page lists them:
 * katalog-api's vocabulary, in katalog-api's order. A role is an open token,
 * so any other one is valid too; it is shown titleized, after these.
 */
val KNOWN_ROLES: List<String> = listOf(
    "actor",
    "creator",
    "director",
    "writer",
    "producer",
    "composer",
    "cinematographer",
    "editor",
)

/** The label over a role's names on the detail page: one, several. */
private val GROUP_LABELS: Map<String, Pair<String, String>> = mapOf(
    "actor" to ("Starring" to "Starring"),
    "creator" to ("Created by" to "Created by"),
    "director" to ("Director" to "Directors"),
    "writer" to ("Writer" to "Writers"),
    "producer" to ("Producer" to "Producers"),
    "composer" to ("Music" to "Music"),
    "cinematographer" to ("Cinematography" to "Cinematography"),
    "editor" to ("Editor" to "Editors"),
)

/** A person's role on a title, as a filmography card names it. */
private val ROLE_NAMES: Map<String, String> = mapOf(
    "actor" to "Actor",
    "creator" to "Creator",
    "director" to "Director",
    "writer" to "Writer",
    "producer" to "Producer",
    "composer" to "Composer",
    "cinematographer" to "Cinematographer",
    "editor" to "Editor",
)

/** One crew block of the detail page. */
data class CreditGroup(
    /** The role token ("director", "sound-designer"). */
    val role: String,
    /** What the detail page writes over the names ("Directors", "Music"). */
    val label: String,
    val people: List<CastMember>,
)

data class GroupedCredits(
    /** The actors, in the order katalog-api sends them: billing order. */
    val actors: List<CastMember>,
    /** Every other role, known roles first in [KNOWN_ROLES] order, then the
     *  rest as they come. */
    val crew: List<CreditGroup>,
)

/** A credit's role as a lower-case token. A credit without one is an actor:
 *  catalogs from before roles were sent credited actors only. */
fun roleOf(credit: CastMember): String = credit.role.orEmpty().trim().lowercase().ifEmpty { "actor" }

/** An unknown role token as words: "sound-designer" → "Sound Designer". */
fun titleizeRole(role: String): String =
    role.split(Regex("[\\s_-]+"))
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }

/** The label over [count] names in [role]: "Director" for one, "Directors"
 *  for two. */
fun creditLabel(role: String, count: Int): String {
    val r = role.trim().lowercase()
    val label = GROUP_LABELS[r] ?: return titleizeRole(r)
    return if (count > 1) label.second else label.first
}

/**
 * Split a title's credits into its actors and its crew by role. The order
 * within a role is the order the credits arrive in (katalog-api sends billing
 * order); roles are listed known ones first. A person credited twice in one
 * role is listed once, with both characters — two links with one name in one
 * block would only read as a mistake. Credits without a name are dropped.
 */
fun groupCredits(cast: List<CastMember>): GroupedCredits {
    val byRole = LinkedHashMap<String, MutableList<CastMember>>()
    // person-in-role → index of their entry in byRole[role]
    val kept = HashMap<String, Int>()
    for (credit in cast) {
        val name = credit.name.trim()
        if (name.isEmpty()) continue
        val role = roleOf(credit)
        val key = role + "\u0000" + (credit.personId?.ifEmpty { null } ?: name)
        val people = byRole.getOrPut(role) { mutableListOf() }
        val earlierIndex = kept[key]
        if (earlierIndex != null) {
            val earlier = people[earlierIndex]
            val character = credit.character?.trim().orEmpty()
            val known = earlier.character.orEmpty()
            if (character.isNotEmpty() && character !in known.split(" / ")) {
                people[earlierIndex] = earlier.copy(
                    character = if (known.isEmpty()) character else "$known / $character",
                )
            }
            continue
        }
        kept[key] = people.size
        people += credit.copy(name = name, role = role)
    }

    fun rank(role: String): Int = KNOWN_ROLES.indexOf(role).let { if (it < 0) KNOWN_ROLES.size else it }

    val crew = byRole.keys
        .filter { it != "actor" }
        // A stable sort: roles chino has no name for keep the order they came in.
        .sortedBy(::rank)
        .map { role ->
            val people = byRole.getValue(role)
            CreditGroup(role = role, label = creditLabel(role, people.size), people = people)
        }
    return GroupedCredits(actors = byRole["actor"].orEmpty(), crew = crew)
}

/** A person's role on one title, for their filmography: "Director", "Composer". */
fun roleName(role: String): String {
    val r = role.trim().lowercase()
    return ROLE_NAMES[r] ?: titleizeRole(r)
}

/** A person's roles on a title, each once, in the order given: "Director · Writer". */
fun formatRoles(roles: List<String>): String {
    val names = mutableListOf<String>()
    for (role in roles) {
        val name = roleName(role)
        if (name.isNotEmpty() && name !in names) names += name
    }
    return names.joinToString(" · ")
}
