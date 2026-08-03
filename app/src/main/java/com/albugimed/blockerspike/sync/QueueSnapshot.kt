package com.albugimed.blockerspike.sync

import org.json.JSONObject

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
)

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

        return QueueSnapshot(
            generatedAt = root.optString("generated_at"),
            items = items,
            skipped = skipped,
        )
    }

    fun encode(snapshot: QueueSnapshot): String {
        val array = org.json.JSONArray()
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

        return JSONObject()
            .put("generated_at", snapshot.generatedAt)
            .put("items", array)
            .toString()
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
            ),
        )
    }

    private fun decodeNode(json: JSONObject): NodeRef? {
        val nodeId = json.optStringOrNull("node_id") ?: return null
        return NodeRef(nodeId = nodeId, label = json.optStringOrNull("label") ?: nodeId)
    }

    private fun encodeNode(node: NodeRef): JSONObject =
        JSONObject().put("node_id", node.nodeId).put("label", node.label)

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
        return json
    }
}
