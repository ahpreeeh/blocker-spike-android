package com.albugimed.blockerspike.sync

import com.albugimed.blockerspike.capture.Capture
import com.albugimed.blockerspike.capture.CaptureDelivery

/**
 * Toute la surface réseau de l'application, en quatre méthodes.
 *
 * L'interface est étroite exprès. Le §10 du contrat impose de pouvoir dire,
 * à tout moment et sans lire tout le code, **ce que l'application envoie et
 * à qui**. Quatre points de terminaison, un hôte, aucun autre appel : ce
 * fichier est la preuve, et son étroitesse est la garantie.
 *
 * `sendCaptures` a rejoint la liste en V2.1. Elle n'est pas appelée par le
 * moteur d'études mais par `CaptureUploadWorker` : deux files, deux
 * calendriers d'envoi, aucune capture ne peut retarder une trace.
 */
interface SyncTransport {
    suspend fun fetchQueue(credentials: DeviceCredentials, etag: String?): QueueFetch

    suspend fun fetchAgenda(credentials: DeviceCredentials, etag: String?): AgendaFetch

    suspend fun sendEvents(
        credentials: DeviceCredentials,
        deviceId: String,
        events: List<StudyEvent>,
    ): EventDelivery

    suspend fun sendCaptures(
        credentials: DeviceCredentials,
        deviceId: String,
        captures: List<Capture>,
    ): CaptureDelivery
}

sealed interface AgendaFetch {
    data class Fresh(
        val snapshot: AgendaSnapshot,
        val etag: String?,
    ) : AgendaFetch

    data object NotModified : AgendaFetch
    data object Unauthorized : AgendaFetch
    data class Failed(val reason: String, val retryable: Boolean) : AgendaFetch
}

data class EventResult(val eventId: String, val status: String, val reason: String?) {
    /** `duplicate` est un **succès** : c'est la preuve que le rejeu marche. */
    val isSettled: Boolean get() = status == "accepted" || status == "duplicate"
    val isRejected: Boolean get() = status == "rejected"
}

sealed interface QueueFetch {
    data class Fresh(
        val snapshot: QueueSnapshot,
        val etag: String?,
        val deviceId: String?,
    ) : QueueFetch

    data object NotModified : QueueFetch

    /** Jeton absent, inconnu ou révoqué. Ne jamais réessayer en boucle (contrat §2). */
    data object Unauthorized : QueueFetch

    data class Failed(val reason: String, val retryable: Boolean) : QueueFetch
}

sealed interface EventDelivery {
    /** Le serveur a répondu, avec un verdict par événement. */
    data class Answered(val results: List<EventResult>) : EventDelivery

    data object Unauthorized : EventDelivery

    /** Le lot est trop volumineux : le couper en deux, ne rien abandonner. */
    data object TooLarge : EventDelivery

    data class Failed(val reason: String, val retryable: Boolean) : EventDelivery
}
