package com.albugimed.blockerspike.sync

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * La file d'attente des traces d'activité.
 *
 * **Magasin distinct de `block_policy`, et ce n'est pas négociable.** La
 * politique de blocage bascule sur un état dégradé à la moindre corruption
 * de son magasin ; une file d'attente abîmée ne doit jamais pouvoir
 * désactiver la coercition, ni l'inverse. Deux fichiers, deux destins.
 *
 * L'ordre d'envoi n'a aucune importance (contrat §7.5) : un `Set` suffit, et
 * l'idempotence est portée par l'`event_id` frappé à la saisie.
 */
private val Context.studyOutboxStore by preferencesDataStore(name = "study_outbox")

/**
 * Au-delà de ce seuil, on **alerte**, on ne jette pas. Perdre une trace pour
 * protéger un quota serait exactement l'inverse de ce que cette file
 * existe pour garantir.
 */
const val OUTBOX_ALERT_THRESHOLD = 5_000

data class OutboxState(
    val pending: List<StudyEvent> = emptyList(),
    val dead: List<DeadEvent> = emptyList(),
    /**
     * Entrées présentes en magasin mais impossibles à relire. Elles ne sont
     * **pas** supprimées : elles sont comptées pour qu'un écran puisse le
     * dire. Un compteur non nul est un défaut à examiner, pas un déchet à
     * balayer.
     */
    val unreadable: Int = 0,
    /**
     * Faux = les compteurs ci-dessus ne veulent rien dire. Un écran doit
     * afficher « état illisible », **jamais** « 0 en attente ».
     */
    val storageHealthy: Boolean = true,
) {
    val pendingCount: Int get() = pending.size
    val overCapacity: Boolean get() = pending.size >= OUTBOX_ALERT_THRESHOLD
}

class StudyOutboxRepository(
    private val context: Context,
    private val logger: SyncLogger = SystemSyncLogger,
) : StudyOutbox {
    private object Keys {
        val PENDING = stringSetPreferencesKey("pending_events")
        val DEAD = stringSetPreferencesKey("dead_events")
    }

    val state: Flow<OutboxState> = context.studyOutboxStore.data
        .map { prefs ->
            val rawPending = prefs[Keys.PENDING] ?: emptySet()
            val pending = rawPending.mapNotNull(StudyEventJson::decode)
            val dead = (prefs[Keys.DEAD] ?: emptySet()).mapNotNull(StudyEventJson::decodeDead)

            OutboxState(
                pending = pending,
                dead = dead,
                unreadable = rawPending.size - pending.size,
                storageHealthy = true,
            )
        }
        .catch { error ->
            logger.add(
                SyncLogger.TAG_ERROR,
                "File d'attente illisible : ${error.javaClass.simpleName}",
            )
            emit(OutboxState(storageHealthy = false))
        }

    override suspend fun current(): OutboxState = state.first()

    /**
     * Met une trace en file d'attente.
     *
     * Renvoie `false` si l'écriture a échoué. **L'appelant doit le dire à
     * l'utilisateur** : une déclaration qui paraît réussie alors que rien
     * n'a été enregistré est le seul scénario où l'on perd réellement du
     * travail, et c'est celui que toute cette mécanique existe pour éviter.
     */
    suspend fun enqueue(event: StudyEvent): Boolean = mutate(
        "Trace mise en file d'attente : ${event.type.wireName}",
    ) { prefs ->
        val pending = prefs[Keys.PENDING] ?: emptySet()
        if (pending.size >= OUTBOX_ALERT_THRESHOLD) {
            logger.add(
                SyncLogger.TAG_ERROR,
                "File d'attente au-delà de $OUTBOX_ALERT_THRESHOLD entrées : " +
                    "les envois n'aboutissent plus depuis longtemps.",
            )
        }
        prefs[Keys.PENDING] = pending + StudyEventJson.encodeToString(event)
    }

    /** Retire les événements que le serveur a acceptés — ou déjà connus. */
    override suspend fun forget(eventIds: Set<String>): Boolean {
        if (eventIds.isEmpty()) return true
        return mutate("Traces confirmées par le serveur : ${eventIds.size}") { prefs ->
            prefs[Keys.PENDING] = (prefs[Keys.PENDING] ?: emptySet())
                .filterNot { serialized -> StudyEventJson.decode(serialized)?.eventId in eventIds }
                .toSet()
        }
    }

    /**
     * Déplace des événements refusés vers la file morte. Ils ne repartent
     * plus — le même envoi donnerait le même refus — et ne disparaissent pas.
     */
    override suspend fun bury(rejected: List<DeadEvent>): Boolean {
        if (rejected.isEmpty()) return true
        val ids = rejected.mapTo(mutableSetOf()) { it.event.eventId }
        return mutate("Traces refusées mises de côté : ${rejected.size}") { prefs ->
            prefs[Keys.PENDING] = (prefs[Keys.PENDING] ?: emptySet())
                .filterNot { serialized -> StudyEventJson.decode(serialized)?.eventId in ids }
                .toSet()
            prefs[Keys.DEAD] = (prefs[Keys.DEAD] ?: emptySet()) +
                rejected.map(StudyEventJson::encodeDead)
        }
    }

    /**
     * Oublie **une** trace morte, sur geste explicite. Pas d'effacement en
     * bloc : c'est la dernière copie d'un événement que le serveur a refusé.
     */
    suspend fun forgetDead(eventId: String): Boolean =
        mutate("Trace morte oubliée : $eventId") { prefs ->
            prefs[Keys.DEAD] = (prefs[Keys.DEAD] ?: emptySet())
                .filterNot { serialized -> StudyEventJson.decodeDead(serialized)?.event?.eventId == eventId }
                .toSet()
        }

    private suspend fun mutate(
        successMessage: String,
        transform: (MutablePreferences) -> Unit,
    ): Boolean = try {
        context.studyOutboxStore.edit(transform)
        logger.add(SyncLogger.TAG_SYNC, successMessage)
        true
    } catch (error: Exception) {
        logger.add(
            SyncLogger.TAG_ERROR,
            "Écriture de la file d'attente impossible : ${error.javaClass.simpleName}",
        )
        false
    }
}
