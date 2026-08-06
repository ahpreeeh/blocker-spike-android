package com.albugimed.blockerspike.study

import android.content.Context
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.sync.CachedQueue
import com.albugimed.blockerspike.sync.EnrolOutcome
import com.albugimed.blockerspike.sync.OutboxState
import com.albugimed.blockerspike.sync.StudyEvent
import com.albugimed.blockerspike.sync.SyncState
import com.albugimed.blockerspike.sync.newEventId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update

/** Frontière testable entre les écrans D3 et le stockage/transport D1-D2. */
interface StudyRepository {
    val queueState: Flow<StudyQueueState>
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
}

class LocalStudySaveException : Exception("L'écriture dans la file locale a échoué")

/** Adaptateur vers les contrats réels du paquet sync, sans les redéfinir. */
class SyncStudyRepository(
    cachedQueues: Flow<CachedQueue>,
    outboxStates: Flow<OutboxState>,
    override val syncState: Flow<SyncState> = flowOf(SyncState(enrolled = true)),
    private val enqueue: suspend (StudyEvent) -> Boolean,
    private val requestSync: suspend (force: Boolean) -> Unit,
    private val enrol: suspend (baseUrl: String, token: String) -> EnrolOutcome = { _, _ ->
        EnrolOutcome.Unreachable("Enrôlement indisponible")
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val eventIdFactory: (Long) -> String = { newEventId(it) },
) : StudyRepository {
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
}

/** Simulacre manuel pour les previews/tests, suivant les conventions du dépôt. */
class InMemoryStudyRepository(
    initialState: StudyQueueState = StudyQueueState(),
    initialSyncState: SyncState = SyncState(enrolled = true),
) : StudyRepository {
    private val mutableQueueState = MutableStateFlow(initialState)
    private val mutableDeclarations = mutableListOf<ActivityDeclaration>()

    override val queueState: StateFlow<StudyQueueState> = mutableQueueState.asStateFlow()
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
                syncState = Graph.syncEngine.state,
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
