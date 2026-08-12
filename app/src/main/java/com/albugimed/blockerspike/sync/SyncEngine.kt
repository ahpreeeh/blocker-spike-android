package com.albugimed.blockerspike.sync

import com.albugimed.blockerspike.policy.SystemTimeSource
import com.albugimed.blockerspike.policy.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Ce que le moteur consomme de la file d'attente. Volontairement minuscule. */
interface StudyOutbox {
    suspend fun current(): OutboxState
    suspend fun forget(eventIds: Set<String>): Boolean
    suspend fun bury(rejected: List<DeadEvent>): Boolean
}

/**
 * Idem pour les gestes du parcours. Même forme que `StudyOutbox`, magasin
 * distinct : voir `PathCommandOutboxRepository`.
 */
interface PathCommandOutbox {
    suspend fun current(): PathOutboxState
    suspend fun forget(commandIds: Set<String>): Boolean
    suspend fun bury(rejected: List<DeadPathCommand>): Boolean
}

/** Idem pour la copie locale de la file. */
interface QueueCache {
    suspend fun current(): CachedQueue
    suspend fun store(snapshot: QueueSnapshot, etag: String?, atMillis: Long)
    suspend fun touch(atMillis: Long)
    suspend fun clear()
}

data class SyncState(
    val enrolled: Boolean = false,
    val inFlight: Boolean = false,
    /**
     * Vrai après un `401`. Le téléphone **cesse** alors ses tentatives et
     * attend un ré-enrôlement (contrat §2). Réessayer en boucle contre un
     * jeton révoqué ne le fera pas revivre ; ça ne ferait que masquer la
     * seule information utile : il faut refaire l'enrôlement.
     */
    val halted: Boolean = false,
    val consecutiveFailures: Int = 0,
    val nextAttemptAtMillis: Long? = null,
    val lastSuccessAtMillis: Long? = null,
    val lastError: String? = null,
)

sealed interface SyncOutcome {
    data object NotEnrolled : SyncOutcome
    data object Halted : SyncOutcome
    data class Deferred(val untilMillis: Long) : SyncOutcome
    data class Done(val settled: Int, val buried: Int, val queueRefreshed: Boolean) : SyncOutcome
    data class Failed(val reason: String) : SyncOutcome
}

sealed interface EnrolOutcome {
    data class Enrolled(val deviceId: String) : EnrolOutcome
    data object InvalidUrl : EnrolOutcome
    data object InvalidToken : EnrolOutcome
    /** Le serveur a répondu, et il a dit non. Le jeton est encore à l'écran : c'est le bon moment. */
    data object Rejected : EnrolOutcome
    data object ServerTooOld : EnrolOutcome
    data class Unreachable(val reason: String) : EnrolOutcome
}

/**
 * Le moteur d'envoi.
 *
 * Une seule règle gouverne tout ce fichier : **une trace ne disparaît que
 * lorsque le serveur a dit qu'il l'avait.** Tout le reste — le dos
 * exponentiel, le découpage des lots, l'arrêt sur `401` — n'existe que pour
 * ne jamais avoir à violer cette règle.
 *
 * Pas de `WorkManager` en tranche 1 : l'utilisateur déclare *dans*
 * l'application, elle est donc ouverte au moment qui compte. L'envoi est
 * déclenché à l'ouverture, après chaque déclaration, et par un bouton de
 * reprise manuel.
 */
class SyncEngine(
    private val outbox: StudyOutbox,
    private val queueCache: QueueCache,
    private val credentialStore: DeviceCredentialStore,
    private val transport: SyncTransport,
    private val pathOutbox: PathCommandOutbox? = null,
    private val agendaCache: AgendaCache? = null,
    private val agendaStore: AgendaStore? = null,
    private val timeSource: TimeSource = SystemTimeSource,
    private val logger: SyncLogger = SystemSyncLogger,
) {
    private val mutex = Mutex()

    // L'état d'enrôlement est connu dès la construction, sans déchiffrement ni
    // réseau : l'écran doit pouvoir se décider à la première image, sinon un
    // appareil enrôlé affiche brièvement le formulaire d'enrôlement.
    private val _state = MutableStateFlow(SyncState(enrolled = credentialStore.hasCredentials()))
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * Enrôle l'appareil.
     *
     * Rien n'est enregistré avant qu'une vraie requête ait abouti. Un jeton
     * mal recopié échoue donc **pendant qu'il est encore affiché à l'écran**,
     * au lieu de produire des `401` inexplicables trois jours plus tard.
     */
    suspend fun enrol(baseUrlInput: String, tokenInput: String): EnrolOutcome = mutex.withLock {
        val baseUrl = normalizeBaseUrl(baseUrlInput) ?: return@withLock EnrolOutcome.InvalidUrl
        val token = normalizeDeviceToken(tokenInput) ?: return@withLock EnrolOutcome.InvalidToken

        val candidate = DeviceCredentials(baseUrl = baseUrl, token = token)
        when (val fetch = transport.fetchQueue(candidate, etag = null)) {
            is QueueFetch.Fresh -> {
                val deviceId = fetch.deviceId ?: return@withLock EnrolOutcome.ServerTooOld
                val stored = credentialStore.write(candidate.copy(deviceId = deviceId))
                if (!stored) {
                    return@withLock EnrolOutcome.Unreachable("Jeton non enregistrable sur l'appareil")
                }
                queueCache.store(fetch.snapshot, fetch.etag, timeSource.nowMillis())
                refreshAgenda(candidate)
                _state.value = SyncState(enrolled = true, lastSuccessAtMillis = timeSource.nowMillis())
                logger.add(SyncLogger.TAG_SYNC, "Appareil enrôlé : $deviceId")
                EnrolOutcome.Enrolled(deviceId)
            }

            QueueFetch.NotModified -> EnrolOutcome.ServerTooOld
            QueueFetch.Unauthorized -> EnrolOutcome.Rejected
            is QueueFetch.Failed -> EnrolOutcome.Unreachable(fetch.reason)
        }
    }

    /** Désenrôle. La file d'attente n'est **pas** touchée : elle repartira. */
    suspend fun forgetDevice() = mutex.withLock {
        credentialStore.clear()
        queueCache.clear()
        agendaCache?.clear()
        // La copie modifiable part avec le reste : elle n'appartient pas à
        // l'appareil, elle appartient au compte qu'on vient d'oublier.
        agendaStore?.clear()
        _state.value = SyncState()
    }

    suspend fun sync(force: Boolean = false): SyncOutcome = mutex.withLock {
        val credentials = credentialStore.read()
        val deviceId = credentials?.deviceId
        if (credentials == null || deviceId == null) {
            _state.value = _state.value.copy(enrolled = false)
            return@withLock SyncOutcome.NotEnrolled
        }
        if (_state.value.halted && !force) return@withLock SyncOutcome.Halted

        // Le dos exponentiel protège l'envoi des traces. Il ne doit pas
        // empêcher la lecture d'un agenda dont la fraîcheur a une gravité
        // propre (contrat §15). Cet appel garde son ETag et reste sans effet
        // sur l'outbox en cas de panne.
        refreshAgenda(credentials) ?: return@withLock halt()
        exchangeAgenda(credentials, deviceId) ?: return@withLock halt()

        val now = timeSource.nowMillis()
        val nextAttempt = _state.value.nextAttemptAtMillis
        if (!force && nextAttempt != null && now < nextAttempt) {
            return@withLock SyncOutcome.Deferred(nextAttempt)
        }

        _state.value = _state.value.copy(enrolled = true, inFlight = true, halted = false)
        try {
            runSync(credentials, deviceId)
        } finally {
            _state.value = _state.value.copy(inFlight = false)
        }
    }

    private suspend fun runSync(credentials: DeviceCredentials, deviceId: String): SyncOutcome {
        // Les gestes du parcours montent en premier, et l'ordre a une raison
        // précise : `refreshQueue` conclut ce passage, et la file redescendue
        // doit déjà les porter. Envoyés après, ils ne se verraient qu'au
        // passage suivant — la coche resterait grise une synchronisation de
        // trop.
        pushPathCommands(credentials, deviceId) ?: return halt()

        val pending = outbox.current()
        if (!pending.storageHealthy) {
            // On ignore ce qui attend : envoyer à l'aveugle risquerait de
            // marquer comme parties des traces qu'on n'a jamais lues.
            return recordFailure("File d'attente illisible")
        }
        if (pending.overCapacity) {
            logger.add(
                SyncLogger.TAG_ERROR,
                "File d'attente à ${pending.pendingCount} entrées : les envois n'aboutissent plus.",
            )
        }

        var remaining = pending.pending
        var batchSize = MAX_BATCH
        var settled = 0
        var buried = 0

        while (remaining.isNotEmpty()) {
            val batch = remaining.take(batchSize)

            when (val delivery = transport.sendEvents(credentials, deviceId, batch)) {
                is EventDelivery.Answered -> {
                    val verdicts = delivery.results.associateBy { it.eventId }

                    val done = batch.filter { verdicts[it.eventId]?.isSettled == true }
                    val rejected = batch.mapNotNull { event ->
                        val verdict = verdicts[event.eventId] ?: return@mapNotNull null
                        if (!verdict.isRejected) return@mapNotNull null
                        DeadEvent(event, verdict.reason ?: "Refus sans motif")
                    }

                    outbox.forget(done.mapTo(mutableSetOf()) { it.eventId })
                    outbox.bury(rejected)
                    settled += done.size
                    buried += rejected.size

                    // Les événements absents de la réponse restent en file :
                    // on ne les considère envoyés que sur verdict explicite.
                    remaining = remaining.drop(batch.size)
                }

                EventDelivery.Unauthorized -> return halt()

                EventDelivery.TooLarge -> {
                    if (batchSize > 1) {
                        batchSize = maxOf(1, batchSize / 2)
                        continue
                    }
                    // Un seul événement, et il est encore trop gros : le
                    // renvoyer donnerait éternellement la même réponse. Il
                    // part en file morte, visible, jamais effacé.
                    val alone = batch.first()
                    outbox.bury(listOf(DeadEvent(alone, "Événement trop volumineux pour le serveur")))
                    buried += 1
                    remaining = remaining.drop(1)
                }

                is EventDelivery.Failed -> {
                    // Jamais d'enterrement sur une panne : une trace ne meurt
                    // que sur un refus explicite du serveur.
                    return recordFailure(delivery.reason)
                }
            }
        }

        val queueRefreshed = refreshQueue(credentials) ?: return halt()

        val now = timeSource.nowMillis()
        _state.value = _state.value.copy(
            consecutiveFailures = 0,
            nextAttemptAtMillis = null,
            lastSuccessAtMillis = now,
            lastError = null,
        )
        return SyncOutcome.Done(settled = settled, buried = buried, queueRefreshed = queueRefreshed)
    }

    /**
     * Monte les gestes du parcours. `null` signale un `401`.
     *
     * Une panne ne fait rien perdre et n'interrompt rien : les trois gestes
     * sont idempotents, ils restent en file et repartiront entiers au passage
     * suivant. Les traces, elles, sont irremplaçables — elles ne doivent pas
     * rester au sol parce qu'une coche n'est pas passée.
     */
    private suspend fun pushPathCommands(
        credentials: DeviceCredentials,
        deviceId: String,
    ): Boolean? {
        val store = pathOutbox ?: return false
        val state = store.current()
        if (!state.storageHealthy) {
            // Envoyer à l'aveugle risquerait de marquer comme partis des gestes
            // qu'on n'a jamais lus.
            logger.add(SyncLogger.TAG_ERROR, "File des gestes illisible : envoi suspendu.")
            return false
        }

        var remaining = state.pending
        var batchSize = MAX_PATH_BATCH
        while (remaining.isNotEmpty()) {
            val batch = remaining.take(batchSize)

            when (val delivery = transport.sendPathCommands(credentials, deviceId, batch)) {
                is PathCommandDelivery.Answered -> {
                    val verdicts = delivery.results.associateBy { it.commandId }

                    val done = batch.filter { verdicts[it.commandId]?.isSettled == true }
                    val rejected = batch.mapNotNull { command ->
                        val verdict = verdicts[command.commandId] ?: return@mapNotNull null
                        if (!verdict.isRejected) return@mapNotNull null
                        DeadPathCommand(command, verdict.reason ?: "Refus sans motif")
                    }

                    store.forget(done.mapTo(mutableSetOf()) { it.commandId })
                    store.bury(rejected)

                    // Les commandes absentes de la réponse restent en file : on
                    // ne les tient pour parties que sur verdict explicite.
                    remaining = remaining.drop(batch.size)
                }

                PathCommandDelivery.Unauthorized -> return null

                PathCommandDelivery.TooLarge -> {
                    if (batchSize > 1) {
                        batchSize = maxOf(1, batchSize / 2)
                        continue
                    }
                    // Un seul geste, et il est encore trop gros : le renvoyer
                    // donnerait éternellement la même réponse. Un
                    // réordonnancement de plusieurs milliers d'étapes est le
                    // seul cas plausible.
                    val alone = batch.first()
                    store.bury(listOf(DeadPathCommand(alone, "Geste trop volumineux pour le serveur")))
                    remaining = remaining.drop(1)
                }

                is PathCommandDelivery.Failed -> {
                    logger.add(SyncLogger.TAG_SYNC, "Gestes de parcours non envoyés : ${delivery.reason}")
                    return false
                }
            }
        }

        return true
    }

    /** `null` signale un `401` ; l'appelant s'arrête. */
    private suspend fun refreshQueue(credentials: DeviceCredentials): Boolean? {
        val cached = queueCache.current()
        return when (val fetch = transport.fetchQueue(credentials, cached.etag)) {
            is QueueFetch.Fresh -> {
                queueCache.store(fetch.snapshot, fetch.etag, timeSource.nowMillis())
                fetch.deviceId?.let(credentialStore::rememberDeviceId)
                if (fetch.snapshot.skipped > 0) {
                    logger.add(
                        SyncLogger.TAG_ERROR,
                        "${fetch.snapshot.skipped} étape(s) de la file illisibles et ignorées.",
                    )
                }
                true
            }

            QueueFetch.NotModified -> {
                queueCache.touch(timeSource.nowMillis())
                false
            }

            QueueFetch.Unauthorized -> null

            is QueueFetch.Failed -> {
                // La file est du confort : son échec ne compromet aucune trace.
                logger.add(SyncLogger.TAG_SYNC, "File non rafraîchie : ${fetch.reason}")
                false
            }
        }
    }

    /**
     * Rafraîchit le cache temporel indépendamment de la file académique.
     * `null` conserve la signification commune d'un jeton refusé.
     */
    private suspend fun refreshAgenda(credentials: DeviceCredentials): Boolean? {
        val cache = agendaCache ?: return false
        val cached = cache.current()
        return when (val fetch = transport.fetchAgenda(credentials, cached.etag)) {
            is AgendaFetch.Fresh -> {
                cache.store(fetch.snapshot, fetch.etag, timeSource.nowMillis())
                val issues = fetch.snapshot.skippedWindowItems + fetch.snapshot.malformedFields
                if (issues > 0) {
                    logger.add(
                        SyncLogger.TAG_ERROR,
                        "$issues élément(s) de l'agenda illisible(s) et signalé(s).",
                    )
                }
                true
            }

            AgendaFetch.NotModified -> {
                cache.touch(timeSource.nowMillis())
                false
            }

            AgendaFetch.Unauthorized -> null

            is AgendaFetch.Failed -> {
                // L'agenda en cache reste affichable : une panne ne doit ni
                // effacer cette copie, ni compromettre l'envoi des traces.
                logger.add(SyncLogger.TAG_SYNC, "Agenda non rafraîchi : ${fetch.reason}")
                false
            }
        }
    }

    /**
     * L'échange à deux sens de l'agenda — amendement V1.4.
     *
     * Il monte d'abord, il descend ensuite, et l'ordre compte : descendre en
     * premier ferait arbitrer les modifications locales contre une version du
     * serveur qui ne les connaît pas encore, et une correction faite dans
     * l'avion serait écrasée par ce qu'elle corrigeait.
     *
     * Il partage la règle du reste du fichier : **une saisie ne cesse
     * d'attendre que lorsque le serveur a dit qu'il l'avait.** Une panne laisse
     * donc tout en attente, et le prochain passage réessaie.
     *
     * `null` conserve la signification commune d'un jeton refusé.
     */
    private suspend fun exchangeAgenda(
        credentials: DeviceCredentials,
        deviceId: String,
    ): Boolean? {
        val store = agendaStore ?: return false
        val state = store.current()
        if (!state.storageHealthy) {
            // Envoyer à l'aveugle risquerait de marquer comme parties des
            // saisies qu'on n'a jamais lues.
            logger.add(SyncLogger.TAG_ERROR, "Agenda local illisible : échange suspendu.")
            return false
        }

        var remaining = state.pending
        var batchSize = MAX_AGENDA_BATCH
        while (remaining.isNotEmpty()) {
            val batch = remaining.take(batchSize)
            when (val delivery = transport.sendAgendaChanges(credentials, deviceId, batch)) {
                is AgendaDelivery.Answered -> {
                    val verdicts = delivery.results.associateBy { it.id }
                    val settled = batch
                        .filter { verdicts[it.id]?.isSettled == true }
                        .mapTo(mutableSetOf()) { it.id }
                    store.settle(settled)

                    delivery.results.filter { it.isRejected }.forEach { verdict ->
                        // Un refus de structure ne se réessaie pas : il
                        // donnerait éternellement la même réponse. Il ne
                        // disparaît pas non plus — l'entrée reste en attente et
                        // l'écran la montre encore, avec ce motif au journal.
                        logger.add(
                            SyncLogger.TAG_ERROR,
                            "Entrée d'agenda refusée (${verdict.id}) : " +
                                (verdict.reason ?: "refus sans motif"),
                        )
                    }
                    remaining = remaining.drop(batch.size)
                }

                AgendaDelivery.Unauthorized -> return null

                AgendaDelivery.TooLarge -> {
                    if (batchSize > 1) {
                        batchSize = maxOf(1, batchSize / 2)
                        continue
                    }
                    logger.add(
                        SyncLogger.TAG_ERROR,
                        "Entrée d'agenda trop volumineuse pour le serveur : envoi suspendu.",
                    )
                    return false
                }

                is AgendaDelivery.Failed -> {
                    logger.add(SyncLogger.TAG_SYNC, "Agenda non envoyé : ${delivery.reason}")
                    return false
                }
            }
        }

        var cursor = state.cursor
        var pages = 0
        while (pages < MAX_AGENDA_PAGES) {
            when (val delta = transport.fetchAgendaChanges(credentials, cursor)) {
                is AgendaDelta.Fresh -> {
                    if (delta.skipped > 0) {
                        logger.add(
                            SyncLogger.TAG_ERROR,
                            "${delta.skipped} entrée(s) d'agenda illisible(s) et ignorée(s).",
                        )
                    }
                    store.applyRemote(delta.entries, delta.cursor)
                    // Sans curseur qui avance, redemander donnerait la même
                    // page indéfiniment : mieux vaut s'arrêter là et reprendre
                    // au prochain passage.
                    if (!delta.hasMore || delta.cursor == null || delta.cursor == cursor) break
                    cursor = delta.cursor
                    pages += 1
                }

                AgendaDelta.Unauthorized -> return null

                is AgendaDelta.Failed -> {
                    logger.add(SyncLogger.TAG_SYNC, "Agenda non descendu : ${delta.reason}")
                    return false
                }
            }
        }

        return true
    }

    private fun halt(): SyncOutcome {
        logger.add(
            SyncLogger.TAG_ERROR,
            "Jeton refusé par le serveur. Envois arrêtés : ré-enrôlement nécessaire.",
        )
        _state.value = _state.value.copy(
            halted = true,
            lastError = "Jeton refusé",
            nextAttemptAtMillis = null,
        )
        return SyncOutcome.Halted
    }

    private fun recordFailure(reason: String): SyncOutcome {
        val failures = _state.value.consecutiveFailures + 1
        val wait = minOf(BASE_BACKOFF_MILLIS shl minOf(failures - 1, MAX_BACKOFF_SHIFT), MAX_BACKOFF_MILLIS)
        _state.value = _state.value.copy(
            consecutiveFailures = failures,
            nextAttemptAtMillis = timeSource.nowMillis() + wait,
            lastError = reason,
        )
        logger.add(SyncLogger.TAG_SYNC, "Envoi différé après échec ($reason), tentative $failures")
        return SyncOutcome.Failed(reason)
    }

    private companion object {
        /** Aligné sur la limite du serveur (contrat §5). */
        const val MAX_BATCH = 100

        /** Aligné sur `MAX_AGENDA_CHANGES_PER_BATCH` du serveur. */
        const val MAX_AGENDA_BATCH = 50

        /** Aligné sur `MAX_PATH_COMMANDS_PER_BATCH` du serveur. */
        const val MAX_PATH_BATCH = 100

        /**
         * Bornes du balayage descendant. Un serveur qui rendrait sans cesse
         * `has_more` ferait tourner la boucle sans fin ; ce plafond la coupe, et
         * le curseur enregistré fait reprendre le passage suivant là où il en
         * était.
         */
        const val MAX_AGENDA_PAGES = 20
        const val BASE_BACKOFF_MILLIS = 15_000L
        const val MAX_BACKOFF_MILLIS = 900_000L
        const val MAX_BACKOFF_SHIFT = 6
    }
}
