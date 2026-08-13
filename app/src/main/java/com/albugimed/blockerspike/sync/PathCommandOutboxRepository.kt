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
 * La file d'attente des gestes du parcours.
 *
 * **Septième magasin, distinct de `study_outbox`, et pour la même raison que
 * celui-ci l'est de `block_policy`** : une file abîmée ne doit jamais pouvoir
 * en emporter une autre. Ici s'ajoute une raison propre — une trace d'activité
 * est irremplaçable, un ordre de parcours se refait d'un geste. Les mélanger
 * ferait porter à la seconde la prudence que mérite la première, et
 * inversement.
 *
 * Contrairement aux traces, **l'ordre compte** : verser une matière puis
 * réordonner n'a pas le même effet dans l'autre sens. Le magasin garde donc une
 * liste JSON, pas un `Set`.
 */
private val Context.pathCommandStore by preferencesDataStore(name = "path_command_outbox")

/**
 * Au-delà, on **alerte**, on ne jette pas. Le repli ci-dessous garde la file
 * naturellement courte : ce seuil ne se franchit que si plus rien ne part.
 */
const val PATH_OUTBOX_ALERT_THRESHOLD = 500

data class PathOutboxState(
    val pending: List<PathCommand> = emptyList(),
    val dead: List<DeadPathCommand> = emptyList(),
    /** Entrées présentes mais illisibles. Comptées, jamais balayées. */
    val unreadable: Int = 0,
    /** Faux = les compteurs ne veulent rien dire ; ne pas afficher « 0 ». */
    val storageHealthy: Boolean = true,
) {
    val pendingCount: Int get() = pending.size
}

/**
 * Le repli : un geste plus récent remplace celui qu'il annule.
 *
 * Cocher puis décocher la même étape ne doit pas envoyer deux commandes qui se
 * contredisent, et deux réordonnancements successifs rejoués dans le désordre
 * remettraient l'ancien ordre. La règle est donc **le dernier geste gagne**,
 * appliquée à l'entrée de la file plutôt qu'à la sortie.
 */
internal fun collapsePathCommands(
    pending: List<PathCommand>,
    incoming: PathCommand,
): List<PathCommand> {
    val kept = pending.filterNot { existing ->
        when (incoming) {
            is PathCommand.CompleteStep ->
                existing is PathCommand.CompleteStep && existing.stepId == incoming.stepId

            is PathCommand.PourSubject ->
                existing is PathCommand.PourSubject &&
                    existing.nodeId == incoming.nodeId

            // Un ordre est absolu : le dernier dit tout, les précédents ne
            // disent plus rien.
            is PathCommand.ReorderPath -> existing is PathCommand.ReorderPath
        }
    }
    return kept + incoming
}

/**
 * Réinjecte les commandes comprises sans déplacer les entrées d'une version
 * future que cette version de l'application ne sait pas encore décoder.
 * Les commandes remplacées par [collapsePathCommands] disparaissent à leur
 * ancienne place ; les nouvelles sont ajoutées en queue, dans l'ordre reçu.
 */
internal fun mergeSerializedPathCommands(
    rawPending: List<String>,
    pending: List<PathCommand>,
): List<String> {
    val pendingById = pending.associateBy(PathCommand::commandId)
    val emitted = mutableSetOf<String>()
    return buildList {
        rawPending.forEach { serialized ->
            val decoded = PathCommandJson.decode(serialized)
            if (decoded == null) {
                add(serialized)
            } else {
                pendingById[decoded.commandId]?.let { surviving ->
                    if (emitted.add(surviving.commandId)) {
                        add(PathCommandJson.encodeToString(surviving))
                    }
                }
            }
        }
        pending.forEach { command ->
            if (emitted.add(command.commandId)) {
                add(PathCommandJson.encodeToString(command))
            }
        }
    }
}

class PathCommandOutboxRepository(
    private val context: Context,
    private val logger: SyncLogger = SystemSyncLogger,
) : PathCommandOutbox {
    private object Keys {
        val PENDING = stringPreferencesKey("pending_commands")
        val DEAD = stringPreferencesKey("dead_commands")
    }

    val state: Flow<PathOutboxState> = context.pathCommandStore.data
        .map { prefs ->
            val rawPending = readArray(prefs[Keys.PENDING])
            val pending = rawPending.mapNotNull(PathCommandJson::decode)
            val dead = readArray(prefs[Keys.DEAD]).mapNotNull(PathCommandJson::decodeDead)

            PathOutboxState(
                pending = pending,
                dead = dead,
                unreadable = rawPending.size - pending.size,
                storageHealthy = true,
            )
        }
        .catch { error ->
            logger.add(
                SyncLogger.TAG_ERROR,
                "File des gestes du parcours illisible : ${error.javaClass.simpleName}",
            )
            emit(PathOutboxState(storageHealthy = false))
        }

    override suspend fun current(): PathOutboxState = state.first()

    /**
     * Met un geste en file d'attente.
     *
     * Renvoie `false` si l'écriture a échoué. **L'appelant doit le dire** : un
     * geste qui paraît pris alors que rien n'est enregistré est le seul cas où
     * l'écran ment.
     */
    suspend fun enqueue(command: PathCommand): Boolean = enqueueAll(listOf(command))

    /**
     * Ecrit un lot dans une seule transaction DataStore. Les entrees illisibles
     * preexistantes sont conservees mot pour mot : ajouter un geste ne doit pas
     * reparer le magasin en supprimant ce que l'application ne comprend pas.
     */
    override suspend fun enqueueAll(commands: List<PathCommand>): Boolean {
        if (commands.isEmpty()) return true
        return mutate("Gestes de parcours en attente : ${commands.size}") { prefs ->
            val rawPending = readArray(prefs[Keys.PENDING])
            var pending = rawPending.mapNotNull(PathCommandJson::decode)
            if (pending.size >= PATH_OUTBOX_ALERT_THRESHOLD) {
                logger.add(
                    SyncLogger.TAG_ERROR,
                    "File des gestes au-delà de $PATH_OUTBOX_ALERT_THRESHOLD entrées : " +
                        "les envois n'aboutissent plus depuis longtemps.",
                )
            }
            commands.forEach { command ->
                pending = collapsePathCommands(pending, command)
            }
            prefs[Keys.PENDING] = writeArray(mergeSerializedPathCommands(rawPending, pending))
        }
    }

    /** Retire les gestes que le serveur a appliqués — ou jugés sans effet. */
    override suspend fun forget(commandIds: Set<String>): Boolean {
        if (commandIds.isEmpty()) return true
        return mutate("Gestes confirmés par le serveur : ${commandIds.size}") { prefs ->
            prefs[Keys.PENDING] = writeArray(
                readArray(prefs[Keys.PENDING]).filterNot { serialized ->
                    PathCommandJson.decode(serialized)?.commandId in commandIds
                },
            )
        }
    }

    /**
     * Déplace des gestes refusés vers la file morte. Ils ne repartent plus — le
     * même envoi donnerait le même refus — et ne disparaissent pas.
     */
    override suspend fun bury(rejected: List<DeadPathCommand>): Boolean {
        if (rejected.isEmpty()) return true
        val ids = rejected.mapTo(mutableSetOf()) { it.command.commandId }
        return mutate("Gestes refusés mis de côté : ${rejected.size}") { prefs ->
            prefs[Keys.PENDING] = writeArray(
                readArray(prefs[Keys.PENDING]).filterNot { serialized ->
                    PathCommandJson.decode(serialized)?.commandId in ids
                },
            )
            prefs[Keys.DEAD] = writeArray(
                readArray(prefs[Keys.DEAD]) + rejected.map(PathCommandJson::encodeDead),
            )
        }
    }

    /** Oublie **un** geste mort, sur geste explicite. Jamais d'effacement en bloc. */
    suspend fun forgetDead(commandId: String): Boolean =
        mutate("Geste mort oublié : $commandId") { prefs ->
            prefs[Keys.DEAD] = writeArray(
                readArray(prefs[Keys.DEAD]).filterNot { serialized ->
                    PathCommandJson.decodeDead(serialized)?.command?.commandId == commandId
                },
            )
        }

    private fun readArray(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { it.isNotBlank() }
        }
    }

    private fun writeArray(entries: List<String>): String =
        JSONArray().apply { entries.forEach(::put) }.toString()

    private suspend fun mutate(
        successMessage: String,
        transform: (MutablePreferences) -> Unit,
    ): Boolean = try {
        context.pathCommandStore.edit(transform)
        logger.add(SyncLogger.TAG_SYNC, successMessage)
        true
    } catch (error: Exception) {
        logger.add(
            SyncLogger.TAG_ERROR,
            "Écriture de la file des gestes impossible : ${error.javaClass.simpleName}",
        )
        false
    }
}
