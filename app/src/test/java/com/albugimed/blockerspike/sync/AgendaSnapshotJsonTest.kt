package com.albugimed.blockerspike.sync

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaSnapshotJsonTest {
    @Test
    fun decodeConserveOrdreNullEtKindInconnu() {
        val snapshot = AgendaSnapshotJson.decode(
            """
            {
              "generated_at":"2026-08-06T05:12:00Z",
              "timezone":"Europe/Paris",
              "next_lock":null,
              "window_48h":[
                {"id":"tmp_2","label":"Simulation","kind":"simulation_future",
                 "starts_at":"2026-08-06T22:00:00+02:00",
                 "ends_at":"2026-08-06T23:00:00+02:00","all_day":false},
                {"id":"tmp_1","label":"Garde","kind":"garde",
                 "starts_at":"2026-08-07T08:00:00+02:00",
                 "ends_at":"2026-08-07T20:00:00+02:00","all_day":false}
              ]
            }
            """.trimIndent(),
        )!!

        assertNull(snapshot.nextLock)
        assertEquals(listOf("tmp_2", "tmp_1"), snapshot.window48h.map { it.id })
        assertEquals("simulation_future", snapshot.window48h.first().kind)
        assertEquals(0, snapshot.skippedWindowItems)
        assertEquals(0, snapshot.malformedFields)
    }

    @Test
    fun entreeDeFenetreMalformeeEstCompteeEtLesAutresRestentVisibles() {
        val snapshot = AgendaSnapshotJson.decode(
            """
            {
              "generated_at":"2026-08-06T05:12:00Z",
              "timezone":"Europe/Paris",
              "next_lock":null,
              "window_48h":[
                {"id":"cassé"},
                {"id":"ok","label":"Cours","kind":"cours",
                 "starts_at":"2026-08-06T10:00:00+02:00",
                 "ends_at":"2026-08-06T12:00:00+02:00","all_day":false}
              ]
            }
            """.trimIndent(),
        )!!

        assertNull(snapshot.nextLock)
        assertEquals(listOf("ok"), snapshot.window48h.map { it.id })
        assertEquals(1, snapshot.skippedWindowItems)
        assertEquals(0, snapshot.malformedFields)
    }

    @Test
    fun champsRacineAbsentsOuMalformesRejettentToutLeSnapshot() {
        val invalidRoots = listOf(
            "{}",
            """{"generated_at":"illisible","timezone":"Europe/Paris","next_lock":null,"window_48h":[]}""",
            """{"generated_at":"2026-08-06T05:12:00Z","timezone":"Fuseau/inconnu","next_lock":null,"window_48h":[]}""",
            """{"generated_at":"2026-08-06T05:12:00Z","timezone":"Europe/Paris","window_48h":[]}""",
            """{"generated_at":"2026-08-06T05:12:00Z","timezone":"Europe/Paris","next_lock":{"id":"cassé"},"window_48h":[]}""",
            """{"generated_at":"2026-08-06T05:12:00Z","timezone":"Europe/Paris","next_lock":null,"window_48h":null}""",
        )

        invalidRoots.forEach { assertNull(AgendaSnapshotJson.decode(it)) }
        assertNull(AgendaSnapshotJson.decode("pas du json"))
    }

    @Test
    fun fenetreRefuseUneDureeNulleOuNegativeSansMasquerLesAutresEntrees() {
        val snapshot = AgendaSnapshotJson.decode(
            """
            {"generated_at":"2026-08-06T05:12:00Z","timezone":"Europe/Paris",
             "next_lock":null,"window_48h":[
               {"id":"zero","label":"Nulle","kind":"cours",
                "starts_at":"2026-08-06T10:00:00+02:00",
                "ends_at":"2026-08-06T10:00:00+02:00","all_day":false},
               {"id":"negative","label":"Inversée","kind":"cours",
                "starts_at":"2026-08-06T11:00:00+02:00",
                "ends_at":"2026-08-06T10:00:00+02:00","all_day":false},
               {"id":"ok","label":"Valide","kind":"cours",
                "starts_at":"2026-08-06T12:00:00+02:00",
                "ends_at":"2026-08-06T13:00:00+02:00","all_day":false}
             ]}
            """.trimIndent(),
        )!!

        assertEquals(listOf("ok"), snapshot.window48h.map { it.id })
        assertEquals(2, snapshot.skippedWindowItems)
    }

    @Test
    fun cacheCorrompuAbandonneSonEtagPourForcerUneReparation200() {
        val cached = decodeCachedAgenda(
            encoded = "pas du json",
            etag = "\"etag-qui-ne-doit-plus-servir\"",
            fetchedAtMillis = 123L,
        )

        assertNull(cached.etag)
        assertEquals(123L, cached.fetchedAtMillis)
        assertTrue(!cached.storageHealthy)
    }

    @Test
    fun cachePartielAbandonneAussiSonEtagPourPouvoirEtreReinterprete() {
        val partial = AgendaSnapshot(
            generatedAt = "2026-08-06T05:12:00Z",
            timezone = "Europe/Paris",
            nextLock = null,
            window48h = emptyList(),
            skippedWindowItems = 1,
        )

        val cached = decodeCachedAgenda(
            encoded = AgendaSnapshotJson.encode(partial),
            etag = "\"etag-partiel\"",
            fetchedAtMillis = 123L,
        )

        assertEquals(partial, cached.snapshot)
        assertNull(cached.etag)
        assertTrue(cached.storageHealthy)
    }

    @Test
    fun roundTripCacheConserveKindEtCompteurs() {
        val original = AgendaSnapshot(
            generatedAt = "2026-08-06T05:12:00Z",
            timezone = "Europe/Paris",
            nextLock = null,
            window48h = listOf(
                AgendaItem(
                    id = "tmp_1",
                    label = "Inconnu",
                    kind = "nouveau_kind",
                    startsAt = java.time.OffsetDateTime.parse("2026-08-06T08:00:00+02:00"),
                    endsAt = java.time.OffsetDateTime.parse("2026-08-06T09:00:00+02:00"),
                    allDay = false,
                ),
            ),
            skippedWindowItems = 2,
            malformedFields = 1,
        )

        assertEquals(original, AgendaSnapshotJson.decode(AgendaSnapshotJson.encode(original)))
    }

    @Test
    fun journeesEntieresGardentLesDureesReellesDesDeuxDst() {
        val spring = decodeAllDay(
            start = "2026-03-29T00:00:00+01:00",
            end = "2026-03-30T00:00:00+02:00",
        )
        val autumn = decodeAllDay(
            start = "2026-10-25T00:00:00+02:00",
            end = "2026-10-26T00:00:00+01:00",
        )

        assertEquals(23L, Duration.between(spring.startsAt, spring.endsAt!!).toHours())
        assertEquals(25L, Duration.between(autumn.startsAt, autumn.endsAt!!).toHours())
    }

    private fun decodeAllDay(start: String, end: String): AgendaItem =
        AgendaSnapshotJson.decode(
            """
            {"generated_at":"2026-01-01T00:00:00Z","timezone":"Europe/Paris",
             "next_lock":null,"window_48h":[
               {"id":"tmp_dst","label":"Journée","kind":"stage",
                "starts_at":"$start","ends_at":"$end","all_day":true}
             ]}
            """.trimIndent(),
        )!!.window48h.single()
}
