package com.albugimed.blockerspike.sync

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray

/**
 * La copie modifiable de l'agenda — amendement V1.4.
 *
 * Magasin distinct de `study_agenda`, qui garde l'instantané dérivé du serveur.
 * Les deux ne se remplacent pas : l'instantané répond à « qu'est-ce qui
 * arrive ? » et le serveur le calcule ; celui-ci détient la matière, y compris
 * ce que le téléphone vient de saisir et que le serveur ignore encore.
 *
 * Un seul champ contient tout, encodé en tableau JSON, plutôt qu'un ensemble de
 * chaînes comme la file des traces. La raison est concrète : ici une entrée est
 * **remplacée** quand on la corrige, et un ensemble se retrouverait avec deux
 * versions du même identifiant sans moyen de dire laquelle compte.
 */
private val Context.agendaEntriesStore by preferencesDataStore(name = "study_agenda_entries")

data class AgendaStoreState(
    val entries: List<AgendaEntry> = emptyList(),
    /**
     * Heure **serveur** du dernier balayage descendu. `null` = jamais : le
     * prochain balayage prendra tout.
     */
    val cursor: String? = null,
    /** Entrées présentes en magasin mais illisibles. Comptées, jamais jetées. */
    val unreadable: Int = 0,
    /** Faux = les listes ci-dessus ne veulent rien dire. À dire à l'écran. */
    val storageHealthy: Boolean = true,
) {
    /** Ce qui attend d'être accusé par le serveur. */
    val pending: List<AgendaEntry> get() = entries.filter { it.pending }
}

/** Ce que le moteur de synchronisation consomme. Volontairement minuscule. */
interface AgendaStore {
    suspend fun current(): AgendaStoreState

    /** Applique un lot descendu, en arbitrant chaque entrée. */
    suspend fun applyRemote(entries: List<AgendaEntry>, cursor: String?): Boolean

    /** Le serveur a accusé ces versions : elles cessent d'attendre. */
    suspend fun settle(ids: Set<String>): Boolean

    /** Écrit une version locale et la met en attente d'envoi. */
    suspend fun put(entry: AgendaEntry): Boolean

    suspend fun clear()
}

class AgendaStoreRepository(
    private val context: Context,
    private val logger: SyncLogger = SystemSyncLogger,
) : AgendaStore {
    private object Keys {
        val ENTRIES = stringPreferencesKey("entries")
        val CURSOR = stringPreferencesKey("cursor")
    }

    val state: Flow<AgendaStoreState> = context.agendaEntriesStore.data
        .map { prefs -> decodeAgendaStore(prefs[Keys.ENTRIES], prefs[Keys.CURSOR]) }
        .catch { error ->
            logger.add(
                SyncLogger.TAG_ERROR,
                "Agenda local illisible : ${error.javaClass.simpleName}",
            )
            emit(AgendaStoreState(storageHealthy = false))
        }

    override suspend fun current(): AgendaStoreState = state.first()

    override suspend fun applyRemote(entries: List<AgendaEntry>, cursor: String?): Boolean = write(
        transform = { existing ->
            val merged = LinkedHashMap<String, AgendaEntry>()
            existing.forEach { merged[it.id] = it }
            entries.forEach { incoming ->
                merged[incoming.id] = mergeAgendaEntry(merged[incoming.id], incoming)
            }
            merged.values.toList()
        },
        // Le curseur n'avance que dans la même écriture que les entrées :
        // l'enregistrer à part laisserait une fenêtre où il désigne un balayage
        // qui n'a pas été conservé, et les entrées manquantes ne
        // redescendraient jamais.
        also = { prefs -> if (cursor != null) prefs[Keys.CURSOR] = cursor },
    )

    override suspend fun settle(ids: Set<String>): Boolean = write(
        transform = { existing ->
            existing.map { entry ->
                // Seul l'accusé de **cette** version compte. Si l'utilisateur a
                // recorrigé l'entrée pendant l'envoi, `editedAt` a changé et la
                // nouvelle version doit rester en attente.
                if (entry.id in ids && entry.pending) entry.copy(pending = false) else entry
            }
        },
    )

    override suspend fun put(entry: AgendaEntry): Boolean = write(
        transform = { existing ->
            existing.filterNot { it.id == entry.id } + entry.copy(pending = true)
        },
    )

    override suspend fun clear() {
        runCatching { context.agendaEntriesStore.edit { it.clear() } }
    }

    private suspend fun write(
        transform: (List<AgendaEntry>) -> List<AgendaEntry>,
        also: (MutablePreferences) -> Unit = {},
    ): Boolean = runCatching {
        context.agendaEntriesStore.edit { prefs ->
            val existing = decodeAgendaStore(prefs[Keys.ENTRIES], null)
            // Réécrire par-dessus un magasin illisible effacerait ce qu'on n'a
            // pas su relire — y compris des saisies jamais envoyées. On refuse.
            if (!existing.storageHealthy) error("Agenda local illisible")
            val array = JSONArray()
            transform(existing.entries).forEach { array.put(AgendaEntryJson.encodeLocal(it)) }
            prefs[Keys.ENTRIES] = array.toString()
            also(prefs)
        }
        true
    }.onFailure { error ->
        logger.add(
            SyncLogger.TAG_ERROR,
            "Agenda local non enregistré : ${error.javaClass.simpleName}",
        )
    }.getOrDefault(false)
}

/** Pure, pour prouver le comportement de reprise après corruption. */
internal fun decodeAgendaStore(encoded: String?, cursor: String?): AgendaStoreState {
    if (encoded == null) return AgendaStoreState(cursor = cursor)
    val array = runCatching { JSONArray(encoded) }.getOrNull()
        ?: return AgendaStoreState(cursor = cursor, storageHealthy = false)

    val entries = ArrayList<AgendaEntry>(array.length())
    var unreadable = 0
    for (index in 0 until array.length()) {
        val entry = array.optJSONObject(index)
            ?.let { AgendaEntryJson.decodeLocal(it.toString()) }
        if (entry == null) unreadable += 1 else entries += entry
    }
    return AgendaStoreState(entries = entries, cursor = cursor, unreadable = unreadable)
}
