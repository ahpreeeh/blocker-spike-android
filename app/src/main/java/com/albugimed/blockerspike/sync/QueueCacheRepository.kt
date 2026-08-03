package com.albugimed.blockerspike.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * La dernière file reçue, conservée pour être consultable hors ligne.
 *
 * **Encore un magasin distinct.** Cette copie est du confort : elle se
 * reconstruit d'un appel réseau. La file d'attente des traces, elle, ne se
 * reconstruit pas. Les mettre dans le même fichier ferait dépendre
 * l'irremplaçable du jetable.
 */
private val Context.studyQueueStore by preferencesDataStore(name = "study_queue")

data class CachedQueue(
    val snapshot: QueueSnapshot = QueueSnapshot.EMPTY,
    val etag: String? = null,
    /** Quand cette copie a été reçue. `null` = jamais. */
    val fetchedAtMillis: Long? = null,
)

class QueueCacheRepository(
    private val context: Context,
    private val logger: SyncLogger = SystemSyncLogger,
) : QueueCache {
    private object Keys {
        val SNAPSHOT = stringPreferencesKey("snapshot")
        val ETAG = stringPreferencesKey("etag")
        val FETCHED_AT = longPreferencesKey("fetched_at")
    }

    val cached: Flow<CachedQueue> = context.studyQueueStore.data
        .map { prefs ->
            CachedQueue(
                snapshot = prefs[Keys.SNAPSHOT]
                    ?.let(QueueSnapshotJson::decode)
                    ?: QueueSnapshot.EMPTY,
                etag = prefs[Keys.ETAG],
                fetchedAtMillis = prefs[Keys.FETCHED_AT],
            )
        }
        .catch { error ->
            logger.add(
                SyncLogger.TAG_ERROR,
                "Copie locale de la file illisible : ${error.javaClass.simpleName}",
            )
            emit(CachedQueue())
        }

    override suspend fun current(): CachedQueue = cached.first()

    override suspend fun store(snapshot: QueueSnapshot, etag: String?, atMillis: Long) {
        runCatching {
            context.studyQueueStore.edit { prefs ->
                prefs[Keys.SNAPSHOT] = QueueSnapshotJson.encode(snapshot)
                if (etag == null) prefs.remove(Keys.ETAG) else prefs[Keys.ETAG] = etag
                prefs[Keys.FETCHED_AT] = atMillis
            }
        }.onFailure { error ->
            logger.add(
                SyncLogger.TAG_ERROR,
                "Copie locale de la file non enregistrée : ${error.javaClass.simpleName}",
            )
        }
    }

    /** Met à jour la seule date de fraîcheur, quand le serveur répond `304`. */
    override suspend fun touch(atMillis: Long) {
        runCatching {
            context.studyQueueStore.edit { prefs -> prefs[Keys.FETCHED_AT] = atMillis }
        }
    }

    override suspend fun clear() {
        runCatching { context.studyQueueStore.edit { it.clear() } }
    }
}
