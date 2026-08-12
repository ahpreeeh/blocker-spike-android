package com.albugimed.blockerspike.study

import android.content.Context
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.sync.AgendaStore
import com.albugimed.blockerspike.sync.AgendaStoreState
import com.albugimed.blockerspike.sync.CachedAgenda
import com.albugimed.blockerspike.sync.CachedQueue
import com.albugimed.blockerspike.sync.EnrolOutcome
import com.albugimed.blockerspike.sync.OutboxState
import com.albugimed.blockerspike.sync.PathCommand
import com.albugimed.blockerspike.sync.PathOutboxState
import com.albugimed.blockerspike.sync.StudyEvent
import com.albugimed.blockerspike.sync.SyncState
import com.albugimed.blockerspike.sync.agendaEditedNow
import com.albugimed.blockerspike.sync.collapsePathCommands
import com.albugimed.blockerspike.sync.formatOccurredAt
import com.albugimed.blockerspike.sync.newEventId
import com.albugimed.blockerspike.sync.newPathCommandId
import com.albugimed.blockerspike.sync.newTemporalConstraintId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Frontière testable entre les écrans D3 et le stockage/transport D1-D2. */
interface StudyRepository {
    val queueState: Flow<StudyQueueState>
    val agendaState: Flow<AgendaState>

    /** La matière de l'agenda, modifiable ici — V1.4. */
    val agendaEntriesState: Flow<AgendaEntriesState>
    val syncState: Flow<SyncState>

    suspend fun enrolDevice(baseUrl: String, token: String): EnrolOutcome

    /** Synchronisation opportuniste, soumise au dos exponentiel du moteur. */
    suspend fun onQueueOpened()

    /** Geste explicite : force une nouvelle tentative. */
    suspend fun retryPending()

    /** Doit terminer l'écriture locale avant de rendre la main. */
    suspend fun saveActivityLocally(declaration: ActivityDeclaration)

    /** Tentative opportuniste séparée : elle ne conditionne jamais le succès local. */
    suspend fun syncAfterLocalSave()

    /**
     * Enregistre une saisie d'agenda. Doit terminer l'écriture locale avant de
     * rendre la main : l'envoi viendra ensuite, ou plus tard, ou jamais si le
     * réseau manque — la saisie, elle, est acquise.
     */
    suspend fun saveAgendaEntry(draft: AgendaDraft)

    /** Retire une entrée. Le retrait est daté et repart comme une modification. */
    suspend fun deleteAgendaEntry(id: String)

    /**
     * Coche ou décoche une étape. **Un état, pas un basculement** : c'est ce
     * qui rend le rejeu inoffensif, et c'est aussi ce qui interdit de déduire
     * l'achèvement du travail déclaré — seul ce geste-ci le décide.
     */
    suspend fun setStepDone(stepId: String, done: Boolean)

    /** Verse les chapitres d'une matière dans le parcours, en une fois. */
    suspend fun pourSubject(nodeId: String, kind: String)

    /**
     * Impose l'ordre affiché. La liste est **entière et absolue** : « monte
     * d'un rang » rejoué sur un parcours modifié entre-temps donnerait un
     * résultat que personne n'a voulu.
     */
    suspend fun reorderPath(orderedStepIds: List<String>)
}

class LocalStudySaveException : Exception("L'écriture dans la file locale a échoué")

/** Adaptateur vers les contrats réels du paquet sync, sans les redéfinir. */
class SyncStudyRepository(
    cachedQueues: Flow<CachedQueue>,
    outboxStates: Flow<OutboxState>,
    cachedAgendas: Flow<CachedAgenda> = flowOf(CachedAgenda()),
    agendaStoreStates: Flow<AgendaStoreState> = flowOf(AgendaStoreState()),
    pathOutboxStates: Flow<PathOutboxState> = flowOf(PathOutboxState()),
    override val syncState: Flow<SyncState> = flowOf(SyncState(enrolled = true)),
    private val enqueue: suspend (StudyEvent) -> Boolean,
    private val enqueuePathCommand: suspend (PathCommand) -> Boolean = { false },
    private val requestSync: suspend (force: Boolean) -> Unit,
    private val agendaStore: AgendaStore? = null,
    private val enrol: suspend (baseUrl: String, token: String) -> EnrolOutcome = { _, _ ->
        EnrolOutcome.Unreachable("Enrôlement indisponible")
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val eventIdFactory: (Long) -> String = { newEventId(it) },
    private val commandIdFactory: (Long) -> String = { newPathCommandId(it) },
) : StudyRepository {
    override val agendaState: Flow<AgendaState> = cachedAgendas.map { it.toAgendaState() }

    override val agendaEntriesState: Flow<AgendaEntriesState> =
        agendaStoreStates.map { it.toAgendaEntriesState() }

    override val queueState: Flow<StudyQueueState> = combine(
        cachedQueues,
        outboxStates,
        pathOutboxStates,
    ) { cached, outbox, path ->
        StudyQueueState(
            cachedAtMillis = cached.fetchedAtMillis,
            items = cached.snapshot.items,
            skippedQueueItems = cached.snapshot.skipped,
            nodes = cached.snapshot.nodes,
            skippedQueueNodes = cached.snapshot.skippedNodes,
            pendingCount = outbox.pendingCount,
            rejectedEvents = outbox.dead,
            unreadableCount = outbox.unreadable,
            outboxStorageHealthy = outbox.storageHealthy,
            pendingPathCommands = path.pending,
            rejectedPathCommands = path.dead,
        )
    }

    override suspend fun onQueueOpened() {
        requestSync(false)
    }

    override suspend fun enrolDevice(baseUrl: String, token: String): EnrolOutcome =
        enrol(baseUrl, token)

    override suspend fun retryPending() {
        requestSync(true)
    }

    override suspend fun saveActivityLocally(declaration: ActivityDeclaration) {
        val event = declaration.toStudyEvent(eventIdFactory(nowMillis()))
        if (!enqueue(event)) throw LocalStudySaveException()
    }

    override suspend fun syncAfterLocalSave() {
        requestSync(false)
    }

    override suspend fun saveAgendaEntry(draft: AgendaDraft) {
        val store = agendaStore ?: throw LocalStudySaveException()
        val now = nowMillis()
        val entry = draft.toEntry(
            // L'identifiant est frappé ici, à l'enregistrement, et jamais à
            // l'envoi : une entrée renvoyée après une coupure porte le même, et
            // le serveur la reconnaît au lieu d'en créer une seconde.
            id = draft.id ?: newTemporalConstraintId(now),
            editedAt = agendaEditedNow(now),
        )
        if (!store.put(entry)) throw LocalStudySaveException()
    }

    override suspend fun deleteAgendaEntry(id: String) {
        val store = agendaStore ?: throw LocalStudySaveException()
        // Le contenu est conservé : le retrait est un changement daté, et une
        // correction plus récente venue du PC doit pouvoir le défaire.
        val existing = store.current().entries.firstOrNull { it.id == id } ?: return
        val removed = existing.copy(deleted = true, editedAt = agendaEditedNow(nowMillis()))
        if (!store.put(removed)) throw LocalStudySaveException()
    }

    override suspend fun setStepDone(stepId: String, done: Boolean) {
        val now = nowMillis()
        push(
            PathCommand.CompleteStep(
                commandId = commandIdFactory(now),
                stepId = stepId,
                completed = done,
                // L'instant du geste, décalage compris : cocher hors ligne
                // hier, c'est hier — pas au retour du réseau.
                completedAt = formatOccurredAt(now),
            ),
        )
    }

    override suspend fun pourSubject(nodeId: String, kind: String) {
        val now = nowMillis()
        push(PathCommand.PourSubject(commandIdFactory(now), nodeId, kind))
    }

    override suspend fun reorderPath(orderedStepIds: List<String>) {
        if (orderedStepIds.isEmpty()) return
        val now = nowMillis()
        push(PathCommand.ReorderPath(commandIdFactory(now), orderedStepIds))
    }

    /**
     * Enregistre le geste, puis tente de l'envoyer.
     *
     * L'écriture locale d'abord, et elle seule peut faire échouer : un geste qui
     * paraît pris alors que rien n'est enregistré est la seule chose que l'écran
     * n'a pas le droit de faire. L'envoi qui suit est opportuniste — hors ligne,
     * il échoue sans rien annuler.
     */
    private suspend fun push(command: PathCommand) {
        if (!enqueuePathCommand(command)) throw LocalStudySaveException()
        requestSync(false)
    }
}

/** Simulacre manuel pour les previews/tests, suivant les conventions du dépôt. */
class InMemoryStudyRepository(
    initialState: StudyQueueState = StudyQueueState(),
    initialAgendaState: AgendaState = AgendaState(),
    initialAgendaEntries: AgendaEntriesState = AgendaEntriesState(),
    initialSyncState: SyncState = SyncState(enrolled = true),
) : StudyRepository {
    private val mutableQueueState = MutableStateFlow(initialState)
    private val mutableDeclarations = mutableListOf<ActivityDeclaration>()
    private val mutableAgendaEntries = MutableStateFlow(initialAgendaEntries)

    override val queueState: StateFlow<StudyQueueState> = mutableQueueState.asStateFlow()
    override val agendaState: StateFlow<AgendaState> =
        MutableStateFlow(initialAgendaState).asStateFlow()
    override val agendaEntriesState: StateFlow<AgendaEntriesState> =
        mutableAgendaEntries.asStateFlow()
    override val syncState: StateFlow<SyncState> = MutableStateFlow(initialSyncState).asStateFlow()

    val savedDeclarations: List<ActivityDeclaration>
        get() = synchronized(mutableDeclarations) { mutableDeclarations.toList() }

    override suspend fun onQueueOpened() = Unit

    override suspend fun enrolDevice(baseUrl: String, token: String): EnrolOutcome =
        EnrolOutcome.Unreachable("Simulacre non enrôlable")

    override suspend fun retryPending() = Unit

    override suspend fun saveActivityLocally(declaration: ActivityDeclaration) {
        synchronized(mutableDeclarations) {
            mutableDeclarations += declaration
        }
        mutableQueueState.update { state ->
            state.copy(pendingCount = state.pendingCount + 1)
        }
    }

    override suspend fun syncAfterLocalSave() = Unit

    override suspend fun saveAgendaEntry(draft: AgendaDraft) {
        val entry = draft.toEntry(
            id = draft.id ?: newTemporalConstraintId(System.currentTimeMillis()),
            editedAt = agendaEditedNow(System.currentTimeMillis()),
        )
        mutableAgendaEntries.update { state ->
            state.copy(entries = state.entries.filterNot { it.id == entry.id } + entry)
        }
    }

    override suspend fun deleteAgendaEntry(id: String) {
        mutableAgendaEntries.update { state ->
            state.copy(
                entries = state.entries.map {
                    if (it.id == id) {
                        it.copy(deleted = true, editedAt = agendaEditedNow(System.currentTimeMillis()))
                    } else {
                        it
                    }
                },
            )
        }
    }

    // Le simulacre met les gestes en attente comme le vrai magasin : c'est la
    // couche optimiste elle-même, et les previews doivent la montrer.
    override suspend fun setStepDone(stepId: String, done: Boolean) = queueCommand(
        PathCommand.CompleteStep(
            commandId = newPathCommandId(System.currentTimeMillis()),
            stepId = stepId,
            completed = done,
            completedAt = formatOccurredAt(System.currentTimeMillis()),
        ),
    )

    override suspend fun pourSubject(nodeId: String, kind: String) = queueCommand(
        PathCommand.PourSubject(newPathCommandId(System.currentTimeMillis()), nodeId, kind),
    )

    override suspend fun reorderPath(orderedStepIds: List<String>) {
        if (orderedStepIds.isEmpty()) return
        queueCommand(
            PathCommand.ReorderPath(newPathCommandId(System.currentTimeMillis()), orderedStepIds),
        )
    }

    private fun queueCommand(command: PathCommand) {
        mutableQueueState.update { state ->
            state.copy(
                pendingPathCommands = collapsePathCommands(state.pendingPathCommands, command),
            )
        }
    }
}

/** Point d'injection de processus ; la production se branche sur Graph. */
object StudyDependencies {
    @Volatile
    private var installedRepository: StudyRepository? = null

    fun repository(@Suppress("UNUSED_PARAMETER") context: Context): StudyRepository {
        installedRepository?.let { return it }
        return synchronized(this) {
            installedRepository ?: SyncStudyRepository(
                cachedQueues = Graph.queueCache.cached,
                outboxStates = Graph.studyOutbox.state,
                cachedAgendas = Graph.agendaCache.cached,
                agendaStoreStates = Graph.agendaStore.state,
                pathOutboxStates = Graph.pathCommandOutbox.state,
                syncState = Graph.syncEngine.state,
                agendaStore = Graph.agendaStore,
                enqueue = Graph.studyOutbox::enqueue,
                enqueuePathCommand = Graph.pathCommandOutbox::enqueue,
                requestSync = { force ->
                    Graph.syncEngine.sync(force = force)
                    Unit
                },
                enrol = Graph.syncEngine::enrol,
            ).also { installedRepository = it }
        }
    }

    @Synchronized
    fun install(repository: StudyRepository) {
        installedRepository = repository
    }
}
