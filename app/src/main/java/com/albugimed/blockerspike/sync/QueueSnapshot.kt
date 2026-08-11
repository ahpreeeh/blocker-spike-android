package com.albugimed.blockerspike.sync

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * La file courte reçue du serveur — contrat §4.
 *
 * **L'ordre de `items` fait foi.** C'est celui que l'utilisateur a posé à la
 * main dans l'atelier. Le téléphone l'affiche tel quel : pas de tri par
 * échéance, pas de tri par fraîcheur, pas de remontée de l'« urgent ».
 * L'application n'a aucun avis sur l'ordre de travail (P4-02).
 *
 * Les signaux sont des **faits affichés**, pas des scores. `freshnessDays`
 * dit depuis combien de jours rien n'a été déclaré ; il ne dit pas qu'il
 * faudrait s'y remettre.
 */
data class QueueSnapshot(
    val generatedAt: String,
    val items: List<QueueItem>,
    /** Items reçus mais inexploitables. Visible, jamais silencieux. */
    val skipped: Int = 0,
    /** Matières et chapitres reçus, dans l'ordre exact du serveur. */
    val nodes: List<AcademicNodeRef> = emptyList(),
    /** Nœuds reçus mais inutilisables. Conservé aussi dans la copie locale. */
    val skippedNodes: Int = 0,
) {
    companion object {
        val EMPTY = QueueSnapshot(generatedAt = "", items = emptyList())
    }
}

data class QueueItem(
    val stepId: String,
    val label: String,
    val kind: String,
    val subject: NodeRef,
    val chapter: NodeRef?,
    val resource: ResourceRef?,
    val signals: QueueSignals,
)

data class NodeRef(val nodeId: String, val label: String)

enum class AcademicNodeKind(val wireName: String) {
    SUBJECT("subject"),
    CHAPTER("chapter"),
    ;

    companion object {
        fun fromWire(value: String): AcademicNodeKind? =
            entries.firstOrNull { it.wireName == value }
    }
}

data class AcademicNodeRef(
    val nodeId: String,
    val label: String,
    val kind: AcademicNodeKind,
    val parentId: String?,
    /** Absent d'un serveur ou d'un cache anterieur : [NodeProgress.NONE]. */
    val progress: NodeProgress = NodeProgress.NONE,
)

/**
 * Les dimensions de progression d'un noeud, calculees par le serveur.
 *
 * Elles arrivent **separees et le restent** : il n'existe aucun endroit ou les
 * fondre en un pourcentage unique, et l'ecran n'en fabrique pas (P4-01 point 7).
 *
 * `null` veut dire « aucune trace », jamais « zero ». C'est la distinction du
 * cadrage §1.2, et elle survit au transport : le serveur envoie `null`, le
 * cache conserve `null`, l'affichage rend « — ». Remplacer un `null` par 0 ici
 * transformerait « je n'ai rien declare » en « j'ai fait zero revision ».
 */
data class NodeProgress(
    val courseStudied: Boolean = false,
    val revisionCount: Int? = null,
    val trainingCount: Int? = null,
    val errorCount: Int? = null,
    val freshnessDays: Int? = null,
) {
    companion object {
        val NONE = NodeProgress()
    }
}

data class ResourceRef(
    val resourceId: String,
    val label: String,
    val type: String,
    /**
     * Absente quand le serveur a jugé l'adresse non ouvrable. La ressource
     * reste visible, seule l'ouverture disparaît — ne jamais reconstruire
     * une adresse écartée ici.
     */
    val openUri: String?,
)

data class QueueSignals(
    val lastActivityAt: String?,
    val freshnessDays: Int?,
    val deadlineLabel: String?,
    val deadlineDate: String?,
    /**
     * Dernier travail déclaré. Il s'agit d'un fait historique, pas de la
     * position de reprise du futur lecteur PDF.
     */
    val lastWork: LastWork? = null,
)

data class LastWork(
    val occurredAt: String,
    val unit: LastWorkUnit,
    val stepId: String?,
    val resourceId: String?,
)

/**
 * Forme descendante et tolérante des unités reçues dans `signals.last_work`.
 *
 * Elle reste volontairement distincte d'[ActivityUnit] : l'application doit
 * pouvoir relire hors ligne un type ajouté plus tard par le serveur. La forme
 * inconnue est donc gardée telle quelle dans le cache, sans être interprétée.
 */
sealed interface LastWorkUnit {
    data class Pages(val from: Int, val to: Int) : LastWorkUnit
    data object Chapter : LastWorkUnit
    data class Annale(val label: String) : LastWorkUnit
    data class Cards(val count: Int) : LastWorkUnit
    data class Free(val label: String) : LastWorkUnit
    data class Unknown(val rawJson: String) : LastWorkUnit
}

object QueueSnapshotJson {

    /** Le `device_id` que le serveur attribue au jeton présenté (contrat §3). */
    fun deviceIdOf(body: String): String? =
        runCatching { JSONObject(body) }.getOrNull()?.optStringOrNull("device_id")

    fun decode(body: String): QueueSnapshot? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val array = root.optJSONArray("items") ?: return null

        val items = ArrayList<QueueItem>(array.length())
        var skipped = 0
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)?.let(::decodeItem)
            if (item == null) skipped += 1 else items.add(item)
        }
        val decodedNodes = decodeAcademicNodes(root)

        return QueueSnapshot(
            generatedAt = root.optString("generated_at"),
            items = items,
            skipped = skipped.saturatingAdd(root.cachedSkipped("_cache_skipped_items")),
            nodes = decodedNodes.nodes,
            skippedNodes = decodedNodes.skipped
                .saturatingAdd(root.cachedSkipped("_cache_skipped_nodes")),
        )
    }

    fun encode(snapshot: QueueSnapshot): String {
        val array = JSONArray()
        snapshot.items.forEach { item ->
            val json = JSONObject()
                .put("step_id", item.stepId)
                .put("label", item.label)
                .put("kind", item.kind)
                .put("subject", encodeNode(item.subject))
                .put("signals", encodeSignals(item.signals))
            item.chapter?.let { json.put("chapter", encodeNode(it)) }
            item.resource?.let { resource ->
                json.put(
                    "resource",
                    JSONObject()
                        .put("resource_id", resource.resourceId)
                        .put("label", resource.label)
                        .put("type", resource.type)
                        .apply { resource.openUri?.let { put("open_uri", it) } },
                )
            }
            array.put(json)
        }

        val nodes = JSONArray()
        snapshot.nodes.forEach { node ->
            nodes.put(
                JSONObject()
                    .put("node_id", node.nodeId)
                    .put("label", node.label)
                    .put("kind", node.kind.wireName)
                    .apply {
                        put("parent_id", node.parentId ?: JSONObject.NULL)
                        put("progress", encodeProgress(node.progress))
                    },
            )
        }

        return JSONObject()
            .put("generated_at", snapshot.generatedAt)
            .put("items", array)
            .put("nodes", nodes)
            // Métadonnées du cache uniquement : après retrait des entrées
            // illisibles, leurs compteurs ne doivent pas disparaître au
            // redémarrage suivant.
            .put("_cache_skipped_items", snapshot.skipped)
            .put("_cache_skipped_nodes", snapshot.skippedNodes)
            .toString()
    }

    private data class DecodedNodes(
        val nodes: List<AcademicNodeRef>,
        val skipped: Int,
    )

    private fun decodeAcademicNodes(root: JSONObject): DecodedNodes {
        val array = root.optJSONArray("nodes")
        if (array == null) {
            // Clé absente : ancien serveur/cache, donc pas une corruption.
            val malformedContainer = root.has("nodes") && !root.isNull("nodes")
            return DecodedNodes(emptyList(), if (malformedContainer) 1 else 0)
        }

        val candidates = ArrayList<AcademicNodeRef>(array.length())
        var skipped = 0
        for (index in 0 until array.length()) {
            val node = array.optJSONObject(index)?.let(::decodeAcademicNode)
            if (node == null) skipped += 1 else candidates += node
        }

        // Deux passages : un chapitre peut précéder sa matière dans la
        // réponse. On valide la relation sans jamais retrier le tableau.
        val subjectIds = candidates
            .asSequence()
            .filter { it.kind == AcademicNodeKind.SUBJECT }
            .mapTo(mutableSetOf(), AcademicNodeRef::nodeId)
        val nodes = candidates.filter { node ->
            val usable = node.kind == AcademicNodeKind.SUBJECT || node.parentId in subjectIds
            if (!usable) skipped += 1
            usable
        }
        return DecodedNodes(nodes = nodes, skipped = skipped)
    }

    private fun decodeAcademicNode(json: JSONObject): AcademicNodeRef? {
        val nodeId = json.optStringOrNull("node_id") ?: return null
        val kind = json.optStringOrNull("kind")
            ?.let(AcademicNodeKind::fromWire)
            ?: return null
        val parentId = json.optStringOrNull("parent_id")
        if (kind == AcademicNodeKind.SUBJECT && parentId != null) return null
        if (kind == AcademicNodeKind.CHAPTER && parentId == null) return null
        return AcademicNodeRef(
            nodeId = nodeId,
            label = json.optStringOrNull("label") ?: nodeId,
            kind = kind,
            parentId = parentId,
            progress = decodeProgress(json.optJSONObject("progress")),
        )
    }

    /**
     * Un bloc absent ou illisible donne [NodeProgress.NONE], et le noeud reste
     * affichable. C'est voulu : une progression manquante n'est pas une raison
     * de faire disparaitre un chapitre de la liste des matieres.
     */
    private fun decodeProgress(json: JSONObject?): NodeProgress {
        if (json == null) return NodeProgress.NONE
        return NodeProgress(
            courseStudied = json.optBoolean("course_studied", false),
            revisionCount = json.strictInt("revision_count")?.takeIf { it > 0 },
            trainingCount = json.strictInt("training_count")?.takeIf { it > 0 },
            errorCount = json.strictInt("error_count")?.takeIf { it > 0 },
            // Une fraicheur de 0 jour est un fait — « declare aujourd'hui » —
            // la ou un compte de 0 n'en est pas un. Les deux ne se filtrent
            // donc pas de la meme facon.
            freshnessDays = json.strictInt("freshness_days")?.takeIf { it >= 0 },
        )
    }

    private fun decodeItem(json: JSONObject): QueueItem? {
        val stepId = json.optStringOrNull("step_id") ?: return null
        val subject = json.optJSONObject("subject")?.let(::decodeNode) ?: return null
        val signals = json.optJSONObject("signals")

        return QueueItem(
            stepId = stepId,
            label = json.optStringOrNull("label") ?: subject.label,
            kind = json.optString("kind"),
            subject = subject,
            chapter = json.optJSONObject("chapter")?.let(::decodeNode),
            resource = json.optJSONObject("resource")?.let { resource ->
                val resourceId = resource.optStringOrNull("resource_id") ?: return@let null
                ResourceRef(
                    resourceId = resourceId,
                    label = resource.optStringOrNull("label") ?: resourceId,
                    type = resource.optString("type"),
                    openUri = resource.optStringOrNull("open_uri"),
                )
            },
            signals = QueueSignals(
                lastActivityAt = signals?.optStringOrNull("last_activity_at"),
                freshnessDays = signals?.let {
                    if (it.isNull("freshness_days")) null else it.optInt("freshness_days")
                },
                deadlineLabel = signals?.optJSONObject("deadline")?.optStringOrNull("label"),
                deadlineDate = signals?.optJSONObject("deadline")?.optStringOrNull("date"),
                lastWork = signals?.optJSONObject("last_work")?.let(::decodeLastWork),
            ),
        )
    }

    private fun decodeLastWork(json: JSONObject): LastWork? {
        val occurredAt = json.optStringOrNull("occurred_at") ?: return null
        if (!json.has("unit")) return null
        return LastWork(
            occurredAt = occurredAt,
            unit = decodeLastWorkUnit(json.opt("unit")),
            stepId = json.optStringOrNull("step_id"),
            resourceId = json.optStringOrNull("resource_id"),
        )
    }

    private fun decodeLastWorkUnit(value: Any?): LastWorkUnit {
        val json = value as? JSONObject
            ?: return LastWorkUnit.Unknown(encodeOpaqueJson(value))

        return when (json.optStringOrNull("type")) {
            "pages" -> {
                val from = json.strictInt("from")
                val to = json.strictInt("to")
                if (from != null && to != null && from > 0 && to >= from) {
                    LastWorkUnit.Pages(from = from, to = to)
                } else {
                    LastWorkUnit.Unknown(json.toString())
                }
            }

            "chapter" -> LastWorkUnit.Chapter
            "annale" -> json.optStringOrNull("label")
                ?.let { LastWorkUnit.Annale(it) }
                ?: LastWorkUnit.Unknown(json.toString())

            "cards" -> json.strictInt("count")
                ?.takeIf { it > 0 }
                ?.let { LastWorkUnit.Cards(it) }
                ?: LastWorkUnit.Unknown(json.toString())

            "free" -> json.optStringOrNull("label")
                ?.let { LastWorkUnit.Free(it) }
                ?: LastWorkUnit.Unknown(json.toString())

            else -> LastWorkUnit.Unknown(json.toString())
        }
    }

    private fun decodeNode(json: JSONObject): NodeRef? {
        val nodeId = json.optStringOrNull("node_id") ?: return null
        return NodeRef(nodeId = nodeId, label = json.optStringOrNull("label") ?: nodeId)
    }

    private fun encodeNode(node: NodeRef): JSONObject =
        JSONObject().put("node_id", node.nodeId).put("label", node.label)

    /**
     * `JSONObject.NULL` explicite, et non la clef omise : relu, un `null` doit
     * redonner `null` et non retomber sur une valeur par defaut. C'est la
     * meme exigence que du cote serveur, de l'autre bout du meme fil.
     */
    private fun encodeProgress(progress: NodeProgress): JSONObject =
        JSONObject()
            .put("course_studied", progress.courseStudied)
            .put("revision_count", progress.revisionCount ?: JSONObject.NULL)
            .put("training_count", progress.trainingCount ?: JSONObject.NULL)
            .put("error_count", progress.errorCount ?: JSONObject.NULL)
            .put("freshness_days", progress.freshnessDays ?: JSONObject.NULL)

    private fun encodeSignals(signals: QueueSignals): JSONObject {
        val json = JSONObject()
        signals.lastActivityAt?.let { json.put("last_activity_at", it) }
        signals.freshnessDays?.let { json.put("freshness_days", it) }
        if (signals.deadlineDate != null) {
            json.put(
                "deadline",
                JSONObject()
                    .put("date", signals.deadlineDate)
                    .apply { signals.deadlineLabel?.let { put("label", it) } },
            )
        }
        signals.lastWork?.let { json.put("last_work", encodeLastWork(it)) }
        return json
    }

    private fun encodeLastWork(lastWork: LastWork): JSONObject =
        JSONObject()
            .put("occurred_at", lastWork.occurredAt)
            .put("unit", encodeLastWorkUnit(lastWork.unit))
            .apply {
                lastWork.stepId?.let { put("step_id", it) }
                lastWork.resourceId?.let { put("resource_id", it) }
            }

    private fun encodeLastWorkUnit(unit: LastWorkUnit): Any = when (unit) {
        is LastWorkUnit.Pages -> JSONObject()
            .put("type", "pages")
            .put("from", unit.from)
            .put("to", unit.to)

        LastWorkUnit.Chapter -> JSONObject().put("type", "chapter")
        is LastWorkUnit.Annale -> JSONObject()
            .put("type", "annale")
            .put("label", unit.label)

        is LastWorkUnit.Cards -> JSONObject()
            .put("type", "cards")
            .put("count", unit.count)

        is LastWorkUnit.Free -> JSONObject()
            .put("type", "free")
            .put("label", unit.label)

        is LastWorkUnit.Unknown -> runCatching {
            JSONTokener(unit.rawJson).nextValue()
        }.getOrElse {
            // Une instance forgée localement reste sérialisable sans perte.
            unit.rawJson
        }
    }
}

private fun JSONObject.cachedSkipped(name: String): Int =
    strictInt(name)?.coerceAtLeast(0) ?: 0

private fun Int.saturatingAdd(other: Int): Int =
    if (this > Int.MAX_VALUE - other) Int.MAX_VALUE else this + other

private fun encodeOpaqueJson(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject, is JSONArray, is Number, is Boolean -> value.toString()
    else -> JSONObject.quote(value.toString())
}

private fun JSONObject.strictInt(name: String): Int? {
    val number = opt(name) as? Number ?: return null
    val value = number.toDouble()
    if (!value.isFinite() || value % 1.0 != 0.0) return null
    if (value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()) return null
    return value.toInt()
}
