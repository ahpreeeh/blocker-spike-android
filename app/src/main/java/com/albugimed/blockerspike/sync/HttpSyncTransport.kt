package com.albugimed.blockerspike.sync

import com.albugimed.blockerspike.capture.Capture
import com.albugimed.blockerspike.capture.CaptureDelivery
import com.albugimed.blockerspike.capture.CaptureJson
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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

    override suspend fun sendCaptures(
        credentials: DeviceCredentials,
        deviceId: String,
        captures: List<Capture>,
    ): CaptureDelivery = withContext(dispatcher) {
        val connection = open(credentials, "/api/v1/captures")
            ?: return@withContext CaptureDelivery.Failed(
                "Adresse de serveur inutilisable",
                retryable = false,
            )

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")

            val body = CaptureJson.encodeBatch(deviceId, captures).toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val answer = connection.inputStream.bufferedReader().use { it.readText() }
                    val results = CaptureJson.decodeResults(answer)
                    if (results == null) {
                        // Illisible n'est pas refusé : on garde tout et on
                        // réessaiera. Le rejeu porte le même `capture_id`.
                        CaptureDelivery.Failed("Réponse de verdicts illisible", retryable = true)
                    } else {
                        CaptureDelivery.Answered(results)
                    }
                }

                HttpURLConnection.HTTP_UNAUTHORIZED -> CaptureDelivery.Unauthorized
                HttpURLConnection.HTTP_ENTITY_TOO_LARGE -> CaptureDelivery.TooLarge
                else -> CaptureDelivery.Failed("HTTP $status", retryable = status >= 500)
            }
        } catch (error: IOException) {
            CaptureDelivery.Failed(error.javaClass.simpleName, retryable = true)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun fetchAgendaChanges(
        credentials: DeviceCredentials,
        since: String?,
    ): AgendaDelta = withContext(dispatcher) {
        // Le curseur passe en paramètre d'URL : c'est une heure serveur, pas un
        // secret. Le jeton, lui, reste dans l'en-tête, comme partout ailleurs.
        val query = since?.let { "?since=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
        val connection = open(credentials, "/api/v1/agenda/sync$query")
            ?: return@withContext AgendaDelta.Failed(
                "Adresse de serveur inutilisable",
                retryable = false,
            )

        try {
            connection.requestMethod = "GET"

            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    decodeAgendaDelta(body)
                        ?: AgendaDelta.Failed("Réponse d'agenda illisible", retryable = true)
                }

                HttpURLConnection.HTTP_UNAUTHORIZED -> AgendaDelta.Unauthorized
                else -> AgendaDelta.Failed("HTTP $status", retryable = status >= 500)
            }
        } catch (error: IOException) {
            AgendaDelta.Failed(error.javaClass.simpleName, retryable = true)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun sendAgendaChanges(
        credentials: DeviceCredentials,
        deviceId: String,
        changes: List<AgendaEntry>,
    ): AgendaDelivery = withContext(dispatcher) {
        val connection = open(credentials, "/api/v1/agenda/sync")
            ?: return@withContext AgendaDelivery.Failed(
                "Adresse de serveur inutilisable",
                retryable = false,
            )

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")

            val payload = JSONObject().put("device_id", deviceId)
            val array = JSONArray()
            changes.forEach { array.put(AgendaEntryJson.encodeChange(it)) }
            payload.put("changes", array)

            val body = payload.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val answer = connection.inputStream.bufferedReader().use { it.readText() }
                    val results = decodeAgendaResults(answer)
                    if (results == null) {
                        // Illisible n'est pas refusé : on garde tout et on
                        // réessaiera. Le rejeu porte le même identifiant et le
                        // même `edited_at`, il ne peut rien dupliquer.
                        AgendaDelivery.Failed("Réponse de verdicts illisible", retryable = true)
                    } else {
                        AgendaDelivery.Answered(results)
                    }
                }

                HttpURLConnection.HTTP_UNAUTHORIZED -> AgendaDelivery.Unauthorized
                HttpURLConnection.HTTP_ENTITY_TOO_LARGE -> AgendaDelivery.TooLarge
                else -> AgendaDelivery.Failed("HTTP $status", retryable = status >= 500)
            }
        } catch (error: IOException) {
            AgendaDelivery.Failed(error.javaClass.simpleName, retryable = true)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun sendPathCommands(
        credentials: DeviceCredentials,
        deviceId: String,
        commands: List<PathCommand>,
    ): PathCommandDelivery = withContext(dispatcher) {
        // Même chemin que `fetchQueue`, autre verbe : le parcours se lit et
        // s'écrit au même endroit.
        val connection = open(credentials, "/api/v1/queue")
            ?: return@withContext PathCommandDelivery.Failed(
                "Adresse de serveur inutilisable",
                retryable = false,
            )

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")

            val body = PathCommandJson.encodeBatch(deviceId, commands).toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val answer = connection.inputStream.bufferedReader().use { it.readText() }
                    val results = PathCommandJson.decodeResults(answer)
                    if (results == null) {
                        // Illisible n'est pas refusé : on garde tout et on
                        // réessaiera. Les trois gestes sont idempotents, le
                        // rejeu ne peut rien abîmer.
                        PathCommandDelivery.Failed("Réponse de verdicts illisible", retryable = true)
                    } else {
                        PathCommandDelivery.Answered(results)
                    }
                }

                HttpURLConnection.HTTP_UNAUTHORIZED -> PathCommandDelivery.Unauthorized
                HttpURLConnection.HTTP_ENTITY_TOO_LARGE -> PathCommandDelivery.TooLarge
                else -> PathCommandDelivery.Failed("HTTP $status", retryable = status >= 500)
            }
        } catch (error: IOException) {
            PathCommandDelivery.Failed(error.javaClass.simpleName, retryable = true)
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
