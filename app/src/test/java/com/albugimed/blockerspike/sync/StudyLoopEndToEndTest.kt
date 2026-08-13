package com.albugimed.blockerspike.sync

import com.albugimed.blockerspike.capture.Capture
import com.albugimed.blockerspike.capture.CaptureDelivery

import com.albugimed.blockerspike.study.DeclarationBuildResult
import com.albugimed.blockerspike.study.DeclareFormState
import com.albugimed.blockerspike.study.WorkUnitType
import com.albugimed.blockerspike.study.buildActivityDeclaration
import com.albugimed.blockerspike.study.buildFreeActivityDeclaration
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
        val commandsReceived = mutableListOf<PathCommand>()
    }

    private class ServerTransport(private val server: FakeServer) : SyncTransport {
        override suspend fun fetchQueue(credentials: DeviceCredentials, etag: String?): QueueFetch =
            if (server.offline) QueueFetch.Failed("Réseau coupé", retryable = true)
            else QueueFetch.Fresh(server.queue, etag = "\"v1\"", deviceId = "poco-x7")

        override suspend fun fetchAgenda(
            credentials: DeviceCredentials,
            etag: String?,
        ): AgendaFetch = if (server.offline) {
            AgendaFetch.Failed("Réseau coupé", retryable = true)
        } else {
            AgendaFetch.NotModified
        }

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

        /**
         * Le vrai serveur, en miniature : il applique la coche à sa file, et
         * répond un verdict par commande. `noop` quand l'étape n'existe pas —
         * un succès, pas un refus.
         */
        override suspend fun sendPathCommands(
            credentials: DeviceCredentials,
            deviceId: String,
            commands: List<PathCommand>,
        ): PathCommandDelivery {
            if (server.offline) return PathCommandDelivery.Failed("Réseau coupé", retryable = true)

            val results = commands.map { command ->
                server.commandsReceived += command
                when (command) {
                    is PathCommand.CompleteStep -> {
                        val known = server.queue.items.any { it.stepId == command.stepId }
                        if (known) {
                            server.queue = server.queue.copy(
                                items = server.queue.items.map { item ->
                                    if (item.stepId != command.stepId) item
                                    else item.copy(
                                        completedAt = command.completedAt.takeIf { command.completed },
                                    )
                                },
                            )
                            PathCommandResult(command.commandId, "applied", null)
                        } else {
                            PathCommandResult(command.commandId, "noop", "étape introuvable")
                        }
                    }

                    else -> PathCommandResult(command.commandId, "applied", null)
                }
            }
            return PathCommandDelivery.Answered(results)
        }
    }

    private class MemoryPathOutbox : PathCommandOutbox {
        var state = PathOutboxState()

        fun enqueue(command: PathCommand) {
            state = state.copy(pending = collapsePathCommands(state.pending, command))
        }

        override suspend fun current(): PathOutboxState = state
        override suspend fun enqueueAll(commands: List<PathCommand>): Boolean {
            commands.forEach(::enqueue)
            return true
        }
        override suspend fun forget(commandIds: Set<String>): Boolean {
            state = state.copy(pending = state.pending.filterNot { it.commandId in commandIds })
            return true
        }
        override suspend fun bury(rejected: List<DeadPathCommand>): Boolean {
            val ids = rejected.mapTo(mutableSetOf()) { it.command.commandId }
            state = state.copy(
                pending = state.pending.filterNot { it.commandId in ids },
                dead = state.dead + rejected,
            )
            return true
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
    private val pathOutbox = MemoryPathOutbox()
    private val cache = MemoryQueueCache()
    private var clock = 1_000L

    private val engine = SyncEngine(
        outbox = outbox,
        queueCache = cache,
        credentialStore = MemoryCredentials(),
        transport = ServerTransport(server),
        pathOutbox = pathOutbox,
        timeSource = { clock },
        logger = { _, _ -> },
    )

    private val etape = QueueItem(
        stepId = "stp_01JZQK3M8F2WQRSTVWXYZ0123",
        label = "Insuffisance cardiaque — relecture",
        kind = "revision",
        subject = NodeRef("nod_cardio", "Cardiologie"),
        chapter = NodeRef("nod_ic", "Insuffisance cardiaque"),
        resource = ResourceRef(
            resourceId = "res_college_cardio",
            label = "Collège de cardiologie",
            type = "pdf_drive",
            openUri = "https://drive.example/cardio",
        ),
        signals = QueueSignals(null, null, null, null),
    )

    private val matiere = AcademicNodeRef(
        nodeId = "nod_cardio",
        label = "Cardiologie",
        kind = AcademicNodeKind.SUBJECT,
        parentId = null,
    )
    private val chapitre = AcademicNodeRef(
        nodeId = "nod_ic",
        label = "Insuffisance cardiaque",
        kind = AcademicNodeKind.CHAPTER,
        parentId = matiere.nodeId,
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
            activityKind = ActivityKind.REVISION,
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

    private fun declarerHorsFile(suffixe: String): StudyEvent {
        val built = buildFreeActivityDeclaration(
            chapter = chapitre,
            activityKind = ActivityKind.TRAINING,
            form = DeclareFormState(
                durationMinutes = "45",
                unitType = WorkUnitType.ANNALE,
                annaleLabel = "ECN 2019 — dossier 3",
                difficulty = Difficulty.OK,
            ),
            occurredAt = OffsetDateTime.parse("2026-07-31T17:00:00+02:00"),
        )
        assertTrue(built is DeclarationBuildResult.Valid)
        val event = (built as DeclarationBuildResult.Valid).declaration
            .toStudyEvent("evt_01JZR4A9M2XK7QRSTVWXYZ02$suffixe")
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
        assertEquals("res_college_cardio", recu.resourceId)
        assertEquals(null, recu.activityKind)
    }

    @Test
    fun uneDeclarationHorsFileSurvitHorsLigneSansFausseEtape() = runTest {
        server.offline = true
        val event = declarerHorsFile("34")

        assertTrue(engine.sync() is SyncOutcome.Failed)
        assertEquals(listOf(event), outbox.state.pending)

        server.offline = false
        assertTrue(engine.sync(force = true) is SyncOutcome.Done)
        val received = server.received.getValue(event.eventId)
        assertEquals("nod_ic", received.nodeId)
        assertEquals(null, received.stepId)
        assertEquals(null, received.resourceId)
        assertEquals(ActivityKind.TRAINING, received.activityKind)
        assertEquals(ActivityUnit.Annale("ECN 2019 — dossier 3"), received.unit)
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
            nodes = listOf(matiere, chapitre),
        )

        engine.sync()
        assertEquals(
            listOf("stp_pose_en_premier", "stp_pose_en_second"),
            cache.stored.snapshot.items.map { it.stepId },
        )
        assertEquals(listOf("nod_cardio", "nod_ic"), cache.stored.snapshot.nodes.map { it.nodeId })

        // Réseau coupé : la copie précédente reste, elle n'est pas écrasée par
        // du vide. Une file vide et une file inconnue ne se ressemblent pas.
        server.offline = true
        engine.sync(force = true)

        assertEquals(2, cache.stored.snapshot.items.size)
        assertEquals(2, cache.stored.snapshot.nodes.size)
        assertFalse(engine.state.value.halted)
    }

    // ------------------------------------------------- les gestes du parcours

    private fun cocher(suffixe: String, stepId: String, completed: Boolean = true) =
        PathCommand.CompleteStep(
            commandId = "cmd_01JZR4A9M2XK7QRSTVWXYZ01$suffixe",
            stepId = stepId,
            completed = completed,
            completedAt = "2026-07-31T18:00:00+02:00",
        ).also(pathOutbox::enqueue)

    @Test
    fun cocherHorsLigneTientJusquAuRetourDuReseau() = runTest {
        server.queue = QueueSnapshot(
            generatedAt = "2026-07-31T13:00:00.000Z",
            items = listOf(etape),
            nodes = listOf(matiere, chapitre),
        )
        server.offline = true

        val geste = cocher("23", etape.stepId)
        engine.sync()

        // Rien n'est parti, et rien n'est perdu.
        assertTrue(server.commandsReceived.isEmpty())
        assertEquals(listOf(geste), pathOutbox.state.pending)

        server.offline = false
        engine.sync(force = true)

        assertEquals(listOf(geste), server.commandsReceived)
        assertTrue(pathOutbox.state.pending.isEmpty())

        // Et — c'est tout l'intérêt de l'ordre d'envoi — la file redescendue
        // dans le MÊME passage porte déjà la coche. Envoyée après le
        // rafraîchissement, elle serait restée grise une synchronisation de trop.
        assertEquals(
            "2026-07-31T18:00:00+02:00",
            cache.stored.snapshot.items.single().completedAt,
        )
    }

    @Test
    fun decocherEstUnEtatQueLeRejeuNePeutPasInverser() = runTest {
        server.queue = QueueSnapshot(
            generatedAt = "2026-07-31T13:00:00.000Z",
            items = listOf(etape.copy(completedAt = "2026-07-30T09:00:00+02:00")),
            nodes = listOf(matiere, chapitre),
        )

        cocher("24", etape.stepId, completed = false)
        engine.sync()
        assertEquals(null, cache.stored.snapshot.items.single().completedAt)

        // Rejoué — ce que ferait une réponse perdue — le même geste redonne le
        // même état. C'est ce qui dispense le serveur de tenir une table des
        // commandes déjà vues.
        cocher("24", etape.stepId, completed = false)
        engine.sync(force = true)
        assertEquals(null, cache.stored.snapshot.items.single().completedAt)
    }

    @Test
    fun uneEtapeDisparueDeLAtelierNeBloquePasLaFile() = runTest {
        // L'atelier a supprimé l'étape entre-temps : le serveur répond `noop`.
        // C'est un succès — la renvoyer donnerait éternellement la même réponse.
        cocher("25", "stp_01JZQK3M8F2WQRSTVWXYZ9999")

        engine.sync()

        assertTrue(pathOutbox.state.pending.isEmpty())
        assertTrue(pathOutbox.state.dead.isEmpty())
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
