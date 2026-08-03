package com.albugimed.blockerspike.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le format d'un événement.
 *
 * Ce même encodage sert au stockage hors ligne **et** à l'envoi. Un
 * aller-retour qui perd un champ perdrait donc une trace pour de bon, sans
 * bruit, entre le moment où l'utilisateur la déclare et celui où le réseau
 * revient.
 */
class StudyEventJsonTest {

    private val complet = StudyEvent(
        eventId = "evt_01JZR4A9M2XK7QRSTVWXYZ0123",
        type = StudyEventType.ACTIVITY_RECORDED,
        occurredAt = "2026-07-31T15:42:00+02:00",
        nodeId = "nod_01JZQ8C7YB3TQRSTVWXYZ0123",
        stepId = "stp_01JZQK3M8F2WQRSTVWXYZ0123",
        resourceId = "res_01JZQK3M8F2WQRSTVWXYZ0124",
        durationMinutes = 35,
        unit = ActivityUnit.Pages(from = 47, to = 62),
        difficulty = Difficulty.HARD,
        note = "revoir les critères de Framingham",
    )

    @Test
    fun allerRetourSansPerte() {
        assertEquals(complet, StudyEventJson.decode(StudyEventJson.encodeToString(complet)))
    }

    @Test
    fun allerRetourDeChaqueUnite() {
        val unites = listOf(
            ActivityUnit.Pages(1, 2),
            ActivityUnit.Chapter,
            ActivityUnit.Annale("ECN 2019"),
            ActivityUnit.Cards(40),
            ActivityUnit.Free("relecture"),
        )
        unites.forEach { unit ->
            val event = complet.copy(unit = unit)
            assertEquals(unit, StudyEventJson.decode(StudyEventJson.encodeToString(event))?.unit)
        }
    }

    @Test
    fun unEvenementMinimalSeRelitAussi() {
        val minimal = StudyEvent(
            eventId = "evt_01JZR4A9M2XK7QRSTVWXYZ0123",
            type = StudyEventType.STEP_COMPLETED,
            occurredAt = "2026-07-31T13:42:00Z",
            stepId = "stp_01JZQK3M8F2WQRSTVWXYZ0123",
        )
        val relu = StudyEventJson.decode(StudyEventJson.encodeToString(minimal))

        assertEquals(minimal, relu)
        assertNull(relu?.durationMinutes)
        assertNull(relu?.unit)
    }

    @Test
    fun leFormatEnvoyeSuitLeContrat() {
        val json = StudyEventJson.encode(complet)

        assertEquals("evt_01JZR4A9M2XK7QRSTVWXYZ0123", json.getString("event_id"))
        assertEquals("activity_recorded", json.getString("type"))
        assertEquals("2026-07-31T15:42:00+02:00", json.getString("occurred_at"))

        val payload = json.getJSONObject("payload")
        assertEquals(35, payload.getInt("duration_minutes"))
        assertEquals("hard", payload.getString("difficulty"))
        assertEquals("pages", payload.getJSONObject("unit").getString("type"))
        assertEquals(47, payload.getJSONObject("unit").getInt("from"))
    }

    @Test
    fun uneEntreeIllisibleSeSignaleAuLieuDeSInventer() {
        // L'appelant garde alors la chaîne d'origine : elle est comptée
        // comme illisible, jamais remplacée par un événement plausible.
        assertNull(StudyEventJson.decode("pas du json"))
        assertNull(StudyEventJson.decode("""{"type":"activity_recorded"}"""))
        assertNull(StudyEventJson.decode("""{"event_id":"evt_x","type":"inconnu","occurred_at":"2026-07-31T13:42:00Z"}"""))
    }

    @Test
    fun leLotPorteLIdentifiantDAppareilEtLOrdreRecu() {
        val batch = StudyEventJson.encodeBatch("poco-x7", listOf(complet, complet.copy(eventId = "evt_b")))
        val root = JSONObject(batch)

        assertEquals("poco-x7", root.getString("device_id"))
        assertEquals(2, root.getJSONArray("events").length())
        assertEquals("evt_b", root.getJSONArray("events").getJSONObject(1).getString("event_id"))
    }

    @Test
    fun lesVerdictsSeRelisentUnParEvenement() {
        val results = StudyEventJson.decodeResults(
            """{"results":[
                 {"event_id":"evt_a","status":"accepted"},
                 {"event_id":"evt_b","status":"duplicate"},
                 {"event_id":"evt_c","status":"rejected","reason":"occurred_at illisible"}
               ]}"""
        )

        assertNotNull(results)
        assertEquals(3, results!!.size)
        assertTrue(results[0].isSettled)
        // `duplicate` est un succès : c'est la preuve du rejeu idempotent.
        assertTrue(results[1].isSettled)
        assertTrue(results[2].isRejected)
        assertEquals("occurred_at illisible", results[2].reason)
    }

    @Test
    fun uneReponseIllisibleNEstPasUnRefus() {
        // `null` fait réessayer ; une liste vide ferait croire que rien n'a
        // été traité et pourrait déclencher un abandon.
        assertNull(StudyEventJson.decodeResults("pas du json"))
        assertNull(StudyEventJson.decodeResults("""{"autre":true}"""))
    }

    @Test
    fun uneTraceMorteConserveSonMotifEtSonEvenement() {
        val dead = DeadEvent(complet, "occurred_at illisible")
        val relu = StudyEventJson.decodeDead(StudyEventJson.encodeDead(dead))

        assertEquals(dead, relu)
    }
}
