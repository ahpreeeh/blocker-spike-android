package com.albugimed.blockerspike.study

import com.albugimed.blockerspike.sync.AgendaItem
import com.albugimed.blockerspike.sync.AgendaSnapshot
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaPresentationTest {
    @Test
    fun enteteMetVerrouAvantFenetreSansInventerDeZero() {
        val presentation = buildAgendaHeaderPresentation(
            state = AgendaState(
                cachedAtMillis = null,
                snapshot = AgendaSnapshot(
                    generatedAt = "2026-08-06T05:12:00Z",
                    timezone = "Europe/Paris",
                    nextLock = item("lock", "Partiel", "autre"),
                    window48h = emptyList(),
                ),
            ),
            deviceZone = ZoneId.of("UTC"),
            locale = Locale.FRANCE,
        )

        assertEquals("Partiel", presentation.nextLock?.label)
        assertTrue(presentation.window48h.isEmpty())
        assertEquals("Agenda à jour du —", presentation.freshnessLabel)
    }

    @Test
    fun kindInconnuResteBrut() {
        assertEquals("simulation_future", "simulation_future".displayAgendaKindLabel())
        assertEquals("Rendez-vous", "rdv".displayAgendaKindLabel())
    }

    @Test
    fun instantEstRenduDansLeFuseauUtilisateurPasCeluiDuTelephone() {
        val presentation = buildAgendaHeaderPresentation(
            state = AgendaState(
                snapshot = AgendaSnapshot(
                    generatedAt = "x",
                    timezone = "Europe/Paris",
                    nextLock = item(
                        id = "lock",
                        label = "Cours tardif",
                        kind = "cours",
                        start = "2026-08-06T20:00:00Z",
                    ),
                    window48h = emptyList(),
                ),
            ),
            deviceZone = ZoneId.of("America/New_York"),
            locale = Locale.FRANCE,
        )

        assertEquals("06/08/2026 22:00", presentation.nextLock?.timeLabel)
    }

    @Test
    fun journeeEntiereNeSaffichePasCommeMinuitAMinuit() {
        val allDay = AgendaItem(
            id = "dst",
            label = "Stage",
            kind = "stage",
            startsAt = OffsetDateTime.parse("2026-03-29T00:00:00+01:00"),
            endsAt = OffsetDateTime.parse("2026-03-30T00:00:00+02:00"),
            allDay = true,
        )

        assertEquals(
            "29/03/2026",
            allDay.agendaTimeLabel(ZoneId.of("Europe/Paris"), Locale.FRANCE),
        )
    }

    @Test
    fun cacheEtJsonMalformesRestentVisibles() {
        val presentation = buildAgendaHeaderPresentation(
            AgendaState(
                snapshot = AgendaSnapshot.EMPTY.copy(
                    skippedWindowItems = 2,
                    malformedFields = 1,
                ),
                storageHealthy = false,
            ),
        )

        assertEquals(3, presentation.warnings.size)
        assertTrue(presentation.warnings.any { it.contains("Copie locale") })
        assertNull(presentation.nextLock)
    }

    private fun item(
        id: String,
        label: String,
        kind: String,
        start: String = "2026-09-15T08:00:00+02:00",
    ) = AgendaItem(
        id = id,
        label = label,
        kind = kind,
        startsAt = OffsetDateTime.parse(start),
        endsAt = null,
        allDay = false,
    )
}
