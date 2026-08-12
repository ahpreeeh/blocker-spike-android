package com.albugimed.blockerspike.sync

import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

/**
 * Les trois gestes du parcours, tels qu'ils voyagent — `POST /api/v1/queue`.
 *
 * Le téléphone ne crée ni ne supprime d'étape : ça reste le travail de
 * l'atelier. Il ne fait que trois choses, et cette liste est fermée exprès.
 *
 * Les trois sont **idempotents par construction**, ce qui n'est pas un
 * raffinement mais la condition du hors-ligne : cocher est un état, pas un
 * basculement ; verser saute ce qui est déjà là ; réordonner envoie la liste
 * ENTIÈRE affichée, jamais « monte d'un rang ». Trois « monte » rejoués sur une
 * liste que l'atelier a modifiée entre-temps donneraient un résultat que
 * personne n'a voulu. C'est aussi ce qui dispense le serveur de tenir une table
 * des commandes déjà vues.
 */
sealed interface PathCommand {
    /**
     * Frappé **au geste**, jamais à l'envoi — même raison qu'un `event_id`
     * (§3). Il ne sert qu'à recevoir son verdict en retour : le serveur ne le
     * stocke pas, puisque les commandes sont rejouables sans mémoire.
     */
    val commandId: String

    data class CompleteStep(
        override val commandId: String,
        val stepId: String,
        val completed: Boolean,
        /** L'instant du geste. Cocher hors ligne hier, c'est hier. */
        val completedAt: String,
    ) : PathCommand

    data class PourSubject(
        override val commandId: String,
        val nodeId: String,
        val kind: String,
    ) : PathCommand

    data class ReorderPath(
        override val commandId: String,
        val stepIds: List<String>,
    ) : PathCommand
}

/** Une commande que le serveur a refusée. Elle ne repart plus, et ne disparaît pas. */
data class DeadPathCommand(val command: PathCommand, val reason: String)

data class PathCommandResult(
    val commandId: String,
    val status: String,
    val reason: String?,
) {
    /**
     * `noop` est un **succès** : la commande était comprise et n'avait rien à
     * faire — l'étape a disparu de l'atelier, ou les chapitres y étaient déjà.
     * La renvoyer donnerait éternellement la même réponse.
     */
    val isSettled: Boolean get() = status == "applied" || status == "noop"
    val isRejected: Boolean get() = status == "rejected"
}

fun newPathCommandId(nowMillis: Long, random: Random = Random.Default): String =
    "cmd_" + ulidBody(nowMillis, random)

/**
 * Sérialisation des commandes de parcours.
 *
 * Le même format sert au magasin local et à l'envoi, comme pour les traces :
 * une commande mise en attente hors ligne part exactement telle qu'elle a été
 * faite, sans ré-encodage capable de la déformer.
 */
object PathCommandJson {

    fun encode(command: PathCommand): JSONObject {
        val json = JSONObject().put("command_id", command.commandId)
        return when (command) {
            is PathCommand.CompleteStep -> json
                .put("type", "complete_step")
                .put("step_id", command.stepId)
                .put("completed", command.completed)
                .put("completed_at", command.completedAt)

            is PathCommand.PourSubject -> json
                .put("type", "pour_subject")
                .put("node_id", command.nodeId)
                .put("kind", command.kind)

            is PathCommand.ReorderPath -> json
                .put("type", "reorder_path")
                .put("step_ids", JSONArray().apply { command.stepIds.forEach(::put) })
        }
    }

    fun encodeToString(command: PathCommand): String = encode(command).toString()

    /**
     * Relit une commande stockée. `null` si la forme est inexploitable —
     * l'appelant ne jette **pas** la chaîne d'origine : elle reste en magasin,
     * comptée comme illisible, jusqu'à examen.
     */
    fun decode(serialized: String): PathCommand? {
        val root = runCatching { JSONObject(serialized) }.getOrNull() ?: return null
        val commandId = root.optStringOrNull("command_id") ?: return null

        return when (root.optString("type")) {
            "complete_step" -> PathCommand.CompleteStep(
                commandId = commandId,
                stepId = root.optStringOrNull("step_id") ?: return null,
                completed = root.optBoolean("completed"),
                completedAt = root.optStringOrNull("completed_at") ?: return null,
            )

            "pour_subject" -> PathCommand.PourSubject(
                commandId = commandId,
                nodeId = root.optStringOrNull("node_id") ?: return null,
                kind = root.optStringOrNull("kind") ?: return null,
            )

            "reorder_path" -> {
                val array = root.optJSONArray("step_ids") ?: return null
                val ids = ArrayList<String>(array.length())
                for (index in 0 until array.length()) {
                    ids.add(array.optString(index).takeIf { it.isNotBlank() } ?: return null)
                }
                if (ids.isEmpty()) return null
                PathCommand.ReorderPath(commandId = commandId, stepIds = ids)
            }

            else -> null
        }
    }

    fun encodeDead(dead: DeadPathCommand): String =
        JSONObject()
            .put("reason", dead.reason)
            .put("command", encode(dead.command))
            .toString()

    fun decodeDead(serialized: String): DeadPathCommand? {
        val root = runCatching { JSONObject(serialized) }.getOrNull() ?: return null
        val command = root.optJSONObject("command")?.let { decode(it.toString()) } ?: return null
        return DeadPathCommand(command = command, reason = root.optString("reason", "Motif inconnu"))
    }

    fun encodeBatch(deviceId: String, commands: List<PathCommand>): String {
        val array = JSONArray()
        commands.forEach { array.put(encode(it)) }
        return JSONObject()
            .put("device_id", deviceId)
            .put("commands", array)
            .toString()
    }

    /**
     * Les verdicts, **un par commande**. Une réponse illisible ne vaut pas un
     * refus : l'appelant garde tout et réessaiera, ce que l'idempotence rend
     * sans danger.
     */
    fun decodeResults(body: String): List<PathCommandResult>? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val array = root.optJSONArray("results") ?: return null

        val results = ArrayList<PathCommandResult>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val commandId = item.optStringOrNull("command_id") ?: continue
            results.add(
                PathCommandResult(
                    commandId = commandId,
                    status = item.optString("status"),
                    reason = item.optStringOrNull("reason"),
                ),
            )
        }
        return results
    }
}
