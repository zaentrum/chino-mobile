package cloud.nalet.chino.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** chino-web's lib/credits.test.ts, case for case, so both clients group,
 *  order and name a title's credits alike. */
class CreditsTest {
    private fun credit(name: String, role: String? = null, character: String? = null, order: Int? = null) =
        CastMember(name = name, role = role, personId = "id-$name", character = character, order = order)

    @Test
    fun theCrewIsGroupedByRoleKnownRolesInDisplayOrderOthersAfterThemAsTheyCame() {
        val (actors, crew) = groupCredits(
            listOf(
                credit("Esther Wouda", "writer"),
                credit("Halina Reijn", "actor", character = "Sintel (voice)", order = 0),
                credit("Jan Morgenstern", "composer"),
                credit("Rob Tuytel", "visual-effects"),
                credit("Colin Levy", "director"),
                credit("Thom Hoffman", "actor", character = "Shaman (voice)", order = 1),
                credit("Joram Letwory", "sound-designer"),
                credit("Ton Roosendaal", "producer"),
                credit("Josh Bernhard", "creator"),
            ),
        )

        assertEquals(listOf("Halina Reijn", "Thom Hoffman"), actors.map { it.name })
        assertEquals(listOf("Sintel (voice)", "Shaman (voice)"), actors.map { it.character })
        assertEquals(
            listOf(
                "creator" to "Created by",
                "director" to "Director",
                "writer" to "Writer",
                "producer" to "Producer",
                "composer" to "Music",
                "visual-effects" to "Visual Effects",
                "sound-designer" to "Sound Designer",
            ),
            crew.map { it.role to it.label },
        )
    }

    @Test
    fun theOrderWithinARoleIsTheOrderTheCreditsArriveIn() {
        // katalog-api sends billing order; the client keeps it.
        val leads = listOf("Alexandra Blatt", "Laura Graham", "James Rich", "Einar Gunn", "Jack Haley")
        val (actors) = groupCredits(leads.mapIndexed { i, n -> credit(n, "actor", order = i) })

        assertEquals(leads, actors.map { it.name })
    }

    @Test
    fun aCreditWithoutARoleIsAnActorAndRolesMatchCaseInsensitively() {
        val (actors, crew) = groupCredits(
            listOf(credit("A"), credit("B", ""), credit("C", " Actor "), credit("D", "DIRECTOR")),
        )

        assertEquals(listOf("A" to "actor", "B" to "actor", "C" to "actor"), actors.map { it.name to it.role })
        assertEquals(listOf("director" to listOf("D")), crew.map { g -> g.role to g.people.map { it.name } })
    }

    @Test
    fun aPersonCreditedTwiceInOneRoleIsListedOnceWithBothCharacters() {
        val (actors, crew) = groupCredits(
            listOf(
                credit("Ian Hubert", "director"),
                credit("Ian Hubert", "director"),
                credit("Ian Hubert", "writer"),
                credit("Derek de Lint", "actor", character = "Old Thom"),
                credit("Derek de Lint", "actor", character = "Narrator"),
                credit("Derek de Lint", "actor", character = "Old Thom"),
            ),
        )

        assertEquals(
            listOf("Director" to listOf("Ian Hubert"), "Writer" to listOf("Ian Hubert")),
            crew.map { g -> g.label to g.people.map { it.name } },
        )
        assertEquals(1, actors.size)
        assertEquals("Old Thom / Narrator", actors.single().character)
    }

    @Test
    fun withoutAPersonIdTheNameTellsCreditsApartAndNamelessCreditsAreDropped() {
        val (actors) = groupCredits(
            listOf(
                CastMember(name = "Same Name", role = "actor"),
                CastMember(name = "Same Name", role = "actor"),
                CastMember(name = "Other", role = "actor"),
                CastMember(name = "  ", role = "actor"),
            ),
        )

        assertEquals(listOf("Same Name", "Other"), actors.map { it.name })
    }

    @Test
    fun severalPeopleInARolePluraliseItsLabel() {
        val (_, crew) = groupCredits(
            listOf(credit("W1", "writer"), credit("W2", "writer"), credit("D1", "director")),
        )

        assertEquals(listOf("Director", "Writers"), crew.map { it.label })
        assertEquals(listOf("W1", "W2"), crew.last().people.map { it.name })
    }

    @Test
    fun theCastAsChinoApiSendsItGroupsIntoBlocks() {
        // The shape chino-api's katalog/client_test.go pins: twelve actors,
        // a creator, a director, a writer, an unknown role, and an unlinked
        // actor sent after the crew.
        val cast = ("ABCDEFGHIJKL".mapIndexed { i, c ->
            CastMember(name = "Actor $c", role = "actor", personId = "actor-$c", character = "Part $c", order = i)
        }) + listOf(
            CastMember(name = "Creator A", role = "creator", personId = "creator-a", episodeCount = 12),
            CastMember(name = "Director A", role = "director", personId = "director-a", job = "Director"),
            CastMember(name = "Writer B", role = "writer", personId = "writer-b", job = "Screenplay", order = 0),
            CastMember(name = "Stunt A", role = "stunts", personId = "stunt-a"),
            CastMember(name = "Unlinked A", role = "actor"),
        )

        val (actors, crew) = groupCredits(cast)

        assertEquals(13, actors.size)
        assertEquals("Unlinked A", actors.last().name)
        assertEquals(
            listOf("Created by", "Director", "Writer", "Stunts"),
            crew.map { it.label },
        )
        assertEquals("Screenplay", crew[2].people.single().job)
    }

    @Test
    fun theInputIsLeftAsItWas() {
        val cast = listOf(credit("X", "actor", character = "One"), credit("X", "actor", character = "Two"))

        groupCredits(cast)

        assertEquals("One", cast[0].character)
    }

    @Test
    fun noCreditsNoGroups() {
        val grouped = groupCredits(emptyList())

        assertTrue(grouped.actors.isEmpty())
        assertTrue(grouped.crew.isEmpty())
    }

    @Test
    fun labelsSingularForOnePluralForMoreTheFixedOnesFixed() {
        assertEquals("Director", creditLabel("director", 1))
        assertEquals("Directors", creditLabel("director", 2))
        assertEquals("Writers", creditLabel("writer", 3))
        assertEquals("Producer", creditLabel("producer", 1))
        assertEquals("Editors", creditLabel("editor", 2))
        assertEquals("Music", creditLabel("composer", 1))
        assertEquals("Music", creditLabel("composer", 2))
        assertEquals("Cinematography", creditLabel("cinematographer", 2))
        assertEquals("Created by", creditLabel("creator", 2))
        assertEquals("Starring", creditLabel("actor", 5))
        // An unknown role is titleized and not pluralised: it can't be done right blind.
        assertEquals("Casting", creditLabel("casting", 2))
    }

    @Test
    fun titleizeRoleSplitsOnHyphensUnderscoresAndSpaces() {
        assertEquals("Sound Designer", titleizeRole("sound-designer"))
        assertEquals("Visual Effects", titleizeRole("visual_effects"))
        assertEquals("Art Direction", titleizeRole("art  direction"))
        assertEquals("", titleizeRole(""))
    }

    @Test
    fun roleOfDefaultsToActor() {
        assertEquals("actor", roleOf(CastMember(name = "A")))
        assertEquals("composer", roleOf(CastMember(name = "A", role = "Composer")))
    }

    @Test
    fun aPersonsRolesOnATitleAreNamedOnceEachInTheOrderGiven() {
        assertEquals("Director", formatRoles(listOf("director")))
        assertEquals("Director · Writer", formatRoles(listOf("director", "writer")))
        assertEquals("Actor · Composer · Sound Designer", formatRoles(listOf("actor", "composer", "sound-designer")))
        assertEquals("Writer", formatRoles(listOf("writer", "WRITER")))
        assertEquals("", formatRoles(listOf("", " ")))
        assertEquals("", formatRoles(emptyList()))
        assertEquals("Cinematographer", roleName("cinematographer"))
        assertEquals("Creator", roleName("creator"))
    }
}
