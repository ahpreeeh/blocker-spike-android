package com.albugimed.blockerspike.study

import com.albugimed.blockerspike.sync.AgendaEntry
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La semaine du téléphone : sept jours, et ce qu'ils portent. */
class AgendaWeekPresentationTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")

    /** Samedi 8 août 2026, 12 h à Paris. Sa semaine court du 3 au 9. */
    private val now = OffsetDateTime.parse("2026-08-08T12:00:00+02:00").toInstant().toEpochMilli()

    private fun timed(
        id: String,
        label: String,
        start: String,
        end: String?,
        pending: Boolean = false,
        deleted: Boolean = false,
    ) = AgendaEntry(
        id = id,
        label = label,
        kind = "cours",
        allDay = false,
        startsAt = OffsetDateTime.parse(start),
        endsAt = end?.let(OffsetDateTime::parse),
        startDate = null,
        endDate = null,
        isLock = false,
        location = null,
        editedAt = OffsetDateTime.parse("2026-08-08T10:00:00+02:00"),
        deleted = deleted,
        pending = pending,
    )

    private fun allDay(id: String, label: String, from: String, to: String?) = AgendaEntry(
        id = id,
        label = label,
        kind = "stage",
        allDay = true,
        startsAt = null,
        endsAt = null,
        startDate = LocalDate.parse(from),
        endDate = to?.let(LocalDate::parse),
        isLock = false,
        location = null,
        editedAt = OffsetDateTime.parse("2026-08-01T10:00:00+02:00"),
    )

    private fun week(vararg entries: AgendaEntry, offset: Int = 0) =
        buildAgendaWeekPresentation(AgendaEntriesState(entries = entries.toList()), now, offset, zone)

    private fun dayOf(week: AgendaWeekPresentation, date: String) =
        week.days.single { it.date == LocalDate.parse(date) }

    @Test
    fun laSemaineVaDuLundiAuDimancheEtGardeSesJoursVides() {
        val week = week(
            timed("tmp_a", "ED pneumo", "2026-08-06T08:00:00+02:00", "2026-08-06T10:00:00+02:00"),
        )

        assertEquals(7, week.days.size)
        assertEquals(LocalDate.parse("2026-08-03"), week.days.first().date)
        assertEquals(LocalDate.parse("2026-08-09"), week.days.last().date)
        // Le jour vide reste à sa place : c'est là que se voient les trous.
        assertTrue(dayOf(week, "2026-08-05").entries.isEmpty())
        assertEquals(listOf("ED pneumo"), dayOf(week, "2026-08-06").entries.map { it.label })
        assertEquals("08:00 → 10:00", dayOf(week, "2026-08-06").entries.single().timeLabel)
    }

    @Test
    fun aujourdHuiEstMarqueSansEtreDeplace() {
        val week = week()
        assertEquals(
            listOf(LocalDate.parse("2026-08-08")),
            week.days.filter { it.isToday }.map { it.date },
        )
        assertTrue(week.isCurrentWeek)
        assertEquals("3 – 9 août 2026", week.title)
    }

    @Test
    fun uneGardeDeNuitSeLitDepuisChacunDeSesDeuxJours() {
        val week = week(
            timed("tmp_g", "Garde", "2026-08-07T20:00:00+02:00", "2026-08-08T08:00:00+02:00"),
        )

        // La même garde, vue du soir puis du matin. La répéter en entier ferait
        // croire à deux gardes.
        assertEquals("dès 20:00", dayOf(week, "2026-08-07").entries.single().timeLabel)
        assertEquals("jusqu'à 08:00", dayOf(week, "2026-08-08").entries.single().timeLabel)
        assertEquals("tmp_g", dayOf(week, "2026-08-08").entries.single().id)
    }

    @Test
    fun unJourTraverseDeBoutEnBoutNAffichePasDHeure() {
        val week = week(
            timed("tmp_c", "Congrès", "2026-08-04T18:00:00+02:00", "2026-08-06T09:00:00+02:00"),
        )

        assertEquals("dès 18:00", dayOf(week, "2026-08-04").entries.single().timeLabel)
        assertEquals("Toute la journée", dayOf(week, "2026-08-05").entries.single().timeLabel)
        assertEquals("jusqu'à 09:00", dayOf(week, "2026-08-06").entries.single().timeLabel)
        assertTrue(dayOf(week, "2026-08-07").entries.isEmpty())
    }

    @Test
    fun uneFinAMinuitPileNeDebordePasSurLeLendemain() {
        val week = week(
            timed("tmp_s", "Soirée", "2026-08-05T22:00:00+02:00", "2026-08-06T00:00:00+02:00"),
        )

        assertEquals("22:00 → 00:00", dayOf(week, "2026-08-05").entries.single().timeLabel)
        // Minuit pile ferme la veille : sinon le 6 porterait un créneau de
        // durée nulle, sur un jour où il n'y a rien.
        assertTrue(dayOf(week, "2026-08-06").entries.isEmpty())
    }

    @Test
    fun uneJourneeEntiereOccupeChacunDeSesJoursBornesComprises() {
        val week = week(allDay("tmp_st", "Stage de pneumo", "2026-08-03", "2026-08-05"))

        assertEquals("Toute la journée", dayOf(week, "2026-08-03").entries.single().timeLabel)
        // `end_date` est inclusive : le 5 est encore le stage.
        assertEquals("Toute la journée", dayOf(week, "2026-08-05").entries.single().timeLabel)
        assertTrue(dayOf(week, "2026-08-06").entries.isEmpty())
    }

    @Test
    fun lHorlogeDonneLOrdreEtLaJourneeEntierePasseDevant() {
        val week = week(
            timed("tmp_2", "Après-midi", "2026-08-04T14:00:00+02:00", "2026-08-04T16:00:00+02:00"),
            timed("tmp_1", "Matin", "2026-08-04T08:00:00+02:00", "2026-08-04T10:00:00+02:00"),
            allDay("tmp_0", "Stage", "2026-08-04", null),
        )

        assertEquals(
            listOf("Stage", "Matin", "Après-midi"),
            dayOf(week, "2026-08-04").entries.map { it.label },
        )
    }

    @Test
    fun leFeuilletageChangeDeSemaineSansPerdreAujourdHui() {
        val entries = arrayOf(
            timed("tmp_p", "Semaine d'avant", "2026-07-28T08:00:00+02:00", "2026-07-28T10:00:00+02:00"),
        )

        val previous = week(*entries, offset = -1)
        assertEquals("27 juillet – 2 août 2026", previous.title)
        assertFalse(previous.isCurrentWeek)
        assertEquals(listOf("Semaine d'avant"), dayOf(previous, "2026-07-28").entries.map { it.label })
        // Aucun jour de la semaine précédente n'est aujourd'hui.
        assertTrue(previous.days.none { it.isToday })

        assertEquals("10 – 16 août 2026", week(offset = 1).title)
    }

    @Test
    fun uneEntreeRetireeNOccupePlusDeTemps() {
        val week = week(
            timed(
                "tmp_a",
                "Annulé",
                "2026-08-06T08:00:00+02:00",
                "2026-08-06T10:00:00+02:00",
                deleted = true,
            ),
        )
        assertTrue(week.days.all { it.entries.isEmpty() })
    }

    @Test
    fun uneSaisieNonEnvoyeeSAfficheQuandMeme() {
        val state = AgendaEntriesState(
            entries = listOf(
                timed(
                    "tmp_a",
                    "Saisi dans le métro",
                    "2026-08-06T08:00:00+02:00",
                    "2026-08-06T10:00:00+02:00",
                    pending = true,
                ),
            ),
        )
        val week = buildAgendaWeekPresentation(state, now, 0, zone)
        // Elle est acquise sur l'appareil : la cacher jusqu'à l'envoi ferait
        // croire à une perte là où il n'y a qu'une attente.
        assertTrue(dayOf(week, "2026-08-06").entries.single().pending)
        assertEquals(1, state.pendingCount)
    }

    @Test
    fun unMagasinIllisibleSeDitAuLieuDAfficherUneSemaineVide() {
        val week = buildAgendaWeekPresentation(
            AgendaEntriesState(storageHealthy = false, unreadable = 2),
            now,
            0,
            zone,
        )
        assertEquals(
            listOf("Agenda local illisible.", "2 entrée(s) locale(s) illisible(s)."),
            week.warnings,
        )
        // La semaine reste affichable : sept jours vides valent mieux qu'un écran
        // blanc, l'avertissement dit pourquoi.
        assertEquals(7, week.days.size)
    }
}
