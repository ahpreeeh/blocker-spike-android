package com.albugimed.blockerspike.sync

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/**
 * Copie descendante de l'agenda temporel (contrat §15).
 *
 * [kind] reste une chaîne : un serveur plus récent peut ajouter une valeur et
 * le téléphone doit alors l'afficher brute, pas faire disparaître l'entrée.
 */
data class AgendaItem(
    val id: String,
    val label: String,
    val kind: String,
    val startsAt: OffsetDateTime,
    val endsAt: OffsetDateTime?,
    val allDay: Boolean,
    val location: String? = null,
)

data class AgendaSnapshot(
    val generatedAt: String,
    val timezone: String,
    val nextLock: AgendaItem?,
    val window48h: List<AgendaItem>,
    /** Entrées du tableau reçues mais inexploitables. Toujours visible. */
    val skippedWindowItems: Int = 0,
    /** Champs de premier niveau absents ou mal formés, dont `next_lock`. */
    val malformedFields: Int = 0,
) {
    companion object {
        val EMPTY = AgendaSnapshot(
            generatedAt = "",
            timezone = "",
            nextLock = null,
            window48h = emptyList(),
        )
    }
}

/** JSON de réseau et JSON du cache utilisent volontairement la même forme. */
object AgendaSnapshotJson {
    fun decode(body: String): AgendaSnapshot? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val malformedFields = root.cachedCount(CACHE_MALFORMED_FIELDS)

        // Ces champs racine sont le contrat minimal. Les remplacer par des
        // valeurs vides ferait confondre « aucun verrou / rien dans 48 h »
        // avec « le serveur n'a pas fourni l'information », puis écraserait
        // une copie locale encore valable.
        val generatedAt = root.strictString("generated_at")
            ?.takeIf { runCatching { Instant.parse(it) }.isSuccess }
            ?: return null
        val timezone = root.strictString("timezone")
            ?.takeIf { runCatching { ZoneId.of(it) }.isSuccess }
            ?: return null

        val nextLock = when {
            !root.has("next_lock") -> return null
            root.isNull("next_lock") -> null
            else -> root.optJSONObject("next_lock")
                ?.let { decodeItem(it, requireEnd = false) }
                ?: return null
        }

        val window = ArrayList<AgendaItem>()
        var skipped = root.cachedCount(CACHE_SKIPPED_WINDOW)
        val windowJson = root.optJSONArray("window_48h") ?: return null
        for (index in 0 until windowJson.length()) {
            val item = windowJson.optJSONObject(index)
                ?.let { decodeItem(it, requireEnd = true) }
            if (item == null) skipped += 1 else window += item
        }

        return AgendaSnapshot(
            generatedAt = generatedAt,
            timezone = timezone,
            nextLock = nextLock,
            window48h = window,
            skippedWindowItems = skipped,
            malformedFields = malformedFields,
        )
    }

    fun encode(snapshot: AgendaSnapshot): String {
        val root = JSONObject()
            .put("generated_at", snapshot.generatedAt)
            .put("timezone", snapshot.timezone)
            .put(
                "next_lock",
                snapshot.nextLock?.let(::encodeItem) ?: JSONObject.NULL,
            )

        val window = JSONArray()
        snapshot.window48h.forEach { window.put(encodeItem(it)) }
        root.put("window_48h", window)

        // Ces compteurs sont propres au cache. Sans eux, les avertissements
        // disparaîtraient au premier redémarrage après une réponse partielle.
        root.put(CACHE_SKIPPED_WINDOW, snapshot.skippedWindowItems)
        root.put(CACHE_MALFORMED_FIELDS, snapshot.malformedFields)
        return root.toString()
    }

    private fun decodeItem(json: JSONObject, requireEnd: Boolean): AgendaItem? {
        val id = json.strictString("id") ?: return null
        val label = json.strictString("label") ?: return null
        val kind = json.strictString("kind") ?: return null
        val startsAt = json.strictString("starts_at")
            ?.let(::parseOffsetDateTime)
            ?: return null
        val allDay = json.opt("all_day") as? Boolean ?: return null
        val endsAt = json.strictString("ends_at")?.let(::parseOffsetDateTime)
        if (requireEnd && endsAt == null) return null
        if (endsAt != null && !endsAt.toInstant().isAfter(startsAt.toInstant())) return null

        val location = when {
            !json.has("location") || json.isNull("location") -> null
            else -> json.strictString("location") ?: return null
        }
        return AgendaItem(
            id = id,
            label = label,
            kind = kind,
            startsAt = startsAt,
            endsAt = endsAt,
            allDay = allDay,
            location = location,
        )
    }

    private fun encodeItem(item: AgendaItem): JSONObject = JSONObject()
        .put("id", item.id)
        .put("label", item.label)
        .put("kind", item.kind)
        .put("starts_at", item.startsAt.toString())
        .put("all_day", item.allDay)
        .apply {
            item.endsAt?.let { put("ends_at", it.toString()) }
            item.location?.let { put("location", it) }
        }

    private fun parseOffsetDateTime(value: String): OffsetDateTime? =
        runCatching { OffsetDateTime.parse(value) }.getOrNull()

    private fun JSONObject.strictString(key: String): String? =
        (opt(key) as? String)?.trim()?.takeIf(String::isNotEmpty)

    private fun JSONObject.cachedCount(key: String): Int =
        optInt(key, 0).coerceAtLeast(0)

    private const val CACHE_SKIPPED_WINDOW = "_cache_skipped_window_items"
    private const val CACHE_MALFORMED_FIELDS = "_cache_malformed_fields"
}
