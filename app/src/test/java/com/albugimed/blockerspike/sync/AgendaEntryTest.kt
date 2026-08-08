package com.albugimed.blockerspike.sync

import java.time.LocalDate
import java.time.OffsetDateTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ce que l'échange à deux sens de l'agenda doit tenir, prouvé sans réseau.
 *
 * Le cœur du sujet tient en une phrase : deux machines écrivent la même ligne,
 * et le verdict rendu ici doit être **exactement** celui que rend le serveur.
 * Une règle différente des deux côtés ferait osciller une entrée d'un balayage
 * à l'autre sans que rien ne l'explique.
 */
class AgendaEntryTest {

    private fun entry(
        id: String = "tmp_01JZZZZZZZZZZZZZZZZZZZZZZZ",
        editedAt: String = "2026-08-08T12:00:00+02:00",
        pending: Boolean = false,
        deleted: Boolean = false,
    ) = AgendaEntry(
        id = id,
        label = "Garde aux urgences",
        kind = "garde",
        allDay = false,
        startsAt = OffsetDateTime.parse("2026-08-09T08:00:00+02:00"),
        endsAt = OffsetDateTime.parse("2026-08-09T20:00:00+02:00"),
        startDate = null,
        endDate = null,
        isLock = true,
        location = "CHU",
        editedAt = OffsetDateTime.parse(editedAt),
        deleted = deleted,
        pending = pending,
    )

    @Test
    fun leServeurGagneQuandRienNAttendLocalement() {
        val local = entry(editedAt = "2026-08-08T18:00:00+02:00", pending = false)
        val incoming = entry(editedAt = "2026-08-08T09:00:00+02:00")
        // Sans modification locale en attente, la copie du serveur fait foi
        // même si elle est plus ancienne : c'est lui qui détient la vérité.
        assertEquals(incoming, mergeAgendaEntry(local, incoming))
    }

    @Test
    fun uneSaisieLocalePlusRecenteDefendSonRang() {
        val local = entry(editedAt = "2026-08-08T18:00:00+02:00", pending = true)
        val incoming = entry(editedAt = "2026-08-08T09:00:00+02:00")
        assertEquals(local, mergeAgendaEntry(local, incoming))
    }

    @Test
    fun uneSaisieLocaleDepasseeCedeLaPlace() {
        val local = entry(editedAt = "2026-08-08T09:00:00+02:00", pending = true)
        val incoming = entry(editedAt = "2026-08-08T18:00:00+02:00")
        val merged = mergeAgendaEntry(local, incoming)
        assertEquals(incoming, merged)
        // Elle cesse aussi d'attendre : le serveur a tranché, la renvoyer
        // donnerait éternellement la même réponse.
        assertFalse(merged.pending)
    }

    @Test
    fun aEgaliteLaVersionEnPlaceReste() {
        val local = entry(editedAt = "2026-08-08T12:00:00+02:00", pending = true)
        val incoming = entry(editedAt = "2026-08-08T12:00:00+02:00")
        assertEquals(local, mergeAgendaEntry(local, incoming))
    }

    @Test
    fun uneSuppressionNeTransportePasDeContenu() {
        val json = AgendaEntryJson.encodeChange(entry(deleted = true))
        assertTrue(json.getBoolean("deleted"))
        assertFalse(json.has("label"))
        assertFalse(json.has("starts_at"))
        // L'heure de modification, elle, est indispensable : c'est elle qui
        // permet à une correction plus récente de défaire le retrait.
        assertEquals("2026-08-08T12:00+02:00", json.getString("edited_at"))
    }

    @Test
    fun uneJourneeEntiereMonteAvecSesDatesEtSansHoraires() {
        val json = AgendaEntryJson.encodeChange(
            entry().copy(
                allDay = true,
                startsAt = null,
                endsAt = null,
                startDate = LocalDate.parse("2026-09-01"),
                endDate = LocalDate.parse("2026-09-05"),
            ),
        )
        assertEquals("2026-09-01", json.getString("start_date"))
        assertEquals("2026-09-05", json.getString("end_date"))
        assertFalse(json.has("starts_at"))
    }

    @Test
    fun uneEntreeRetireeDescendAvecSonContenu() {
        val decoded = AgendaEntryJson.decodeRemote(
            JSONObject(
                """
                {"id":"tmp_01JZZZZZZZZZZZZZZZZZZZZZZZ","deleted":true,
                 "label":"Garde","kind":"garde","all_day":false,
                 "starts_at":"2026-08-09T08:00:00+02:00",
                 "ends_at":"2026-08-09T20:00:00+02:00",
                 "edited_at":"2026-08-08T12:00:00+02:00"}
                """.trimIndent(),
            ),
        )
        // Elle doit rester montrable : une correction ultérieure venue du PC
        // peut défaire ce retrait, et l'entrée réapparaîtra telle quelle.
        assertEquals("Garde", decoded?.label)
        assertTrue(decoded?.deleted == true)
    }

    @Test
    fun uneEntreeVivanteSansDateEstJugeeIllisible() {
        val decoded = AgendaEntryJson.decodeRemote(
            JSONObject(
                """
                {"id":"tmp_01JZZZZZZZZZZZZZZZZZZZZZZZ","label":"Sans date",
                 "kind":"autre","all_day":false,
                 "edited_at":"2026-08-08T12:00:00+02:00"}
                """.trimIndent(),
            ),
        )
        assertNull(decoded)
    }

    @Test
    fun unBalayageCompteLesEntreesIllisiblesSansPerdreLesAutres() {
        val delta = decodeAgendaDelta(
            """
            {"server_time":"2026-08-08T12:00:00.000Z","timezone":"Europe/Paris",
             "cursor":"2026-08-08T11:59:00.000Z","has_more":false,
             "entries":[
               {"id":"tmp_01JZZZZZZZZZZZZZZZZZZZZZZZ","label":"Cours","kind":"cours",
                "all_day":false,"starts_at":"2026-08-09T08:00:00+02:00",
                "ends_at":"2026-08-09T10:00:00+02:00",
                "edited_at":"2026-08-08T12:00:00+02:00"},
               {"id":"","label":"","kind":""}
             ]}
            """.trimIndent(),
        )
        assertEquals(1, delta?.entries?.size)
        assertEquals(1, delta?.skipped)
        assertEquals("2026-08-08T11:59:00.000Z", delta?.cursor)
    }

    @Test
    fun uneReponseSansTableauNeFaitPasAvancerLeCurseur() {
        // Rendre un balayage vide ferait avancer le curseur sur une réponse
        // qu'on n'a pas comprise, et les entrées sautées ne redescendraient
        // jamais. Mieux vaut ne rien rendre du tout.
        assertNull(decodeAgendaDelta("""{"server_time":"2026-08-08T12:00:00.000Z"}"""))
    }

    @Test
    fun supersededEstUnSucces() {
        assertTrue(AgendaChangeResult("tmp_1", "superseded", null).isSettled)
        assertTrue(AgendaChangeResult("tmp_1", "accepted", null).isSettled)
        assertTrue(AgendaChangeResult("tmp_1", "rejected", "id malformé").isRejected)
    }

    @Test
    fun leMagasinLocalCompteCeQuIlNArrivePasARelire() {
        val state = decodeAgendaStore(
            """[{"id":"tmp_01JZZZZZZZZZZZZZZZZZZZZZZZ","label":"Cours","kind":"cours",
                 "all_day":false,"starts_at":"2026-08-09T08:00:00+02:00",
                 "edited_at":"2026-08-08T12:00:00+02:00","_pending":true},
                {"bidon":true}]""",
            cursor = "2026-08-08T11:59:00.000Z",
        )
        assertEquals(1, state.entries.size)
        assertEquals(1, state.unreadable)
        assertTrue(state.storageHealthy)
        assertEquals(1, state.pending.size)
    }

    @Test
    fun unMagasinIllisibleSeDeclareTelQuel() {
        val state = decodeAgendaStore("ceci n'est pas du JSON", cursor = null)
        assertFalse(state.storageHealthy)
        assertTrue(state.entries.isEmpty())
    }
}
