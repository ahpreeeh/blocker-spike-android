package com.albugimed.blockerspike.sync

import com.albugimed.blockerspike.capture.Capture
import com.albugimed.blockerspike.capture.CaptureDelivery

/**
 * Toute la surface réseau de l'application, en sept méthodes sur cinq chemins.
 *
 * L'interface est étroite exprès. Le §10 du contrat impose de pouvoir dire,
 * à tout moment et sans lire tout le code, **ce que l'application envoie et
 * à qui**. Cinq chemins, un hôte, aucun autre appel : ce fichier est la
 * preuve, et son étroitesse est la garantie.
 *
 * `sendCaptures` a rejoint la liste en V2.1. Elle n'est pas appelée par le
 * moteur d'études mais par `CaptureUploadWorker` : deux files, deux
 * calendriers d'envoi, aucune capture ne peut retarder une trace.
 *
 * `fetchAgendaChanges` et `sendAgendaChanges` ont rejoint la liste en V1.4.
 * Elles partagent un seul chemin, `/api/v1/agenda/sync`, et ne remplacent pas
 * `fetchAgenda` : celui-ci descend l'instantané **calculé** par le serveur,
 * celles-là la matière brute, dans les deux sens.
 *
 * `sendPathCommands` a rejoint la liste en V2.2, sur le chemin de `fetchQueue`
 * — le parcours se lit et s'écrit au même endroit. Elle n'emporte que trois
 * gestes fermés (cocher, verser, réordonner) : le téléphone ne crée ni ne
 * supprime d'étape.
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

    /** `since` est une heure **serveur**, jamais celle du téléphone. */
    suspend fun fetchAgendaChanges(
        credentials: DeviceCredentials,
        since: String?,
    ): AgendaDelta

    suspend fun sendAgendaChanges(
        credentials: DeviceCredentials,
        deviceId: String,
        changes: List<AgendaEntry>,
    ): AgendaDelivery

    /** Les gestes du parcours, dans l'ordre reçu — l'ordre fait partie du sens. */
    suspend fun sendPathCommands(
        credentials: DeviceCredentials,
        deviceId: String,
        commands: List<PathCommand>,
    ): PathCommandDelivery
}

sealed interface PathCommandDelivery {
    /** Le serveur a répondu, avec un verdict par commande. */
    data class Answered(val results: List<PathCommandResult>) : PathCommandDelivery

    data object Unauthorized : PathCommandDelivery

    /** Le lot est trop volumineux : le couper en deux, ne rien abandonner. */
    data object TooLarge : PathCommandDelivery

    data class Failed(val reason: String, val retryable: Boolean) : PathCommandDelivery
}

sealed interface AgendaDelta {
    data class Fresh(
        val entries: List<AgendaEntry>,
        /** Point de reprise du prochain balayage. */
        val cursor: String?,
        val hasMore: Boolean,
        /** Entrées reçues mais inexploitables. Comptées, jamais tues. */
        val skipped: Int,
    ) : AgendaDelta

    data object Unauthorized : AgendaDelta
    data class Failed(val reason: String, val retryable: Boolean) : AgendaDelta
}

data class AgendaChangeResult(val id: String, val status: String, val reason: String?) {
    /**
     * `superseded` est un **succès**, au même titre que `duplicate` pour une
     * trace : le serveur a bien reçu le changement, il l'a simplement jugé plus
     * ancien que ce qu'il détenait. Le renvoyer donnerait éternellement la même
     * réponse.
     */
    val isSettled: Boolean get() = status == "accepted" || status == "superseded"
    val isRejected: Boolean get() = status == "rejected"
}

sealed interface AgendaDelivery {
    data class Answered(val results: List<AgendaChangeResult>) : AgendaDelivery

    data object Unauthorized : AgendaDelivery

    /** Le lot est trop volumineux : le couper en deux, ne rien abandonner. */
    data object TooLarge : AgendaDelivery

    data class Failed(val reason: String, val retryable: Boolean) : AgendaDelivery
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
