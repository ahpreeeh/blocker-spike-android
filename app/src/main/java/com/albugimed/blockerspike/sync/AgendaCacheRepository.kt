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
 * Fichier DataStore propre à l'agenda.
 *
 * Il ne partage rien avec la politique de blocage, la file académique ou
 * l'outbox. Une corruption de calendrier ne peut donc ni désactiver un
 * blocage, ni faire perdre une déclaration encore non envoyée.
 */
private val Context.studyAgendaStore by preferencesDataStore(name = "study_agenda")

data class CachedAgenda(
    val snapshot: AgendaSnapshot = AgendaSnapshot.EMPTY,
    val etag: String? = null,
    /** Instant local de réception/validation. `null` signifie « jamais ». */
    val fetchedAtMillis: Long? = null,
    /** Faux si le fichier existe mais ne peut pas être relu. Visible en UI. */
    val storageHealthy: Boolean = true,
)

interface AgendaCache {
    suspend fun current(): CachedAgenda
    suspend fun store(snapshot: AgendaSnapshot, etag: String?, atMillis: Long)
    suspend fun touch(atMillis: Long)
    suspend fun clear()
}

class AgendaCacheRepository(
    private val context: Context,
    private val logger: SyncLogger = SystemSyncLogger,
) : AgendaCache {
    private object Keys {
        val SNAPSHOT = stringPreferencesKey("snapshot")
        val ETAG = stringPreferencesKey("etag")
        val FETCHED_AT = longPreferencesKey("fetched_at")
    }

    val cached: Flow<CachedAgenda> = context.studyAgendaStore.data
        .map { prefs ->
            decodeCachedAgenda(
                encoded = prefs[Keys.SNAPSHOT],
                etag = prefs[Keys.ETAG],
                fetchedAtMillis = prefs[Keys.FETCHED_AT],
            )
        }
        .catch { error ->
            logger.add(
                SyncLogger.TAG_ERROR,
                "Copie locale de l'agenda illisible : ${error.javaClass.simpleName}",
            )
            emit(CachedAgenda(storageHealthy = false))
        }

    override suspend fun current(): CachedAgenda = cached.first()

    override suspend fun store(snapshot: AgendaSnapshot, etag: String?, atMillis: Long) {
        runCatching {
            context.studyAgendaStore.edit { prefs ->
                prefs[Keys.SNAPSHOT] = AgendaSnapshotJson.encode(snapshot)
                // Une vue partielle doit rester visible avec son avertissement,
                // mais son ETag ne doit jamais produire des 304 éternels : une
                // version future du décodeur pourrait savoir relire l'entrée
                // aujourd'hui sautée.
                val reusableEtag = etag.takeIf { snapshot.isCompleteForEtag() }
                if (reusableEtag == null) {
                    prefs.remove(Keys.ETAG)
                } else {
                    prefs[Keys.ETAG] = reusableEtag
                }
                prefs[Keys.FETCHED_AT] = atMillis
            }
        }.onFailure { error ->
            logger.add(
                SyncLogger.TAG_ERROR,
                "Copie locale de l'agenda non enregistrée : ${error.javaClass.simpleName}",
            )
        }
    }

    /** Un `304` prouve que la copie est encore actuelle. */
    override suspend fun touch(atMillis: Long) {
        runCatching {
            context.studyAgendaStore.edit { prefs -> prefs[Keys.FETCHED_AT] = atMillis }
        }.onFailure { error ->
            logger.add(
                SyncLogger.TAG_ERROR,
                "Fraîcheur de l'agenda non enregistrée : ${error.javaClass.simpleName}",
            )
        }
    }

    override suspend fun clear() {
        runCatching { context.studyAgendaStore.edit { it.clear() } }
    }
}

/** Pure pour prouver le comportement de reprise après corruption. */
internal fun decodeCachedAgenda(
    encoded: String?,
    etag: String?,
    fetchedAtMillis: Long?,
): CachedAgenda {
    val decoded = encoded?.let(AgendaSnapshotJson::decode)
    val unreadable = encoded != null && decoded == null
    return CachedAgenda(
        snapshot = decoded ?: AgendaSnapshot.EMPTY,
        // Un ETag sans corps complet provoquerait des 304 éternels. Le
        // supprimer force le prochain GET à réparer ou réinterpréter par 200.
        etag = etag.takeIf { decoded?.isCompleteForEtag() == true },
        fetchedAtMillis = fetchedAtMillis,
        storageHealthy = !unreadable,
    )
}

private fun AgendaSnapshot.isCompleteForEtag(): Boolean =
    skippedWindowItems == 0 && malformedFields == 0
