package com.albugimed.blockerspike.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * Sérialisation des événements — contrat §5.
 *
 * Le même format sert au stockage local et à l'envoi. Ce n'est pas une
 * économie de code : c'est ce qui garantit qu'un événement mis en file
 * d'attente hors ligne partira **exactement** tel qu'il a été déclaré, sans
 * ré-encodage intermédiaire capable de le déformer.
 *
 * `org.json` plutôt qu'une bibliothèque : il est déjà sur l'appareil et déjà
 * utilisé par `UnlockPromptBuilder`. Aucune dépendance ajoutée, donc aucune
 * surface réseau nouvelle à revérifier (contrat §10).
 */
object StudyEventJson {

    fun encode(event: StudyEvent): JSONObject {
        val payload = JSONObject()
        event.nodeId?.let { payload.put("node_id", it) }
        event.stepId?.let { payload.put("step_id", it) }
        event.resourceId?.let { payload.put("resource_id", it) }
        event.activityKind?.let { payload.put("activity_kind", it.wireName) }
        event.durationMinutes?.let { payload.put("duration_minutes", it) }
        event.difficulty?.let { payload.put("difficulty", it.wireName) }
        event.note?.let { payload.put("note", it) }
        event.unit?.let { payload.put("unit", encodeUnit(it)) }

        return JSONObject()
            .put("event_id", event.eventId)
            .put("type", event.type.wireName)
            .put("occurred_at", event.occurredAt)
            .put("payload", payload)
    }

    fun encodeToString(event: StudyEvent): String = encode(event).toString()

    /**
     * Relit un événement stocké. Renvoie `null` si la forme est inexploitable
     * — l'appelant ne doit alors **pas** jeter la chaîne d'origine : elle
     * reste en magasin, comptée comme illisible, jusqu'à examen.
     */
    fun decode(serialized: String): StudyEvent? {
        val root = runCatching { JSONObject(serialized) }.getOrNull() ?: return null

        val eventId = root.optString("event_id").takeIf { it.isNotBlank() } ?: return null
        val type = StudyEventType.fromWire(root.optString("type")) ?: return null
        val occurredAt = root.optString("occurred_at").takeIf { it.isNotBlank() } ?: return null
        val payload = root.optJSONObject("payload") ?: JSONObject()

        return StudyEvent(
            eventId = eventId,
            type = type,
            occurredAt = occurredAt,
            nodeId = payload.optStringOrNull("node_id"),
            stepId = payload.optStringOrNull("step_id"),
            resourceId = payload.optStringOrNull("resource_id"),
            activityKind = payload.optStringOrNull("activity_kind")?.let(ActivityKind::fromWire),
            durationMinutes = if (payload.has("duration_minutes")) {
                payload.optInt("duration_minutes")
            } else {
                null
            },
            unit = payload.optJSONObject("unit")?.let(::decodeUnit),
            difficulty = payload.optStringOrNull("difficulty")?.let(Difficulty::fromWire),
            note = payload.optStringOrNull("note"),
        )
    }

    fun encodeDead(dead: DeadEvent): String =
        JSONObject()
            .put("reason", dead.reason)
            .put("event", encode(dead.event))
            .toString()

    fun decodeDead(serialized: String): DeadEvent? {
        val root = runCatching { JSONObject(serialized) }.getOrNull() ?: return null
        val event = root.optJSONObject("event")?.let { decode(it.toString()) } ?: return null
        return DeadEvent(event = event, reason = root.optString("reason", "Motif inconnu"))
    }

    /** Le corps d'un envoi groupé — contrat §5. */
    fun encodeBatch(deviceId: String, events: List<StudyEvent>): String {
        val array = JSONArray()
        events.forEach { array.put(encode(it)) }
        return JSONObject()
            .put("device_id", deviceId)
            .put("events", array)
            .toString()
    }

    /**
     * Les verdicts renvoyés par le serveur, **un par événement**. Une réponse
     * illisible ne vaut pas un refus : l'appelant garde tout et réessaiera.
     */
    fun decodeResults(body: String): List<EventResult>? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val array = root.optJSONArray("results") ?: return null

        val results = ArrayList<EventResult>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val eventId = item.optString("event_id").takeIf { it.isNotBlank() } ?: continue
            results.add(
                EventResult(
                    eventId = eventId,
                    status = item.optString("status"),
                    reason = item.optStringOrNull("reason"),
                )
            )
        }
        return results
    }

    private fun encodeUnit(unit: ActivityUnit): JSONObject = when (unit) {
        is ActivityUnit.Pages -> JSONObject()
            .put("type", "pages").put("from", unit.from).put("to", unit.to)
        ActivityUnit.Chapter -> JSONObject().put("type", "chapter")
        is ActivityUnit.Annale -> JSONObject().put("type", "annale").put("label", unit.label)
        is ActivityUnit.Cards -> JSONObject().put("type", "cards").put("count", unit.count)
        is ActivityUnit.Free -> JSONObject().put("type", "free").put("label", unit.label)
    }

    private fun decodeUnit(json: JSONObject): ActivityUnit? = when (json.optString("type")) {
        "pages" -> ActivityUnit.Pages(json.optInt("from"), json.optInt("to"))
        "chapter" -> ActivityUnit.Chapter
        "annale" -> ActivityUnit.Annale(json.optString("label"))
        "cards" -> ActivityUnit.Cards(json.optInt("count"))
        "free" -> ActivityUnit.Free(json.optString("label"))
        else -> null
    }
}

internal fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
