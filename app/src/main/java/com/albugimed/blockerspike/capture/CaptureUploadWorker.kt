package com.albugimed.blockerspike.capture

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.albugimed.blockerspike.Graph
import java.util.concurrent.TimeUnit

/**
 * L'envoi des captures, confié à WorkManager.
 *
 * Le flux d'études s'en passait, et la différence tient en une phrase : une
 * trace d'étude est saisie **dans** l'application, donc elle est ouverte au
 * moment qui compte ; **une capture arrive quand l'application est fermée**.
 * Il n'y a aucun moment d'ouverture sur lequel s'appuyer, et un partage suivi
 * d'un balayage de la liste des tâches doit quand même finir par partir.
 *
 * WorkManager n'ouvre aucune connexion : il ordonnance ce travailleur, qui
 * passe par le même `HttpSyncTransport` que le reste (contrat §10 point 2).
 */
class CaptureUploadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val outbox = Graph.captureOutbox
        val credentials = Graph.captureCredentials.read()
            // Pas encore enrôlé : les captures attendent, elles ne se perdent
            // pas. Réessayer en boucle ne servirait à rien.
            ?: return Result.success()
        val deviceId = credentials.deviceId ?: return Result.success()

        val pending = outbox.pending()
        if (pending.isEmpty()) return Result.success()

        return when (val delivery = Graph.captureTransport.sendCaptures(credentials, deviceId, pending)) {
            is CaptureDelivery.Answered -> {
                val settled = delivery.results.filter { it.isSettled }.map { it.captureId }
                outbox.markSent(settled, System.currentTimeMillis())
                for (rejected in delivery.results.filter { it.isRejected }) {
                    outbox.bury(rejected.captureId, rejected.reason ?: "refusée sans motif")
                }
                // Un verdict manquant n'est pas un verdict négatif : la
                // capture reste en file et repartira au prochain passage.
                if (settled.size + delivery.results.count { it.isRejected } < pending.size) {
                    Result.retry()
                } else {
                    Result.success()
                }
            }

            // Jeton absent, inconnu ou révoqué : réessayer en boucle ne le
            // ranimera pas (contrat §2). Les captures restent en file.
            CaptureDelivery.Unauthorized -> Result.success()

            // Le lot passe mal : au prochain tour il sera coupé en deux.
            CaptureDelivery.TooLarge -> {
                if (pending.size > 1) Result.retry() else Result.success()
            }

            is CaptureDelivery.Failed ->
                if (delivery.retryable) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "capture_upload"

        /**
         * Demande un envoi. `KEEP` et non `REPLACE` : deux partages coup sur
         * coup ne doivent pas repousser indéfiniment le premier envoi — le
         * travailleur lit la file entière de toute façon.
         */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<CaptureUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
