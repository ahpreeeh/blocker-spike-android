package com.albugimed.blockerspike.sync

import com.albugimed.blockerspike.capture.Capture
import com.albugimed.blockerspike.capture.CaptureDelivery

import java.time.OffsetDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaSyncTest {
    private class MemoryAgendaCache(initial: CachedAgenda = CachedAgenda()) : AgendaCache {
        var value = initial
        var stores = 0
        var touches = 0
        var clears = 0

        override suspend fun current(): CachedAgenda = value

        override suspend fun store(snapshot: AgendaSnapshot, etag: String?, atMillis: Long) {
            stores += 1
            value = CachedAgenda(snapshot, etag, atMillis)
        }

        override suspend fun touch(atMillis: Long) {
            touches += 1
            value = value.copy(fetchedAtMillis = atMillis)
        }

        override suspend fun clear() {
            clears += 1
            value = CachedAgenda()
        }
    }

    private class MemoryQueueCache : QueueCache {
        var value = CachedQueue()
        override suspend fun current(): CachedQueue = value
        override suspend fun store(snapshot: QueueSnapshot, etag: String?, atMillis: Long) {
            value = CachedQueue(snapshot, etag, atMillis)
        }
        override suspend fun touch(atMillis: Long) {
            value = value.copy(fetchedAtMillis = atMillis)
        }
        override suspend fun clear() { value = CachedQueue() }
    }

    private class EmptyOutbox : StudyOutbox {
        override suspend fun current(): OutboxState = OutboxState()
        override suspend fun forget(eventIds: Set<String>): Boolean = true
        override suspend fun bury(rejected: List<DeadEvent>): Boolean = true
    }

    private class Credentials : DeviceCredentialStore {
        var value: DeviceCredentials? = DeviceCredentials(
            baseUrl = "https://example.test",
            token = "a".repeat(32),
            deviceId = "poco",
        )
        override fun read(): DeviceCredentials? = value
        override fun write(credentials: DeviceCredentials): Boolean {
            value = credentials
            return true
        }
        override fun rememberDeviceId(deviceId: String): Boolean {
            value = value?.copy(deviceId = deviceId)
            return true
        }
        override fun clear() { value = null }
        override fun hasCredentials(): Boolean = value != null
    }

    private class Transport(var agendaAnswer: AgendaFetch) : SyncTransport {
        var agendaEtag: String? = null
        override suspend fun fetchQueue(
            credentials: DeviceCredentials,
            etag: String?,
        ): QueueFetch = QueueFetch.NotModified

        override suspend fun fetchAgenda(
            credentials: DeviceCredentials,
            etag: String?,
        ): AgendaFetch {
            agendaEtag = etag
            return agendaAnswer
        }

        override suspend fun sendEvents(
            credentials: DeviceCredentials,
            deviceId: String,
            events: List<StudyEvent>,
        ): EventDelivery = EventDelivery.Answered(emptyList())

        /**
         * Le moteur d'études n'envoie jamais de capture : les deux files ont
         * des calendriers séparés (contrat §16). Cette doublure le vérifie en
         * échouant si quelque chose l'appelait ici.
         */
        override suspend fun sendCaptures(
            credentials: DeviceCredentials,
            deviceId: String,
            captures: List<Capture>,
        ): CaptureDelivery = error("le moteur d'études n'envoie pas de captures")

        /**
         * L'échange à deux sens de l'agenda (V1.4) n'a pas de magasin local
         * dans ce montage : le moteur ne doit donc jamais l'appeler, et cette
         * doublure échoue bruyamment si c'était le cas.
         */
        override suspend fun fetchAgendaChanges(
            credentials: DeviceCredentials,
            since: String?,
        ): AgendaDelta = error("aucun magasin d'agenda dans ce montage")

        override suspend fun sendAgendaChanges(
            credentials: DeviceCredentials,
            deviceId: String,
            changes: List<AgendaEntry>,
        ): AgendaDelivery = error("aucun magasin d'agenda dans ce montage")

    }

    @Test
    fun reponseFraicheRemplaceLeCacheAvecSonEtagEtSaDate() = runTest {
        val cache = MemoryAgendaCache(CachedAgenda(etag = "ancien"))
        val snapshot = snapshot("tmp_nouveau")
        val transport = Transport(AgendaFetch.Fresh(snapshot, etag = "nouveau"))
        val engine = engine(cache, transport, now = 42L)

        engine.sync()

        assertEquals("ancien", transport.agendaEtag)
        assertEquals(snapshot, cache.value.snapshot)
        assertEquals("nouveau", cache.value.etag)
        assertEquals(42L, cache.value.fetchedAtMillis)
        assertEquals(1, cache.stores)
    }

    @Test
    fun reponse304NeChangeQueLaFraicheur() = runTest {
        val original = snapshot("tmp_garde")
        val cache = MemoryAgendaCache(CachedAgenda(original, "etag", 10L))

        engine(cache, Transport(AgendaFetch.NotModified), now = 99L).sync()

        assertEquals(original, cache.value.snapshot)
        assertEquals(99L, cache.value.fetchedAtMillis)
        assertEquals(1, cache.touches)
        assertEquals(0, cache.stores)
    }

    @Test
    fun panneReseauConserveLaDerniereCopieHorsLigne() = runTest {
        val original = snapshot("tmp_hors_ligne")
        val cache = MemoryAgendaCache(CachedAgenda(original, "etag", 10L))

        val outcome = engine(
            cache,
            Transport(AgendaFetch.Failed("Réseau coupé", retryable = true)),
            now = 99L,
        ).sync()

        assertTrue(outcome is SyncOutcome.Done)
        assertEquals(CachedAgenda(original, "etag", 10L), cache.value)
        assertEquals(0, cache.stores)
        assertEquals(0, cache.touches)
    }

    @Test
    fun jetonRefuseArreteAussiLaLectureAgenda() = runTest {
        val cache = MemoryAgendaCache()
        val engine = engine(cache, Transport(AgendaFetch.Unauthorized), now = 99L)

        assertEquals(SyncOutcome.Halted, engine.sync())
        assertTrue(engine.state.value.halted)
    }

    @Test
    fun desenrolementEffaceAgendaSansToucherUneOutbox() = runTest {
        val cache = MemoryAgendaCache(CachedAgenda(snapshot("tmp_secret"), "etag", 10L))
        val engine = engine(cache, Transport(AgendaFetch.NotModified), now = 99L)

        engine.forgetDevice()

        assertEquals(1, cache.clears)
        assertTrue(cache.value.snapshot.window48h.isEmpty())
        assertFalse(engine.state.value.enrolled)
    }

    private fun engine(cache: AgendaCache, transport: SyncTransport, now: Long) = SyncEngine(
        outbox = EmptyOutbox(),
        queueCache = MemoryQueueCache(),
        credentialStore = Credentials(),
        transport = transport,
        agendaCache = cache,
        timeSource = { now },
        logger = SyncLogger { _, _ -> },
    )

    private fun snapshot(id: String) = AgendaSnapshot(
        generatedAt = "2026-08-06T05:12:00Z",
        timezone = "Europe/Paris",
        nextLock = null,
        window48h = listOf(
            AgendaItem(
                id = id,
                label = "Garde",
                kind = "garde",
                startsAt = OffsetDateTime.parse("2026-08-06T20:00:00+02:00"),
                endsAt = OffsetDateTime.parse("2026-08-07T08:00:00+02:00"),
                allDay = false,
            ),
        ),
    )
}
