package com.albugimed.blockerspike.sync

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Le seul code de cette application qui ouvre une connexion.
 *
 * `HttpURLConnection` plutôt qu'une bibliothèque : trois points de
 * terminaison, aucun besoin d'intercepteurs, de cache ou de pool. Une
 * dépendance HTTP apporterait ici une surface à auditer sans rien apporter
 * d'utile.
 *
 * Le jeton part dans l'en-tête `Authorization`, **jamais dans une URL** :
 * une URL se retrouve dans les journaux, les référents et l'historique.
 */
class HttpSyncTransport(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 20_000,
) : SyncTransport {

    override suspend fun fetchQueue(
        credentials: DeviceCredentials,
        etag: String?,
    ): QueueFetch = withContext(dispatcher) {
        val connection = open(credentials, "/api/v1/queue") ?: return@withContext QueueFetch.Failed(
            "Adresse de serveur inutilisable",
            retryable = false,
        )

        try {
            connection.requestMethod = "GET"
            etag?.let { connection.setRequestProperty("If-None-Match", it) }

            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val snapshot = QueueSnapshotJson.decode(body)
                        ?: return@withContext QueueFetch.Failed(
                            "Réponse de file illisible",
                            retryable = true,
                        )
                    QueueFetch.Fresh(
                        snapshot = snapshot,
                        etag = connection.getHeaderField("ETag"),
                        deviceId = QueueSnapshotJson.deviceIdOf(body),
                    )
                }

                HttpURLConnection.HTTP_NOT_MODIFIED -> QueueFetch.NotModified
                HttpURLConnection.HTTP_UNAUTHORIZED -> QueueFetch.Unauthorized
                else -> QueueFetch.Failed("HTTP $status", retryable = status >= 500)
            }
        } catch (error: IOException) {
            // Coupure réseau, DNS, TLS : tout cela se réessaie.
            QueueFetch.Failed(error.javaClass.simpleName, retryable = true)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun fetchAgenda(
        credentials: DeviceCredentials,
        etag: String?,
    ): AgendaFetch = withContext(dispatcher) {
        val connection = open(credentials, "/api/v1/agenda")
            ?: return@withContext AgendaFetch.Failed(
                "Adresse de serveur inutilisable",
                retryable = false,
            )

        try {
            connection.requestMethod = "GET"
            etag?.let { connection.setRequestProperty("If-None-Match", it) }

            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val snapshot = AgendaSnapshotJson.decode(body)
                        ?: return@withContext AgendaFetch.Failed(
                            "Réponse d'agenda illisible",
                            retryable = true,
                        )
                    AgendaFetch.Fresh(
                        snapshot = snapshot,
                        etag = connection.getHeaderField("ETag"),
                    )
                }

                HttpURLConnection.HTTP_NOT_MODIFIED -> AgendaFetch.NotModified
                HttpURLConnection.HTTP_UNAUTHORIZED -> AgendaFetch.Unauthorized
                else -> AgendaFetch.Failed("HTTP $status", retryable = status >= 500)
            }
        } catch (error: IOException) {
            AgendaFetch.Failed(error.javaClass.simpleName, retryable = true)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun sendEvents(
        credentials: DeviceCredentials,
        deviceId: String,
        events: List<StudyEvent>,
    ): EventDelivery = withContext(dispatcher) {
        val connection = open(credentials, "/api/v1/events") ?: return@withContext EventDelivery.Failed(
            "Adresse de serveur inutilisable",
            retryable = false,
        )

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")

            val body = StudyEventJson.encodeBatch(deviceId, events).toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val answer = connection.inputStream.bufferedReader().use { it.readText() }
                    val results = StudyEventJson.decodeResults(answer)
                    if (results == null) {
                        // Une réponse illisible n'est pas un refus : on garde
                        // tout et on réessaiera. Le rejeu porte le même
                        // `event_id`, il ne peut rien dupliquer.
                        EventDelivery.Failed("Réponse de verdicts illisible", retryable = true)
                    } else {
                        EventDelivery.Answered(results)
                    }
                }

                HttpURLConnection.HTTP_UNAUTHORIZED -> EventDelivery.Unauthorized
                HttpURLConnection.HTTP_ENTITY_TOO_LARGE -> EventDelivery.TooLarge
                else -> EventDelivery.Failed("HTTP $status", retryable = status >= 500)
            }
        } catch (error: IOException) {
            EventDelivery.Failed(error.javaClass.simpleName, retryable = true)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Ouvre la connexion et refuse tout ce qui n'est pas HTTPS. Un secret ne
     * part pas en clair, quelle qu'ait été la saisie.
     */
    private fun open(credentials: DeviceCredentials, path: String): HttpURLConnection? =
        runCatching {
            val connection = URL(credentials.baseUrl + path).openConnection()
            if (connection !is HttpsURLConnection) return null
            connection.apply {
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                instanceFollowRedirects = false
                setRequestProperty("Authorization", "Bearer ${credentials.token}")
                setRequestProperty("Accept", "application/json")
            }
        }.getOrNull()
}
