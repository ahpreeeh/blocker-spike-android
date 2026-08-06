package com.albugimed.blockerspike.capture

import com.albugimed.blockerspike.sync.SyncLogger
import com.albugimed.blockerspike.sync.SystemSyncLogger
import kotlinx.coroutines.flow.Flow

/**
 * Au-delà de ce seuil on **alerte**, on ne jette pas. Perdre une capture pour
 * protéger un quota serait l'inverse de ce que cette file existe pour
 * garantir.
 */
const val CAPTURE_ALERT_THRESHOLD = 2_000

/**
 * La file d'attente des captures, vue du reste de l'application.
 *
 * La règle d'or est celle du flux d'études : **une capture ne disparaît que
 * lorsque le serveur a dit qu'il l'avait.** Tout le reste — coupure réseau,
 * application tuée, redémarrage — la laisse en place.
 */
class CaptureOutboxRepository(
    private val dao: CaptureDao,
    private val logger: SyncLogger = SystemSyncLogger,
) {
    val pendingCount: Flow<Int> = dao.pendingCount()
    val dead: Flow<List<PendingCaptureRow>> = dao.dead()

    /**
     * Met une capture en file d'attente.
     *
     * Renvoie `false` si l'écriture a échoué. **L'appelant doit le dire :**
     * un partage qui paraît réussi alors que rien n'a été enregistré est le
     * seul scénario où l'on perd réellement quelque chose.
     */
    suspend fun enqueue(capture: Capture): Boolean = try {
        dao.insert(capture.toRow())
        logger.add(SyncLogger.TAG_SYNC, "Capture mise en file d'attente : ${capture.kind}")
        true
    } catch (error: Exception) {
        logger.add(
            SyncLogger.TAG_ERROR,
            "Écriture de la file de captures impossible : ${error.javaClass.simpleName}",
        )
        false
    }

    suspend fun pending(limit: Int = CaptureJson.MAX_PER_BATCH): List<Capture> = try {
        dao.pending(limit).map { it.toCapture() }
    } catch (error: Exception) {
        logger.add(
            SyncLogger.TAG_ERROR,
            "File de captures illisible : ${error.javaClass.simpleName}",
        )
        emptyList()
    }

    suspend fun forget(captureIds: Collection<String>) {
        if (captureIds.isEmpty()) return
        runCatching { dao.forget(captureIds.toList()) }
            .onSuccess {
                logger.add(SyncLogger.TAG_SYNC, "Captures confirmées : ${captureIds.size}")
            }
            .onFailure { error ->
                // Échec sans conséquence : les captures restent en file et
                // seront renvoyées. Le serveur répondra `duplicate`.
                logger.add(
                    SyncLogger.TAG_ERROR,
                    "Nettoyage de la file impossible : ${error.javaClass.simpleName}",
                )
            }
    }

    /**
     * Met de côté une capture que le serveur a refusée. Elle ne repart plus
     * et ne disparaît pas — le refus est une information, pas un déchet.
     */
    suspend fun bury(captureId: String, reason: String) {
        runCatching { dao.bury(captureId, reason) }
            .onSuccess {
                logger.add(SyncLogger.TAG_ERROR, "Capture refusée mise de côté : $reason")
            }
    }

    suspend fun forgetDead(captureId: String) {
        runCatching { dao.forgetDead(captureId) }
    }
}
