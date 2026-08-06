package com.albugimed.blockerspike.reader

import com.albugimed.blockerspike.study.resumeButtonLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingPositionTest {

    private fun position(
        page: Int = 67,
        declaredThrough: Int? = null,
        pageCount: Int = 320,
    ) = ReadingPosition(
        resourceId = "res_college_cardio",
        documentUri = "content://exemple/document/42",
        documentLabel = "Collège de cardiologie.pdf",
        page = page,
        declaredThrough = declaredThrough,
        pageCount = pageCount,
        updatedAtMillis = 1_786_000_320_000,
    )

    @Test
    fun `la premiere seance propose depuis la page 1`() {
        assertEquals(1..67, suggestedPageRange(position()))
    }

    @Test
    fun `la seance suivante reprend apres ce qui est deja declare`() {
        // Déclarer « 47 → 62 » puis lire jusqu'à 67 doit proposer « 63 → 67 ».
        // Reproposer « 47 → 67 » recompterait quinze pages déjà comptées.
        assertEquals(63..67, suggestedPageRange(position(page = 67, declaredThrough = 62)))
    }

    @Test
    fun `rien n est propose quand on n a pas avance`() {
        // Un formulaire pré-rempli avec une plage vide vaut moins qu'un
        // formulaire vide : il donne l'impression d'un fait établi.
        assertNull(suggestedPageRange(position(page = 62, declaredThrough = 62)))
        assertNull(suggestedPageRange(position(page = 40, declaredThrough = 62)))
    }

    @Test
    fun `une page relue seule reste une plage d une page`() {
        assertEquals(63..63, suggestedPageRange(position(page = 63, declaredThrough = 62)))
    }

    @Test
    fun `une page nulle ou negative est refusee a la construction`() {
        // Mieux vaut échouer ici que rouvrir silencieusement au mauvais
        // endroit : `PdfRenderer` compte à partir de 0, l'affichage à partir
        // de 1, et c'est exactement là qu'un décalage se glisse.
        val erreur = runCatching { position(page = 0) }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, erreur?.javaClass)
    }

    @Test
    fun `le libelle de reprise nomme la page, pas seulement l action`() {
        assertEquals("Reprendre page 67 / 320", resumeButtonLabel(position()))
        // Document jamais ouvert entièrement : le total est inconnu, on ne
        // l'invente pas.
        assertEquals("Reprendre page 67", resumeButtonLabel(position(pageCount = 0)))
    }

    @Test
    fun `sans document rattache le bouton ne dit pas Reprendre`() {
        // Le bouton DOIT exister avant tout rattachement — sinon il n'y a
        // aucun moyen d'attacher un premier PDF, et le lecteur est
        // inatteignable. Défaut trouvé sur l'appareil, pas en test.
        assertEquals("Rattacher un PDF", resumeButtonLabel(null))
    }
}

class ReadingPositionJsonTest {

    @Test
    fun `un aller-retour conserve tout ce qui compte`() {
        val original = ReadingPosition(
            resourceId = "res_1",
            documentUri = "content://exemple/42",
            documentLabel = "Collège.pdf",
            page = 67,
            declaredThrough = 62,
            pageCount = 320,
            updatedAtMillis = 1_786_000_320_000,
        )

        val relu = ReadingPositionJson.decode(ReadingPositionJson.encode(original))
        assertEquals(original, relu)
    }

    @Test
    fun `une entree sans declaration prealable se relit sans en inventer une`() {
        val original = ReadingPosition(
            resourceId = "res_1",
            documentUri = "content://exemple/42",
            documentLabel = "Collège.pdf",
            page = 1,
            pageCount = 320,
        )
        assertNull(ReadingPositionJson.decode(ReadingPositionJson.encode(original))?.declaredThrough)
    }

    @Test
    fun `une entree abimee est ignoree, jamais reparee au juge`() {
        // Une page inventée renverrait au mauvais endroit sans le dire.
        assertNull(ReadingPositionJson.decode("pas du json"))
        assertNull(ReadingPositionJson.decode("""{"resource_id":"res_1","page":3}"""))
        assertNull(
            ReadingPositionJson.decode(
                """{"resource_id":"res_1","document_uri":"content://x","page":0}""",
            ),
        )
    }

    @Test
    fun `la position n a aucun encodeur de contrat`() {
        // Test d'intention : `ReadingPositionJson` n'expose que `encode` et
        // `decode` pour un fichier privé. Si quelqu'un ajoute un encodeur de
        // lot ici, c'est que la position s'apprête à partir sur le réseau —
        // ce que l'amendement V2.2 §3 interdit.
        val methodes = ReadingPositionJson::class.java.declaredMethods
            .map { it.name }
            .filterNot { it.startsWith("access$") }
            .toSet()
        assertEquals(setOf("encode", "decode"), methodes)
    }
}
