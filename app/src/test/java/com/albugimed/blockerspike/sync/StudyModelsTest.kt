package com.albugimed.blockerspike.sync

import java.time.ZoneId
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyModelsTest {

    /** La forme exigée par le serveur : `evt_` + ULID canonique (contrat §3). */
    private val forme = Regex("^evt_[0-9A-HJKMNP-TV-Z]{26}$")

    @Test
    fun lIdentifiantSuitLaFormeAttendueParLeServeur() {
        repeat(200) {
            assertTrue(forme.matches(newEventId(System.currentTimeMillis())))
        }
    }

    @Test
    fun deuxSaisiesDansLaMemeMillisecondeNeSeConfondentPas() {
        val ids = (1..1_000).map { newEventId(nowMillis = 1_753_968_120_000L) }.toSet()
        assertEquals(1_000, ids.size)
    }

    @Test
    fun lIdentifiantEstReproductibleAAleaFixe() {
        // Un `event_id` frappé à la saisie doit rester le même à chaque
        // tentative d'envoi : c'est toute la mécanique d'idempotence.
        val premier = newEventId(1_753_968_120_000L, Random(42))
        val second = newEventId(1_753_968_120_000L, Random(42))
        assertEquals(premier, second)
    }

    @Test
    fun lHorodatageConserveLeDecalageVecu() {
        val instant = 1_753_969_320_000L // 2025-07-31T13:42:00Z

        assertTrue(
            formatOccurredAt(instant, ZoneId.of("Europe/Paris")).endsWith("+02:00")
        )
        assertEquals(
            "2025-07-31T13:42:00Z",
            formatOccurredAt(instant, ZoneId.of("UTC")),
        )
    }

    @Test
    fun lHorodatageEstToujoursDecale() {
        // Sans décalage, le serveur refuse l'événement (contrat §6). Aucune
        // zone ne doit produire une chaîne nue.
        listOf("UTC", "Europe/Paris", "America/New_York", "Asia/Kolkata").forEach { zone ->
            val formatted = formatOccurredAt(1_753_969_320_000L, ZoneId.of(zone))
            assertTrue(
                "$zone a produit $formatted",
                formatted.endsWith("Z") || formatted.matches(Regex(".*[+-]\\d{2}:\\d{2}$")),
            )
        }
    }
}
