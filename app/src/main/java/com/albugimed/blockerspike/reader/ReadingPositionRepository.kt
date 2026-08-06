package com.albugimed.blockerspike.reader

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.albugimed.blockerspike.sync.SyncLogger
import com.albugimed.blockerspike.sync.SystemSyncLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Cinquième magasin, et séparé comme les quatre autres.
 *
 * DataStore convient ici alors que les captures ont exigé Room : une position
 * pèse une centaine d'octets et il y en a autant que de documents ouverts,
 * pas autant que de gestes. Ce n'est pas une file d'attente, c'est un
 * signet.
 *
 * Ce magasin ne contient **aucune donnée de coercition et rien qui parte sur
 * le réseau**. Il n'est lu que par le lecteur et par l'écran de déclaration.
 */
private val Context.readingPositionStore by preferencesDataStore(name = "reading_positions")

class ReadingPositionRepository(
    private val context: Context,
    private val logger: SyncLogger = SystemSyncLogger,
) {
    /**
     * Indexé par `resourceId` : un document par ressource. Rattacher un
     * second fichier à la même ressource remplace le premier — sans quoi
     * « Reprendre » aurait à demander lequel, et cesserait d'être un geste.
     */
    private fun key(resourceId: String) = stringPreferencesKey("position:$resourceId")

    val positions: Flow<Map<String, ReadingPosition>> = context.readingPositionStore.data
        .map { prefs ->
            prefs.asMap().entries
                .filter { it.key.name.startsWith("position:") }
                .mapNotNull { (_, value) -> ReadingPositionJson.decode(value as? String ?: "") }
                .associateBy { it.resourceId }
        }
        .catch { error ->
            logger.add(
                SyncLogger.TAG_ERROR,
                "Positions de lecture illisibles : ${error.javaClass.simpleName}",
            )
            // Une carte vide, jamais une position inventée : mieux vaut
            // rouvrir page 1 que rouvrir au mauvais endroit en le taisant.
            emit(emptyMap())
        }

    suspend fun position(resourceId: String): ReadingPosition? =
        positions.first()[resourceId]

    /**
     * Enregistre l'avancée. Renvoie `false` si l'écriture a échoué —
     * l'appelant doit le dire plutôt que de laisser croire que le signet est
     * posé.
     */
    suspend fun remember(position: ReadingPosition): Boolean = write(position)

    /**
     * Rattache un document à une ressource. Conserve `declaredThrough` si le
     * **même** fichier était déjà rattaché : rouvrir le sélecteur pour
     * retrouver son PDF ne doit pas faire recompter les pages déjà déclarées.
     * Un fichier différent remet le compteur à zéro, ce qui est le sens
     * ordinaire de « ce n'est pas le même document ».
     */
    suspend fun attach(
        resourceId: String,
        documentUri: String,
        documentLabel: String,
        pageCount: Int,
        nowMillis: Long,
    ): Boolean {
        val existing = position(resourceId)
        val sameDocument = existing?.documentUri == documentUri
        return write(
            ReadingPosition(
                resourceId = resourceId,
                documentUri = documentUri,
                documentLabel = documentLabel,
                page = if (sameDocument) existing.page else 1,
                declaredThrough = if (sameDocument) existing.declaredThrough else null,
                pageCount = pageCount,
                updatedAtMillis = nowMillis,
            )
        )
    }

    /** Après une déclaration : le compte repartira de la page suivante. */
    suspend fun markDeclaredThrough(resourceId: String, page: Int, nowMillis: Long): Boolean {
        val existing = position(resourceId) ?: return false
        return write(
            existing.copy(
                // `maxOf` : déclarer une plage plus ancienne que la précédente
                // ne doit pas faire reculer le point de départ, sinon la
                // prochaine proposition recompterait des pages déjà comptées.
                declaredThrough = maxOf(existing.declaredThrough ?: 0, page),
                updatedAtMillis = nowMillis,
            )
        )
    }

    /**
     * Oublie le rattachement d'une ressource. Geste explicite uniquement :
     * un fichier introuvable **n'est pas** effacé automatiquement — il peut
     * revenir (carte SD remise, dossier restauré), et l'oubli silencieux
     * ferait disparaître le compte des pages déjà déclarées.
     */
    suspend fun forget(resourceId: String): Boolean = try {
        context.readingPositionStore.edit { it.remove(key(resourceId)) }
        true
    } catch (error: Exception) {
        logger.add(
            SyncLogger.TAG_ERROR,
            "Oubli de position impossible : ${error.javaClass.simpleName}",
        )
        false
    }

    private suspend fun write(position: ReadingPosition): Boolean = try {
        context.readingPositionStore.edit { prefs ->
            prefs[key(position.resourceId)] = ReadingPositionJson.encode(position)
        }
        true
    } catch (error: Exception) {
        logger.add(
            SyncLogger.TAG_ERROR,
            "Écriture de position impossible : ${error.javaClass.simpleName}",
        )
        false
    }
}
