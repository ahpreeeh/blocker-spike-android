package com.albugimed.blockerspike.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le moteur d'envoi.
 *
 * Ces tests ne vérifient pas qu'il envoie : ils vérifient qu'il **ne perd
 * rien**. Chaque cas décrit une façon dont une trace pourrait disparaître —
 * une panne réseau, une réponse partielle, un jeton révoqué — et constate
 * qu'elle reste en file d'attente ou en file morte, jamais nulle part.
 */
class SyncEngineTest {

    // ---------------------------------------------------------------- doubles

    private class FakeOutbox(pending: List<StudyEvent> = emptyList()) : StudyOutbox {
        var state = OutboxState(pending = pending)
        val buried = mutableListOf<DeadEvent>()

        override suspend fun current(): OutboxState = state

        override suspend fun forget(eventIds: Set<String>): Boolean {
            state = state.copy(pending = state.pending.filterNot { it.eventId in eventIds })
            return true
        }

        override suspend fun bury(rejected: List<DeadEvent>): Boolean {
            val ids = rejected.mapTo(mutableSetOf()) { it.event.eventId }
            state = state.copy(
                pending = state.pending.filterNot { it.eventId in ids },
                dead = state.dead + rejected,
            )
            buried += rejected
            return true
        }
    }

    private class FakeQueueCache : QueueCache {
        var stored: CachedQueue = CachedQueue()
        var touches = 0
        var cleared = 0

        override suspend fun current(): CachedQueue = stored
        override suspend fun store(snapshot: QueueSnapshot, etag: String?, atMillis: Long) {
            stored = CachedQueue(snapshot, etag, atMillis)
        }
        override suspend fun touch(atMillis: Long) { touches += 1 }
        override suspend fun clear() { cleared += 1; stored = CachedQueue() }
    }

    private class FakeCredentialStore(
        var credentials: DeviceCredentials? = DeviceCredentials(
            baseUrl = "https://atelier.example",
            token = "oat_TOKEN",
            deviceId = "poco-x7",
        ),
    ) : DeviceCredentialStore {
        var writes = 0
        override fun read(): DeviceCredentials? = credentials
        override fun write(credentials: DeviceCredentials): Boolean {
            this.credentials = credentials
            writes += 1
            return true
        }
        override fun rememberDeviceId(deviceId: String): Boolean {
            credentials = credentials?.copy(deviceId = deviceId)
            return true
        }
        override fun clear() { credentials = null }
        override fun hasCredentials(): Boolean = credentials?.deviceId != null
    }

    private class FakeTransport(
        var queueAnswer: QueueFetch = QueueFetch.NotModified,
        var maxAcceptedBatch: Int = Int.MAX_VALUE,
        val verdict: (StudyEvent) -> EventResult? = { EventResult(it.eventId, "accepted", null) },
        var deliveryOverride: EventDelivery? = null,
    ) : SyncTransport {
        val batchSizes = mutableListOf<Int>()
        var queueCalls = 0

        override suspend fun fetchQueue(credentials: DeviceCredentials, etag: String?): QueueFetch {
            queueCalls += 1
            return queueAnswer
        }

        override suspend fun fetchAgenda(
            credentials: DeviceCredentials,
            etag: String?,
        ): AgendaFetch = AgendaFetch.NotModified

        override suspend fun sendEvents(
            credentials: DeviceCredentials,
            deviceId: String,
            events: List<StudyEvent>,
        ): EventDelivery {
            batchSizes += events.size
            deliveryOverride?.let { return it }
            if (events.size > maxAcceptedBatch) return EventDelivery.TooLarge
            return EventDelivery.Answered(events.mapNotNull(verdict))
        }
    }

    private var clock = 1_000L
    private val noOpLogger = SyncLogger { _, _ -> }

    private fun event(suffix: String) = StudyEvent(
        eventId = "evt_$suffix",
        type = StudyEventType.ACTIVITY_RECORDED,
        occurredAt = "2026-07-31T15:42:00+02:00",
        nodeId = "nod_x",
    )

    private fun engine(
        outbox: FakeOutbox,
        transport: FakeTransport,
        cache: FakeQueueCache = FakeQueueCache(),
        credentials: FakeCredentialStore = FakeCredentialStore(),
    ) = SyncEngine(
        outbox = outbox,
        queueCache = cache,
        credentialStore = credentials,
        transport = transport,
        timeSource = { clock },
        logger = noOpLogger,
    )

    // ------------------------------------------------------------------ tests

    @Test
    fun sansEnrolementRienNePart() = runTest {
        val outbox = FakeOutbox(listOf(event("a")))
        val transport = FakeTransport()

        val outcome = engine(outbox, transport, credentials = FakeCredentialStore(null)).sync()

        assertEquals(SyncOutcome.NotEnrolled, outcome)
        assertTrue(transport.batchSizes.isEmpty())
        assertEquals(1, outbox.state.pending.size)
    }

    @Test
    fun accepteEtDoublonSontDeuxSucces() = runTest {
        val outbox = FakeOutbox(listOf(event("a"), event("b")))
        val transport = FakeTransport(verdict = { candidate ->
            // Le rejeu d'un événement déjà reçu est un succès, pas une erreur :
            // c'est la preuve que l'idempotence fonctionne.
            val status = if (candidate.eventId == "evt_b") "duplicate" else "accepted"
            EventResult(candidate.eventId, status, null)
        })

        val outcome = engine(outbox, transport).sync()

        assertTrue(outcome is SyncOutcome.Done)
        assertEquals(2, (outcome as SyncOutcome.Done).settled)
        assertTrue(outbox.state.pending.isEmpty())
        assertTrue(outbox.buried.isEmpty())
    }

    @Test
    fun refusExpliciteVaEnFileMorteEtPasAuxOubliettes() = runTest {
        val outbox = FakeOutbox(listOf(event("a")))
        val transport = FakeTransport(verdict = {
            EventResult(it.eventId, "rejected", "occurred_at illisible")
        })

        val outcome = engine(outbox, transport).sync()

        assertEquals(1, (outcome as SyncOutcome.Done).buried)
        assertTrue(outbox.state.pending.isEmpty())
        assertEquals(1, outbox.state.dead.size)
        assertEquals("occurred_at illisible", outbox.state.dead.first().reason)
    }

    @Test
    fun panneReseauNeSupprimeRienEtDiffere() = runTest {
        val outbox = FakeOutbox(listOf(event("a")))
        val transport = FakeTransport(
            deliveryOverride = EventDelivery.Failed("UnknownHostException", retryable = true),
        )
        val sync = engine(outbox, transport)

        val outcome = sync.sync()

        assertTrue(outcome is SyncOutcome.Failed)
        assertEquals(1, outbox.state.pending.size)
        assertTrue(outbox.buried.isEmpty())
        assertEquals(1, sync.state.value.consecutiveFailures)
        assertTrue(sync.state.value.nextAttemptAtMillis!! > clock)
    }

    @Test
    fun leDosExponentielCroitEtLaRepriseManuelleLeContourne() = runTest {
        val outbox = FakeOutbox(listOf(event("a")))
        val transport = FakeTransport(
            deliveryOverride = EventDelivery.Failed("SocketTimeoutException", retryable = true),
        )
        val sync = engine(outbox, transport)

        sync.sync()
        val firstWait = sync.state.value.nextAttemptAtMillis!! - clock

        // Tentative automatique pendant l'attente : rien ne part.
        assertTrue(sync.sync() is SyncOutcome.Deferred)
        assertEquals(1, transport.batchSizes.size)

        // Le bouton de reprise, lui, passe outre.
        sync.sync(force = true)
        assertEquals(2, transport.batchSizes.size)
        assertTrue(sync.state.value.nextAttemptAtMillis!! - clock > firstWait)
    }

    @Test
    fun jetonRefuseArreteToutSansRienPerdre() = runTest {
        val outbox = FakeOutbox(listOf(event("a")))
        val transport = FakeTransport(deliveryOverride = EventDelivery.Unauthorized)
        val sync = engine(outbox, transport)

        assertEquals(SyncOutcome.Halted, sync.sync())
        assertTrue(sync.state.value.halted)
        assertEquals(1, outbox.state.pending.size)
        assertTrue(outbox.buried.isEmpty())

        // Et il ne réessaie pas en boucle : c'est un ré-enrôlement qu'il faut.
        assertEquals(SyncOutcome.Halted, sync.sync())
        assertEquals(1, transport.batchSizes.size)
    }

    @Test
    fun unEvenementAbsentDeLaReponseResteEnAttente() = runTest {
        val outbox = FakeOutbox(listOf(event("a"), event("b")))
        val transport = FakeTransport(verdict = { candidate ->
            // Le serveur oublie `evt_b`. Sans verdict explicite, on ne le
            // considère pas envoyé — le rejeu ne coûte rien, la perte si.
            if (candidate.eventId == "evt_b") null
            else EventResult(candidate.eventId, "accepted", null)
        })

        engine(outbox, transport).sync()

        assertEquals(listOf("evt_b"), outbox.state.pending.map { it.eventId })
    }

    @Test
    fun unLotTropVolumineuxEstCoupeJusquAPasser() = runTest {
        val outbox = FakeOutbox((1..120).map { event("e$it") })
        val transport = FakeTransport(maxAcceptedBatch = 50)

        val outcome = engine(outbox, transport).sync()

        // Un `413` n'est pas un refus de contenu : c'est une question de
        // taille, et une question de taille se résout en coupant.
        assertEquals(120, (outcome as SyncOutcome.Done).settled)
        assertEquals(0, outcome.buried)
        assertTrue(outbox.state.pending.isEmpty())
        assertEquals(listOf(100, 50), transport.batchSizes.take(2))
    }

    @Test
    fun unEvenementSeulTropVolumineuxEstEnterre() = runTest {
        val outbox = FakeOutbox(listOf(event("a")))
        val transport = FakeTransport(maxAcceptedBatch = 0)

        val outcome = engine(outbox, transport).sync()

        // Plus rien à couper : le renvoyer donnerait éternellement la même
        // réponse. Il quitte la file d'attente, pas l'appareil.
        assertEquals(1, (outcome as SyncOutcome.Done).buried)
        assertTrue(outbox.state.pending.isEmpty())
        assertEquals(1, outbox.state.dead.size)
    }

    @Test
    fun uneFileIllisibleBloqueLEnvoi() = runTest {
        val outbox = FakeOutbox()
        outbox.state = OutboxState(storageHealthy = false)
        val transport = FakeTransport()

        val outcome = engine(outbox, transport).sync()

        // Envoyer à l'aveugle risquerait de marquer comme parties des traces
        // qu'on n'a jamais réussi à lire.
        assertTrue(outcome is SyncOutcome.Failed)
        assertTrue(transport.batchSizes.isEmpty())
        assertEquals(0, transport.queueCalls)
    }

    @Test
    fun laFileEstRafraichieEtSonOrdreEstConserve() = runTest {
        val snapshot = QueueSnapshot(
            generatedAt = "2026-07-31T13:00:00.000Z",
            items = listOf("stp_3", "stp_1", "stp_2").map { id ->
                QueueItem(
                    stepId = id,
                    label = id,
                    kind = "revision",
                    subject = NodeRef("nod_s", "Cardiologie"),
                    chapter = null,
                    resource = null,
                    signals = QueueSignals(null, null, null, null),
                )
            },
        )
        val cache = FakeQueueCache()
        val transport = FakeTransport(
            queueAnswer = QueueFetch.Fresh(snapshot, etag = "\"abc\"", deviceId = "poco-x7"),
        )

        engine(FakeOutbox(), transport, cache = cache).sync()

        assertEquals(listOf("stp_3", "stp_1", "stp_2"), cache.stored.snapshot.items.map { it.stepId })
        assertEquals("\"abc\"", cache.stored.etag)
    }

    @Test
    fun uneFileNonRafraichieNeComprometAucuneTrace() = runTest {
        val outbox = FakeOutbox(listOf(event("a")))
        val transport = FakeTransport(
            queueAnswer = QueueFetch.Failed("UnknownHostException", retryable = true),
        )

        val outcome = engine(outbox, transport).sync()

        // La trace est partie ; seule la copie de confort manque.
        assertTrue(outcome is SyncOutcome.Done)
        assertFalse((outcome as SyncOutcome.Done).queueRefreshed)
        assertTrue(outbox.state.pending.isEmpty())
    }

    @Test
    fun enrolementRefuseNEnregistreRien() = runTest {
        val store = FakeCredentialStore(null)
        val transport = FakeTransport(queueAnswer = QueueFetch.Unauthorized)

        val outcome = engine(FakeOutbox(), transport, credentials = store)
            .enrol("https://atelier.example", "oat_MAUVAIS")

        assertEquals(EnrolOutcome.Rejected, outcome)
        assertNull(store.credentials)
        assertEquals(0, store.writes)
    }

    @Test
    fun enrolementReussiApprendLIdentifiantDAppareil() = runTest {
        val store = FakeCredentialStore(null)
        val transport = FakeTransport(
            queueAnswer = QueueFetch.Fresh(QueueSnapshot.EMPTY, etag = null, deviceId = "poco-x7"),
        )

        val outcome = engine(FakeOutbox(), transport, credentials = store)
            .enrol("  atelier.example  ", "oat_0123-4567")

        assertEquals(EnrolOutcome.Enrolled("poco-x7"), outcome)
        assertEquals("https://atelier.example", store.credentials?.baseUrl)
        assertEquals("oat_01234567", store.credentials?.token)
        assertEquals("poco-x7", store.credentials?.deviceId)
    }

    @Test
    fun enrolementEnClairEstRefuseAvantToutAppel() = runTest {
        val store = FakeCredentialStore(null)
        val transport = FakeTransport()

        val outcome = engine(FakeOutbox(), transport, credentials = store)
            .enrol("http://atelier.example", "oat_0123456789")

        assertEquals(EnrolOutcome.InvalidUrl, outcome)
        assertEquals(0, transport.queueCalls)
        assertNull(store.credentials)
    }
}
