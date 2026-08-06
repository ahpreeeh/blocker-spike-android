package com.albugimed.blockerspike.capture

import kotlin.random.Random
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureModelsTest {

    @Test
    fun `l identifiant est prefixe et croissant dans le temps`() {
        val premier = newCaptureId(1_754_000_000_000, Random(1))
        val second = newCaptureId(1_754_000_001_000, Random(1))

        assertTrue(premier.startsWith("cap_"))
        assertEquals(30, premier.length)
        // Le préfixe temporel les ordonne : deux captures du même instant se
        // distinguent par leur partie aléatoire, jamais par le hasard seul.
        assertTrue(second > premier)
    }

    @Test
    fun `deux captures du meme instant ne portent pas le meme identifiant`() {
        val a = newCaptureId(1_754_000_000_000, Random(1))
        val b = newCaptureId(1_754_000_000_000, Random(2))
        assertNotEquals(a, b)
    }

    @Test
    fun `un partage contenant une adresse est un lien`() {
        assertEquals(CaptureKind.LINK, inferCaptureKind("Regarde https://exemple.test/ed"))
        assertEquals(CaptureKind.LINK, inferCaptureKind("HTTP://EXEMPLE.TEST/x"))
    }

    @Test
    fun `en cas de doute le type est text`() {
        // `text` est le type le moins engageant : il n'ouvre rien, il ne
        // promet rien. Se tromper vers `link` afficherait une adresse qui
        // n'existe pas.
        assertEquals(CaptureKind.TEXT, inferCaptureKind("ED de pneumo déplacé à jeudi"))
        assertEquals(CaptureKind.TEXT, inferCaptureKind("ftp://exemple.test"))
        assertEquals(CaptureKind.TEXT, inferCaptureKind("https://"))
    }

    @Test
    fun `un partage vide ne cree pas de capture`() {
        assertNull(captureFromShare(null, null, "com.exemple", 1L))
        assertNull(captureFromShare("   ", "  ", "com.exemple", 1L))
    }

    @Test
    fun `un sujet seul suffit a capturer`() {
        val capture = captureFromShare(null, "ED pneumo", "com.exemple", 1L)
        assertEquals("ED pneumo", capture?.text)
        assertEquals("ED pneumo", capture?.subject)
    }

    @Test
    fun `un texte demesure est tronque, jamais rejete`() {
        // Couper vaut mieux que perdre : un partage de 100 000 caractères est
        // probablement une erreur, mais ce n'est pas à cette couche de le
        // décider en jetant tout.
        val capture = captureFromShare("a".repeat(100_000), null, null, 1L)
        assertEquals(MAX_CAPTURE_TEXT_LENGTH, capture?.text?.length)
    }

    @Test
    fun `l instant capture porte son decalage horaire`() {
        // Les secondes nulles sont omises par `OffsetDateTime.toString()`,
        // exactement comme pour `formatOccurredAt` côté études — et le
        // serveur les traite comme facultatives (contrat §6). On aligne le
        // test sur ce que les deux chemins font vraiment, plutôt que de faire
        // diverger le format des captures de celui des traces.
        val rendu = formatCapturedAt(1_786_000_320_000, java.time.ZoneId.of("Europe/Paris"))
        assertEquals("2026-08-06T09:12+02:00", rendu)

        // Le même instant, vécu ailleurs, ne se raconte pas pareil — c'est
        // toute la raison pour laquelle le décalage voyage avec la capture.
        assertEquals(
            "2026-08-06T12:57+05:45",
            formatCapturedAt(1_786_000_320_000, java.time.ZoneId.of("Asia/Kathmandu")),
        )
    }
}

class CaptureJsonTest {

    private fun capture(overrides: Capture.() -> Capture = { this }) = Capture(
        captureId = "cap_01JZR4A9M2XK7QRSTVWXYZ0123",
        kind = CaptureKind.LINK,
        capturedAtMillis = 1_786_000_320_000,
        text = "ED pneumo https://exemple.test/ed",
        subject = "ED pneumo",
        sourcePackage = "com.exemple.messagerie",
    ).overrides()

    @Test
    fun `l enveloppe porte le device_id et le tableau des captures`() {
        val body = JSONObject(CaptureJson.encodeBatch("poco-x7", listOf(capture())))
        assertEquals("poco-x7", body.getString("device_id"))
        assertEquals(1, body.getJSONArray("captures").length())
    }

    @Test
    fun `la charge partagee est encodee dans raw, pas a la racine`() {
        val encoded = CaptureJson.encode(capture())
        val raw = encoded.getJSONObject("raw")

        assertEquals("ED pneumo https://exemple.test/ed", raw.getString("text"))
        assertEquals("ED pneumo", raw.getString("subject"))
        assertFalse(encoded.has("text"))
    }

    @Test
    fun `le nom du paquet d origine ne traverse jamais le reseau`() {
        // Contrat §8 : les noms de paquets ne circulent pas. Ce test est là
        // pour qu'un ajout bien intentionné dans `raw` casse quelque chose.
        val raw = CaptureJson.encode(capture()).getJSONObject("raw")
        assertFalse(raw.has("source_package"))
        assertEquals(setOf("text", "subject"), raw.keys().asSequence().toSet())
    }

    @Test
    fun `les champs facultatifs absents ne sont pas encodes a vide`() {
        // `subject: ""` côté serveur serait un titre vide affiché tel quel.
        val encoded = CaptureJson.encode(capture { copy(subject = null, sourcePackage = null) })
        val raw = encoded.getJSONObject("raw")
        assertFalse(raw.has("subject"))
    }

    @Test
    fun `les verdicts sont relus dans l ordre recu`() {
        val results = CaptureJson.decodeResults(
            """{"results":[
                 {"capture_id":"cap_A","status":"accepted"},
                 {"capture_id":"cap_B","status":"duplicate"},
                 {"capture_id":"cap_C","status":"rejected","reason":"raw vide"}
               ]}""",
        )

        assertEquals(3, results?.size)
        assertTrue(results!![0].isSettled)
        // `duplicate` est un succès : c'est la preuve que le rejeu marche.
        assertTrue(results[1].isSettled)
        assertTrue(results[2].isRejected)
        assertEquals("raw vide", results[2].reason)
    }

    @Test
    fun `une reponse illisible ne vaut pas un refus`() {
        // `null` fait garder la file entière et réessayer. Rendre une liste
        // vide ferait croire que tout est réglé, et perdrait les captures.
        assertNull(CaptureJson.decodeResults("pas du json"))
        assertNull(CaptureJson.decodeResults("""{"autre_chose":1}"""))
    }

    @Test
    fun `un verdict sans identifiant est ignore, les autres passent`() {
        val results = CaptureJson.decodeResults(
            """{"results":[{"status":"accepted"},{"capture_id":"cap_B","status":"accepted"}]}""",
        )
        assertEquals(1, results?.size)
        assertEquals("cap_B", results?.first()?.captureId)
    }
}
