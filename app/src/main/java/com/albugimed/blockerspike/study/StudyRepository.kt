package com.albugimed.blockerspike.study

import android.content.Context
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.sync.AgendaStore
import com.albugimed.blockerspike.sync.AgendaStoreState
import com.albugimed.blockerspike.sync.CachedAgenda
import com.albugimed.blockerspike.sync.CachedQueue
import com.albugimed.blockerspike.sync.EnrolOutcome
import com.albugimed.blockerspike.sync.OutboxState
import com.albugimed.blockerspike.sync.StudyEvent
import com.albugimed.blockerspike.sync.SyncState
import com.albugimed.blockerspike.sync.agendaEditedNow
import com.albugimed.blockerspike.sync.newEventId
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
}

class LocalStudySaveException : Exception("L'écriture dans la file locale a échoué")

/** Adaptateur vers les contrats réels du paquet sync, sans les redéfinir. */
class SyncStudyRepository(
    cachedQueues: Flow<CachedQueue>,
    outboxStates: Flow<OutboxState>,
    cachedAgendas: Flow<CachedAgenda> = flowOf(CachedAgenda()),
    agendaStoreStates: Flow<AgendaStoreState> = flowOf(AgendaStoreState()),
    override val syncState: Flow<SyncState> = flowOf(SyncState(enrolled = true)),
    private val enqueue: suspend (StudyEvent) -> Boolean,
    private val requestSync: suspend (force: Boolean) -> Unit,
    private val agendaStore: AgendaStore? = null,
    private val enrol: suspend (baseUrl: String, token: String) -> EnrolOutcome = { _, _ ->
        EnrolOutcome.Unreachable("Enrôlement indisponible")
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val eventIdFactory: (Long) -> String = { newEventId(it) },
) : StudyRepository {
    override val agendaState: Flow<AgendaState> = cachedAgendas.map { it.toAgendaState() }

    override val agendaEntriesState: Flow<AgendaEntriesState> =
        agendaStoreStates.map { it.toAgendaEntriesState() }

    override val queueState: Flow<StudyQueueState> = combine(
        cachedQueues,
        outboxStates,
    ) { cached, outbox ->
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
                syncState = Graph.syncEngine.state,
                agendaStore = Graph.agendaStore,
                enqueue = Graph.studyOutbox::enqueue,
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
