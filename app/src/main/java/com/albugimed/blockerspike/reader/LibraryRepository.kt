package com.albugimed.blockerspike.reader

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.albugimed.blockerspike.sync.SyncLogger
import com.albugimed.blockerspike.sync.SystemSyncLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Sixieme magasin. Comme le cinquieme, **rien de ce qu'il contient ne part sur
 * le reseau**.
 *
 * DataStore et non Room : quelques dizaines de dossiers et de livres, ecrits a
 * la main, un a la fois. Ce n'est pas une file d'attente, c'est une etagere.
 *
 * Il est separe du magasin des positions alors que les deux parlent des memes
 * documents, et c'est deliberé : la position est un fait produit par la
 * lecture, le rangement est une decision prise par l'utilisateur. Effacer un
 * rangement ne doit jamais pouvoir effacer une page memorisee par ricochet.
 */
private val Context.libraryStore by preferencesDataStore(name = "reading_library")

class LibraryRepository(
    private val context: Context,
    private val logger: SyncLogger = SystemSyncLogger,
) {
    private fun folderKey(id: String) = stringPreferencesKey("$FOLDER_PREFIX$id")

    private fun bookKey(id: String) = stringPreferencesKey("$BOOK_PREFIX$id")

    val library: Flow<Library> = context.libraryStore.data
        .map { prefs -> read(prefs) }
        .catch { error ->
            logger.add(
                SyncLogger.TAG_ERROR,
                "Bibliotheque illisible : ${error.javaClass.simpleName}",
            )
            // Une bibliotheque vide, jamais un rangement invente : un livre
            // pose au mauvais endroit se cherche plus longtemps qu'un livre
            // qu'on sait ne pas etre range.
            emit(Library())
        }

    suspend fun snapshot(): Library = library.first()

    // ---------------------------------------------------------------- dossiers

    /** Cree un dossier et renvoie son identifiant, ou `null` si l'ecriture echoue. */
    suspend fun createFolder(name: String, parentId: String?): String? {
        val id = "fold_${UUID.randomUUID()}"
        val ok = write { prefs ->
            prefs[folderKey(id)] = LibraryJson.encodeFolder(
                LibraryFolder(id = id, name = name.cleanName("Dossier"), parentId = parentId),
            )
        }
        return if (ok) id else null
    }

    suspend fun renameFolder(id: String, name: String): Boolean {
        val existant = snapshot().folder(id) ?: return false
        return write { prefs ->
            prefs[folderKey(id)] =
                LibraryJson.encodeFolder(existant.copy(name = name.cleanName(existant.name)))
        }
    }

    /**
     * Deplace un dossier. Refuse de le poser dans lui-meme ou dans l'un de ses
     * enfants : la branche entiere se detacherait de la racine et deviendrait
     * inatteignable, sans qu'aucun message ne l'explique.
     */
    suspend fun moveFolder(id: String, parentId: String?): Boolean {
        val etat = snapshot()
        val existant = etat.folder(id) ?: return false
        if (parentId != null && parentId in etat.subtree(id)) return false
        return write { prefs ->
            prefs[folderKey(id)] = LibraryJson.encodeFolder(existant.copy(parentId = parentId))
        }
    }

    /**
     * Supprime un dossier **sans rien supprimer de ce qu'il contenait**.
     *
     * Ses sous-dossiers et ses livres remontent d'un cran. Supprimer un
     * rangement est un geste courant ; perdre trente livres parce qu'on a
     * range un dossier ne l'est pas, et aucun avertissement ne repare une
     * suppression qu'on n'attendait pas.
     */
    suspend fun deleteFolder(id: String): Boolean {
        val etat = snapshot()
        val existant = etat.folder(id) ?: return false
        val parent = existant.parentId
        return write { prefs ->
            etat.folders.filter { it.parentId == id }.forEach { enfant ->
                prefs[folderKey(enfant.id)] =
                    LibraryJson.encodeFolder(enfant.copy(parentId = parent))
            }
            etat.books.filter { it.folderId == id }.forEach { livre ->
                prefs[bookKey(livre.id)] = LibraryJson.encodeBook(livre.copy(folderId = parent))
            }
            prefs.remove(folderKey(id))
        }
    }

    // ------------------------------------------------------------------ livres

    /** Cree un livre et renvoie son identifiant, ou `null` si l'ecriture echoue. */
    suspend fun createBook(
        title: String,
        folderId: String?,
        files: List<LibraryBookFile>,
    ): String? {
        val id = "book_${UUID.randomUUID()}"
        val ok = write { prefs ->
            prefs[bookKey(id)] = LibraryJson.encodeBook(
                LibraryBook(
                    id = id,
                    title = title.cleanName("Sans titre"),
                    folderId = folderId,
                    files = files,
                ),
            )
        }
        return if (ok) id else null
    }

    suspend fun renameBook(id: String, title: String): Boolean =
        update(id) { it.copy(title = title.cleanName(it.title)) }

    suspend fun moveBook(id: String, folderId: String?): Boolean =
        update(id) { it.copy(folderId = folderId) }

    suspend fun addFile(id: String, file: LibraryBookFile): Boolean = update(id) { livre ->
        // Rattacher deux fois le meme fichier compterait ses pages deux fois
        // dans la progression du livre.
        if (livre.files.any { it.resourceId == file.resourceId }) {
            livre
        } else {
            livre.copy(files = livre.files + file)
        }
    }

    suspend fun renameFile(id: String, resourceId: String, label: String): Boolean =
        update(id) { livre ->
            livre.copy(
                files = livre.files.map { fichier ->
                    if (fichier.resourceId == resourceId) {
                        fichier.copy(label = label.trim())
                    } else {
                        fichier
                    }
                },
            )
        }

    /**
     * Retire un fichier d'un livre, et oublie sa page.
     *
     * Ici l'oubli est volontaire, contrairement au fichier introuvable que
     * [ReadingPositionRepository] conserve : retirer un fichier est un geste
     * explicite, et laisser la position derriere ferait reapparaitre le
     * document tout seul au prochain balayage.
     */
    suspend fun removeFile(
        id: String,
        resourceId: String,
        positions: ReadingPositionRepository,
    ): Boolean {
        val ok = update(id) { livre ->
            livre.copy(files = livre.files.filterNot { it.resourceId == resourceId })
        }
        if (ok) positions.forget(resourceId)
        return ok
    }

    /** Supprime un livre et les pages memorisees de ses fichiers. */
    suspend fun deleteBook(id: String, positions: ReadingPositionRepository): Boolean {
        val livre = snapshot().books.firstOrNull { it.id == id } ?: return false
        val ok = write { prefs -> prefs.remove(bookKey(id)) }
        if (ok) livre.files.forEach { positions.forget(it.resourceId) }
        return ok
    }

    /**
     * Fait entrer dans la bibliotheque les documents rattaches ailleurs.
     *
     * Un PDF rattache depuis une etape de « A faire » n'est pas passe par
     * l'ajout de livre : il existe comme position, sans titre ni dossier. Sans
     * cette adoption il resterait invisible dans une bibliotheque qui pretend
     * montrer les lectures — le defaut meme que cet ecran corrige.
     *
     * L'operation est idempotente : un document deja porte par un livre n'est
     * jamais adopte deux fois. Un livre supprime ne revient pas non plus,
     * puisque [deleteBook] oublie ses positions.
     */
    suspend fun adoptOrphans(positions: Map<String, ReadingPosition>): Boolean {
        val connus = snapshot().knownResourceIds()
        val orphelins = positions.values.filterNot { it.resourceId in connus }
        if (orphelins.isEmpty()) return false
        return write { prefs ->
            orphelins.forEach { position ->
                val id = "book_${UUID.randomUUID()}"
                prefs[bookKey(id)] = LibraryJson.encodeBook(
                    LibraryBook(
                        id = id,
                        title = bookTitleFromFileName(
                            position.documentLabel.ifBlank { "Document" },
                        ),
                        folderId = null,
                        files = listOf(LibraryBookFile(resourceId = position.resourceId)),
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------ privés

    private suspend fun update(id: String, transform: (LibraryBook) -> LibraryBook): Boolean {
        val existant = snapshot().books.firstOrNull { it.id == id } ?: return false
        return write { prefs -> prefs[bookKey(id)] = LibraryJson.encodeBook(transform(existant)) }
    }

    private fun read(prefs: Preferences): Library {
        val dossiers = mutableListOf<LibraryFolder>()
        val livres = mutableListOf<LibraryBook>()
        prefs.asMap().forEach { (key, value) ->
            val brut = value as? String ?: return@forEach
            when {
                key.name.startsWith(FOLDER_PREFIX) ->
                    LibraryJson.decodeFolder(brut)?.let(dossiers::add)

                key.name.startsWith(BOOK_PREFIX) ->
                    LibraryJson.decodeBook(brut)?.let(livres::add)
            }
        }
        return Library(folders = dossiers, books = livres)
    }

    private suspend fun write(block: (MutablePreferences) -> Unit): Boolean =
        try {
            context.libraryStore.edit(block)
            true
        } catch (error: Exception) {
            logger.add(
                SyncLogger.TAG_ERROR,
                "Ecriture de bibliotheque impossible : ${error.javaClass.simpleName}",
            )
            false
        }

    private companion object {
        const val FOLDER_PREFIX = "folder:"
        const val BOOK_PREFIX = "book:"
    }
}

/** Un nom vide n'est pas un nom : on garde le precedent plutot qu'un blanc. */
private fun String.cleanName(fallback: String): String = trim().ifBlank { fallback }
