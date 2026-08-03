package com.albugimed.blockerspike.sync

import com.albugimed.blockerspike.study.DeclarationBuildResult
import com.albugimed.blockerspike.study.DeclareFormState
import com.albugimed.blockerspike.study.WorkUnitType
import com.albugimed.blockerspike.study.buildActivityDeclaration
import com.albugimed.blockerspike.study.toStudyEvent
import java.time.OffsetDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La boucle entière, côté téléphone, sans appareil ni serveur.
 *
 * Ce test rejoue les points 4 à 8 de la vérification de fin de tranche —
 * ceux qui comptent — contre un faux serveur qui se comporte comme le vrai :
 * il tient un index de ce qu'il a déjà reçu, et répond donc `duplicate` à un
 * rejeu au lieu de créer une seconde ligne.
 *
 * Le scénario le plus important est celui de la **réponse perdue** : le
 * serveur enregistre, puis la connexion tombe avant que la réponse arrive.
 * C'est le seul moment où l'on peut à la fois perdre une trace (en la
 * supprimant trop tôt) et la compter deux fois (en la renvoyant sans
 * idempotence). Les deux sont vérifiés ici.
 */
class StudyLoopEndToEndTest {

    // ------------------------------------------------------------- faux monde

    private class FakeServer {
        /** Ce que le serveur a réellement enregistré, indexé comme sa base. */
        val received = linkedMapOf<String, StudyEvent>()
        var queue: QueueSnapshot = QueueSnapshot.EMPTY
        var offline = false
        /** Le serveur enregistre, puis la réponse n'arrive jamais. */
        var loseNextResponse = false
        var rejectIf: (StudyEvent) -> String? = { null }
    }

    private class ServerTransport(private val server: FakeServer) : SyncTransport {
        override suspend fun fetchQueue(credentials: DeviceCredentials, etag: String?): QueueFetch =
            if (server.offline) QueueFetch.Failed("Réseau coupé", retryable = true)
            else QueueFetch.Fresh(server.queue, etag = "\"v1\"", deviceId = "poco-x7")

        override suspend fun sendEvents(
            credentials: DeviceCredentials,
            deviceId: String,
            events: List<StudyEvent>,
        ): EventDelivery {
            if (server.offline) return EventDelivery.Failed("Réseau coupé", retryable = true)

            val results = events.map { event ->
                val refus = server.rejectIf(event)
                when {
                    refus != null -> EventResult(event.eventId, "rejected", refus)
                    server.received.containsKey(event.eventId) ->
                        EventResult(event.eventId, "duplicate", null)
                    else -> {
                        server.received[event.eventId] = event
                        EventResult(event.eventId, "accepted", null)
                    }
                }
            }

            if (server.loseNextResponse) {
                server.loseNextResponse = false
                return EventDelivery.Failed("Réponse perdue", retryable = true)
            }
            return EventDelivery.Answered(results)
        }
    }

    private class MemoryOutbox : StudyOutbox {
        var state = OutboxState()

        fun enqueue(event: StudyEvent) {
            state = state.copy(pending = state.pending + event)
        }

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
            return true
        }
    }

    private class MemoryQueueCache : QueueCache {
        var stored = CachedQueue()
        override suspend fun current(): CachedQueue = stored
        override suspend fun store(snapshot: QueueSnapshot, etag: String?, atMillis: Long) {
            stored = CachedQueue(snapshot, etag, atMillis)
        }
        override suspend fun touch(atMillis: Long) = Unit
        override suspend fun clear() { stored = CachedQueue() }
    }

    private class MemoryCredentials : DeviceCredentialStore {
        var credentials: DeviceCredentials? = DeviceCredentials(
            baseUrl = "https://atelier.example",
            token = "oat_TOKEN",
            deviceId = "poco-x7",
        )
        override fun read() = credentials
        override fun write(credentials: DeviceCredentials): Boolean {
            this.credentials = credentials; return true
        }
        override fun rememberDeviceId(deviceId: String): Boolean {
            credentials = credentials?.copy(deviceId = deviceId); return true
        }
        override fun clear() { credentials = null }
        override fun hasCredentials() = credentials?.deviceId != null
    }

    // ------------------------------------------------------------- le montage

    private val server = FakeServer()
    private val outbox = MemoryOutbox()
    private val cache = MemoryQueueCache()
    private var clock = 1_000L

    private val engine = SyncEngine(
        outbox = outbox,
        queueCache = cache,
        credentialStore = MemoryCredentials(),
        transport = ServerTransport(server),
        timeSource = { clock },
        logger = { _, _ -> },
    )

    private val etape = QueueItem(
        stepId = "stp_01JZQK3M8F2WQRSTVWXYZ0123",
        label = "Insuffisance cardiaque — relecture",
        kind = "revision",
        subject = NodeRef("nod_cardio", "Cardiologie"),
        chapter = NodeRef("nod_ic", "Insuffisance cardiaque"),
        resource = null,
        signals = QueueSignals(null, null, null, null),
    )

    /** La déclaration de la vérification §6 : 35 minutes, pages 47 à 62, difficile. */
    private fun declarer(suffixe: String): StudyEvent {
        val form = DeclareFormState(
            durationMinutes = "35",
            unitType = WorkUnitType.PAGES,
            pagesFrom = "47",
            pagesTo = "62",
            difficulty = Difficulty.HARD,
        )
        val built = buildActivityDeclaration(
            item = etape,
            form = form,
            occurredAt = OffsetDateTime.parse("2026-07-31T15:42:00+02:00"),
        )
        assertTrue(built is DeclarationBuildResult.Valid)

        // `event_id` frappé ici, à la saisie — pas à l'envoi.
        val event = (built as DeclarationBuildResult.Valid).declaration
            .toStudyEvent("evt_01JZR4A9M2XK7QRSTVWXYZ01$suffixe")
        outbox.enqueue(event)
        return event
    }

    // ---------------------------------------------------------------- scénario

    @Test
    fun horsLigneLaSaisieReussitEtRienNePart() = runTest {
        server.offline = true

        val event = declarer("23")
        val outcome = engine.sync()

        assertTrue(outcome is SyncOutcome.Failed)
        assertEquals(listOf(event), outbox.state.pending)
        assertTrue(server.received.isEmpty())
    }

    @Test
    fun leReseauRevenuLaTracePartUneFois() = runTest {
        server.offline = true
        val event = declarer("23")
        engine.sync()

        server.offline = false
        val outcome = engine.sync(force = true)

        assertTrue(outcome is SyncOutcome.Done)
        assertEquals(1, (outcome as SyncOutcome.Done).settled)
        assertTrue(outbox.state.pending.isEmpty())
        assertEquals(listOf(event.eventId), server.received.keys.toList())

        // Le contenu déclaré est arrivé intact.
        val recu = server.received.getValue(event.eventId)
        assertEquals(35, recu.durationMinutes)
        assertEquals(ActivityUnit.Pages(47, 62), recu.unit)
        assertEquals(Difficulty.HARD, recu.difficulty)
        assertEquals("2026-07-31T15:42:00+02:00", recu.occurredAt)
    }

    @Test
    fun uneReponsePerdueNeCreeNiPerteNiDoublon() = runTest {
        val event = declarer("23")

        // Le serveur enregistre, la réponse n'arrive jamais.
        server.loseNextResponse = true
        assertTrue(engine.sync() is SyncOutcome.Failed)

        // La trace est toujours là : on ne l'a pas supprimée sans verdict.
        assertEquals(listOf(event), outbox.state.pending)
        assertEquals(1, server.received.size)

        // Le rejeu porte le même `event_id` : le serveur le reconnaît.
        val outcome = engine.sync(force = true)

        assertEquals(1, (outcome as SyncOutcome.Done).settled)
        assertTrue(outbox.state.pending.isEmpty())
        assertEquals(1, server.received.size)
    }

    @Test
    fun uneTraceRefuseeResteVisibleAuLieuDeDisparaitre() = runTest {
        val valide = declarer("23")
        val casse = declarer("24")
        server.rejectIf = { if (it.eventId == casse.eventId) "occurred_at illisible" else null }

        val outcome = engine.sync()

        assertEquals(1, (outcome as SyncOutcome.Done).settled)
        assertEquals(1, outcome.buried)
        assertTrue(outbox.state.pending.isEmpty())
        assertEquals(listOf(valide.eventId), server.received.keys.toList())

        // Refusée par le serveur, mais toujours sur l'appareil, avec son motif.
        assertEquals(1, outbox.state.dead.size)
        assertEquals(casse.eventId, outbox.state.dead.first().event.eventId)
        assertEquals("occurred_at illisible", outbox.state.dead.first().reason)
    }

    @Test
    fun laFileRedescendEtResteConsultableHorsLigne() = runTest {
        server.queue = QueueSnapshot(
            generatedAt = "2026-07-31T13:00:00.000Z",
            items = listOf(
                etape.copy(stepId = "stp_pose_en_premier"),
                etape.copy(stepId = "stp_pose_en_second", label = "Pharmaco"),
            ),
        )

        engine.sync()
        assertEquals(
            listOf("stp_pose_en_premier", "stp_pose_en_second"),
            cache.stored.snapshot.items.map { it.stepId },
        )

        // Réseau coupé : la copie précédente reste, elle n'est pas écrasée par
        // du vide. Une file vide et une file inconnue ne se ressemblent pas.
        server.offline = true
        engine.sync(force = true)

        assertEquals(2, cache.stored.snapshot.items.size)
        assertFalse(engine.state.value.halted)
    }

    @Test
    fun unAppareilDejaEnroleNAffichePasLeFormulaireAuDemarrage() {
        // L'état est connu dès la construction : sans cela, l'écran montre
        // brièvement le formulaire d'enrôlement à quelqu'un qui est enrôlé,
        // ce qui se lit comme « on m'a déconnecté ».
        assertTrue(engine.state.value.enrolled)

        val vierge = SyncEngine(
            outbox = MemoryOutbox(),
            queueCache = MemoryQueueCache(),
            credentialStore = MemoryCredentials().apply { clear() },
            transport = ServerTransport(server),
            timeSource = { clock },
            logger = { _, _ -> },
        )
        assertFalse(vierge.state.value.enrolled)
    }
}
