package com.albugimed.blockerspike.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ce que la bibliotheque doit tenir sans Android.
 *
 * Tout ce qui est verifie ici est du calcul pur sur des listes immuables : le
 * rangement, le chemin, et la somme des pages. C'est deliberement la que vit la
 * logique — un ecran Compose ne se teste pas a ce prix-la, et ces regles-ci
 * sont exactement celles qui se cassent en silence.
 */
class LibraryTest {

    private fun position(
        resourceId: String,
        page: Int,
        pageCount: Int,
        updatedAtMillis: Long = 1_786_000_000_000,
    ) = ReadingPosition(
        resourceId = resourceId,
        documentUri = "content://exemple/document/$resourceId",
        documentLabel = "$resourceId.pdf",
        page = page,
        pageCount = pageCount,
        updatedAtMillis = updatedAtMillis,
    )

    /** Medecine › DFGSM2 › Cardiologie, avec un livre en deux tomes au fond. */
    private val medecine = LibraryFolder(id = "f_med", name = "Médecine")
    private val annee = LibraryFolder(id = "f_dfgsm2", name = "DFGSM2", parentId = "f_med")
    private val cardio = LibraryFolder(id = "f_cardio", name = "Cardiologie", parentId = "f_dfgsm2")
    private val college = LibraryBook(
        id = "b_college",
        title = "Collège de cardiologie",
        folderId = "f_cardio",
        files = listOf(
            LibraryBookFile("lib_t1", "Tome 1"),
            LibraryBookFile("lib_t2", "Tome 2"),
        ),
    )
    private val library = Library(
        folders = listOf(medecine, annee, cardio),
        books = listOf(college),
    )

    @Test
    fun `le fil d Ariane remonte de la racine jusqu au dossier`() {
        assertEquals(listOf(medecine, annee, cardio), library.path("f_cardio"))
    }

    @Test
    fun `un cycle de dossiers ne fige pas le fil d Ariane`() {
        // Un magasin abime par une version anterieure ne doit pas boucler
        // indefiniment : un chemin incomplet vaut mieux qu'un ecran fige.
        val boucle = Library(
            folders = listOf(
                LibraryFolder(id = "a", name = "A", parentId = "b"),
                LibraryFolder(id = "b", name = "B", parentId = "a"),
            ),
        )
        assertEquals(2, boucle.path("a").size)
    }

    @Test
    fun `un dossier ne peut pas etre deplace dans lui meme ni dans ses enfants`() {
        val destinations = library.destinationsFor("f_med").map { it.id }
        assertFalse(destinations.contains("f_med"))
        assertFalse(destinations.contains("f_dfgsm2"))
        assertFalse(destinations.contains("f_cardio"))
    }

    @Test
    fun `deplacer un livre garde toutes les destinations ouvertes`() {
        // Un livre n'a pas de descendance : rien ne peut se detacher de la
        // racine en le rangeant.
        assertEquals(3, library.destinationsFor(null).size)
    }

    @Test
    fun `un dossier compte les livres de ses sous dossiers`() {
        assertEquals(1, bookCount(library, "f_med"))
        assertEquals(1, bookCount(library, "f_cardio"))
    }

    @Test
    fun `un livre en deux tomes vaut la somme de ses tomes, pas la moyenne`() {
        // Tome 1 : 100 pages sur 100. Tome 2 : 0 lu sur 300.
        // La moyenne des taux dirait 50 % ; la verite est 25 %.
        val positions = mapOf(
            "lib_t1" to position("lib_t1", page = 100, pageCount = 100),
            "lib_t2" to position("lib_t2", page = 1, pageCount = 300),
        )
        assertEquals(25, bookProgress(college, positions).percent)
    }

    @Test
    fun `un livre jamais ouvert n affiche pas zero pour cent`() {
        // Cadrage §1.2 : absence de trace n'est pas absence de travail.
        // `null` se rend « — », « 0 % » serait une affirmation.
        assertNull(bookProgress(college, emptyMap()).percent)
        assertNull(fileProgress(null).percent)
    }

    @Test
    fun `le dossier racine additionne toute la bibliotheque`() {
        val ailleurs = LibraryBook(
            id = "b_autre",
            title = "Anatomie",
            folderId = null,
            files = listOf(LibraryBookFile("lib_ana")),
        )
        val complete = library.copy(books = library.books + ailleurs)
        val positions = mapOf(
            "lib_t1" to position("lib_t1", page = 50, pageCount = 100),
            "lib_ana" to position("lib_ana", page = 100, pageCount = 100),
        )
        // La racine voit les deux livres ; le dossier Cardiologie n'en voit qu'un.
        assertEquals(75, folderProgress(complete, null, positions).percent)
        assertEquals(50, folderProgress(complete, "f_cardio", positions).percent)
    }

    @Test
    fun `la pastille de page suit le dernier tome touche`() {
        val positions = mapOf(
            "lib_t1" to position("lib_t1", page = 12, pageCount = 100, updatedAtMillis = 1_000),
            "lib_t2" to position("lib_t2", page = 200, pageCount = 300, updatedAtMillis = 2_000),
        )
        assertEquals("lib_t2", currentFileOf(college, positions)?.resourceId)
    }

    @Test
    fun `le titre propose sort du nom du fichier`() {
        assertEquals(
            "college cardio vol1",
            bookTitleFromFileName("college_cardio-vol1.pdf"),
        )
        // Un nom qui ne laisse rien d'exploitable ne doit pas donner un titre vide.
        assertEquals("Document", bookTitleFromFileName("   "))
    }

    @Test
    fun `les niveaux sont ranges par ordre alphabetique`() {
        val desordre = Library(
            folders = listOf(
                LibraryFolder(id = "z", name = "Zoologie"),
                LibraryFolder(id = "a", name = "anatomie"),
            ),
        )
        assertEquals(listOf("a", "z"), desordre.childFolders(null).map { it.id })
    }

    @Test
    fun `un livre range se retrouve par son fichier`() {
        assertEquals("b_college", library.bookOwning("lib_t2")?.id)
        assertNull(library.bookOwning("lib_inconnu"))
        assertTrue(library.knownResourceIds().containsAll(listOf("lib_t1", "lib_t2")))
    }

    @Test
    fun `un livre relu tel qu il a ete ecrit garde ses tomes`() {
        val relu = LibraryJson.decodeBook(LibraryJson.encodeBook(college))
        assertEquals(college, relu)
    }

    @Test
    fun `un dossier relu tel qu il a ete ecrit garde son parent`() {
        assertEquals(cardio, LibraryJson.decodeFolder(LibraryJson.encodeFolder(cardio)))
        assertEquals(medecine, LibraryJson.decodeFolder(LibraryJson.encodeFolder(medecine)))
    }

    @Test
    fun `une entree illisible est ignoree, elle ne fait pas tomber la bibliotheque`() {
        assertNull(LibraryJson.decodeBook("ceci n'est pas du json"))
        assertNull(LibraryJson.decodeFolder("{}"))
    }
}
