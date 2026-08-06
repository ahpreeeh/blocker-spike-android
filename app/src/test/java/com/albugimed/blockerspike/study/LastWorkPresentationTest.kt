package com.albugimed.blockerspike.study

import com.albugimed.blockerspike.sync.LastWork
import com.albugimed.blockerspike.sync.LastWorkUnit
import com.albugimed.blockerspike.sync.ResourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant

class LastWorkPresentationTest {

    @Test
    fun lesUnitesConnuesOntUnLibelleHumain() {
        val labels = mapOf(
            LastWorkUnit.Pages(47, 62) to "pages 47 → 62",
            LastWorkUnit.Pages(67, 67) to "page 67",
            LastWorkUnit.Chapter to "chapitre entier",
            LastWorkUnit.Annale("ECN 2019") to "annale « ECN 2019 »",
            LastWorkUnit.Cards(80) to "80 cartes",
            LastWorkUnit.Cards(1) to "1 carte",
            LastWorkUnit.Free("schémas") to "schémas",
        )

        labels.forEach { (unit, expected) ->
            assertEquals(expected, unit.displayLabel())
        }
    }

    @Test
    fun leDernierTravailAfficheUnAgeRelatif() {
        val lastWork = LastWork(
            occurredAt = "2026-07-28T13:00:00Z",
            unit = LastWorkUnit.Pages(47, 62),
            stepId = "stp_1",
            resourceId = null,
        )

        assertEquals(
            "pages 47 → 62, il y a 3 j",
            lastWorkDisplayLabel(lastWork, now = Instant.parse("2026-07-31T13:00:00Z")),
        )
    }

    @Test
    fun laMemeRessourceEstIdentifieeParSonLibelle() {
        val label = lastWorkDisplayLabel(
            lastWork = lastWork(resourceId = "res_1"),
            currentResource = resource("res_1", "Collège de cardiologie"),
            now = Instant.parse("2026-07-31T13:00:00Z"),
        )

        assertEquals(
            "pages 47 → 62, Collège de cardiologie, il y a 3 j",
            label,
        )
    }

    @Test
    fun uneRessourceDifferenteEstDistingueeAvecSonIdentifiant() {
        val label = lastWorkDisplayLabel(
            lastWork = lastWork(resourceId = "res_autre"),
            currentResource = resource("res_courante", "Collège de cardiologie"),
            now = Instant.parse("2026-07-31T13:00:00Z"),
        )

        assertEquals(
            "pages 47 → 62, autre ressource (res_autre), il y a 3 j",
            label,
        )
        assertFalse(label.contains("Collège de cardiologie"))
    }

    @Test
    fun uneTraceSansRessourceNAjouteAucuneIdentite() {
        val label = lastWorkDisplayLabel(
            lastWork = lastWork(resourceId = null),
            currentResource = resource("res_courante", "Collège de cardiologie"),
            now = Instant.parse("2026-07-31T13:00:00Z"),
        )

        assertEquals("pages 47 → 62, il y a 3 j", label)
    }

    @Test
    fun uneTraceAvecRessourceSansRessourceCouranteGardeLIdentifiant() {
        val label = lastWorkDisplayLabel(
            lastWork = lastWork(resourceId = "res_1"),
            currentResource = null,
            now = Instant.parse("2026-07-31T13:00:00Z"),
        )

        assertEquals("pages 47 → 62, ressource res_1, il y a 3 j", label)
    }

    @Test
    fun uneDateInvalideNEffacePasLUnite() {
        val lastWork = LastWork(
            occurredAt = "date inconnue",
            unit = LastWorkUnit.Chapter,
            stepId = null,
            resourceId = null,
        )

        assertEquals("chapitre entier", lastWorkDisplayLabel(lastWork))
    }

    @Test
    fun uneUniteFutureResteVisibleSousFormeBruteCompacte() {
        val raw = """{"type":"future","label":"simulation"}"""
        val label = LastWorkUnit.Unknown(raw).displayLabel()

        assertEquals(raw, label)
    }

    @Test
    fun unLibelleLibreEstCompacteEtBorne() {
        val unsafe = "  ligne 1\n\tligne 2 " + "x".repeat(200)
        val label = LastWorkUnit.Free(unsafe).displayLabel()

        assertFalse(label.contains('\n'))
        assertFalse(label.contains('\t'))
        assertEquals(120, label.length)
    }

    private fun lastWork(resourceId: String?) = LastWork(
        occurredAt = "2026-07-28T13:00:00Z",
        unit = LastWorkUnit.Pages(47, 62),
        stepId = "stp_1",
        resourceId = resourceId,
    )

    private fun resource(resourceId: String, label: String) = ResourceRef(
        resourceId = resourceId,
        label = label,
        type = "pdf_drive",
        openUri = null,
    )
}
