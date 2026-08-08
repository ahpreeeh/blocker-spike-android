package com.albugimed.blockerspike.sync

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.random.Random
import org.json.JSONObject

/**
 * Une entrée d'agenda telle que le téléphone la détient — amendement V1.4.
 *
 * Différence de nature avec [AgendaItem] : celui-ci est un **extrait calculé
 * par le serveur** (prochain verrou, fenêtre de 48 h), celle-là est la
 * **matière**, modifiable ici, et qui repart vers le serveur.
 *
 * Deux horloges cohabitent, et il faut les distinguer pour comprendre le reste
 * du fichier :
 *
 *   - [editedAt] est l'heure à laquelle **une machine a modifié** l'entrée.
 *     C'est l'arbitre : la version la plus récente gagne, quelle que soit la
 *     machine. Elle voyage dans les deux sens.
 *   - le curseur de synchronisation, lui, est une heure **serveur** conservée
 *     à part par [AgendaStore]. Les deux ne peuvent pas être confondues : un
 *     curseur basé sur l'heure du téléphone reculerait au moindre décalage et
 *     sauterait des entrées pour toujours.
 *
 * [pending] dit que cette version n'a pas encore été accusée par le serveur.
 * Elle tient lieu de file d'attente : au lieu d'empiler les gestes, on garde
 * **le dernier état connu de l'entrée**. Créer puis supprimer hors réseau ne
 * produit donc qu'un seul envoi, et non deux qui se contredisent.
 */
data class AgendaEntry(
    val id: String,
    val label: String,
    val kind: String,
    val allDay: Boolean,
    /** Créneau horaire. `null` quand [allDay]. */
    val startsAt: OffsetDateTime?,
    val endsAt: OffsetDateTime?,
    /** Journée entière. `null` sinon. [endDate] est **inclusive**. */
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val isLock: Boolean,
    val location: String?,
    val editedAt: OffsetDateTime,
    /**
     * Retirée. Elle n'est pas effacée du magasin : une suppression est un
     * changement daté comme un autre, et une correction plus récente venue du
     * PC doit pouvoir la défaire.
     */
    val deleted: Boolean = false,
    val pending: Boolean = false,
) {
    /**
     * L'instant qui sert à ranger l'entrée dans le temps. Une journée entière
     * n'a pas d'heure : on la place à son début dans le fuseau d'affichage,
     * faute de quoi elle se retrouverait en tête ou en fin de liste selon le
     * hasard du fuseau.
     */
    fun sortInstant(zone: ZoneId): Instant = when {
        startsAt != null -> startsAt.toInstant()
        startDate != null -> startDate.atStartOfDay(zone).toInstant()
        else -> Instant.EPOCH
    }

    /**
     * L'instant de fin, pour décider ce qui est passé. Une journée entière
     * court jusqu'à la fin de son dernier jour — `end_date` est inclusive,
     * s'arrêter à son début ferait basculer l'entrée dans le passé alors
     * qu'elle est en cours.
     */
    fun endInstant(zone: ZoneId): Instant = when {
        endsAt != null -> endsAt.toInstant()
        startsAt != null -> startsAt.toInstant()
        endDate != null -> endDate.plusDays(1).atStartOfDay(zone).toInstant()
        startDate != null -> startDate.plusDays(1).atStartOfDay(zone).toInstant()
        else -> Instant.EPOCH
    }
}

/**
 * Les cinq natures que la base sait stocker.
 *
 * La colonne serveur est une énumération PostgreSQL : contrairement au type
 * d'un événement, une valeur hors liste ne peut pas y entrer du tout. Le
 * formulaire ne propose donc que celles-ci — mais [AgendaEntry.kind] reste une
 * chaîne, pour qu'un serveur plus récent qui en ajouterait une puisse la faire
 * descendre et l'afficher sans faire disparaître l'entrée.
 */
val AGENDA_KINDS: List<String> = listOf("cours", "stage", "garde", "rdv", "autre")

private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

/**
 * Un ULID préfixé `tmp_`, frappé **à la création dans le formulaire** et non à
 * l'envoi. Même mécanique que `newEventId` : une entrée renvoyée après une
 * coupure porte le même identifiant, et le serveur la reconnaît au lieu d'en
 * créer une seconde.
 */
fun newTemporalConstraintId(nowMillis: Long, random: Random = Random.Default): String {
    val characters = CharArray(26)
    var remaining = nowMillis
    for (index in 9 downTo 0) {
        characters[index] = CROCKFORD[(remaining % 32L).toInt()]
        remaining /= 32L
    }
    for (index in 10 until 26) {
        characters[index] = CROCKFORD[random.nextInt(CROCKFORD.length)]
    }
    return "tmp_" + String(characters)
}

/**
 * La forme réseau et la forme du magasin local.
 *
 * Elles partagent volontairement le même encodage, à un champ près
 * ([AgendaEntry.pending], qui ne concerne que le téléphone). Deux formes
 * distinctes obligeraient à maintenir deux décodeurs pour la même donnée.
 */
object AgendaEntryJson {

    /** Une entrée descendue par `GET /api/v1/agenda/sync`. */
    fun decodeRemote(json: JSONObject): AgendaEntry? {
        val id = json.text("id") ?: return null
        val editedAt = json.text("edited_at")?.let(::parseOffset) ?: return null
        val deleted = json.opt("deleted") as? Boolean ?: false
        val allDay = json.opt("all_day") as? Boolean ?: false

        // Une entrée retirée descend avec son contenu : le téléphone doit
        // pouvoir la remontrer si une correction ultérieure la défait.
        val label = json.text("label") ?: (if (deleted) "" else return null)
        val kind = json.text("kind") ?: (if (deleted) "autre" else return null)

        val startsAt = json.text("starts_at")?.let(::parseOffset)
        val endsAt = json.text("ends_at")?.let(::parseOffset)
        val startDate = json.text("start_date")?.let(::parseDate)
        val endDate = json.text("end_date")?.let(::parseDate)

        // Une entrée vivante sans aucune date serait invisible dans la liste et
        // impossible à replacer dans le temps : mieux vaut la compter comme
        // illisible et le dire que de l'afficher au 1er janvier 1970.
        if (!deleted && startsAt == null && startDate == null) return null

        return AgendaEntry(
            id = id,
            label = label,
            kind = kind,
            allDay = allDay,
            startsAt = if (allDay) null else startsAt,
            endsAt = if (allDay) null else endsAt,
            startDate = if (allDay) startDate else null,
            endDate = if (allDay) endDate else null,
            isLock = json.opt("is_lock") as? Boolean ?: false,
            location = json.text("location"),
            editedAt = editedAt,
            deleted = deleted,
            pending = false,
        )
    }

    /** Ce qui monte dans `POST /api/v1/agenda/sync`. */
    fun encodeChange(entry: AgendaEntry): JSONObject {
        val json = JSONObject()
            .put("id", entry.id)
            .put("edited_at", entry.editedAt.toString())
        if (entry.deleted) {
            // Une suppression ne transporte pas de contenu : le serveur n'en a
            // pas besoin, et l'envoyer laisserait croire qu'il peut être écrit.
            return json.put("deleted", true)
        }
        json.put("label", entry.label)
            .put("kind", entry.kind)
            .put("all_day", entry.allDay)
            .put("is_lock", entry.isLock)
        if (entry.allDay) {
            json.put("start_date", entry.startDate?.toString())
            json.put("end_date", entry.endDate?.toString())
        } else {
            json.put("starts_at", entry.startsAt?.toString())
            json.put("ends_at", entry.endsAt?.toString())
        }
        entry.location?.let { json.put("location", it) }
        return json
    }

    fun encodeLocal(entry: AgendaEntry): JSONObject =
        encodeFull(entry).put("_pending", entry.pending)

    fun decodeLocal(raw: String): AgendaEntry? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val entry = decodeRemote(json) ?: return null
        return entry.copy(pending = json.opt("_pending") as? Boolean ?: false)
    }

    private fun encodeFull(entry: AgendaEntry): JSONObject = JSONObject()
        .put("id", entry.id)
        .put("label", entry.label)
        .put("kind", entry.kind)
        .put("all_day", entry.allDay)
        .put("is_lock", entry.isLock)
        .put("deleted", entry.deleted)
        .put("edited_at", entry.editedAt.toString())
        .apply {
            entry.startsAt?.let { put("starts_at", it.toString()) }
            entry.endsAt?.let { put("ends_at", it.toString()) }
            entry.startDate?.let { put("start_date", it.toString()) }
            entry.endDate?.let { put("end_date", it.toString()) }
            entry.location?.let { put("location", it) }
        }

    private fun parseOffset(value: String): OffsetDateTime? =
        runCatching { OffsetDateTime.parse(value) }.getOrNull()

    private fun parseDate(value: String): LocalDate? =
        runCatching { LocalDate.parse(value.take(10)) }.getOrNull()

    private fun JSONObject.text(key: String): String? =
        (opt(key) as? String)?.trim()?.takeIf(String::isNotEmpty)
}

/**
 * L'arbitre du dernier-écrit-gagne, côté téléphone.
 *
 * Il rend exactement le même verdict que le serveur, égalité comprise : à
 * `edited_at` identique, c'est la version déjà en place qui reste. Une règle
 * différente des deux côtés ferait osciller une entrée d'un balayage à l'autre.
 */
internal fun mergeAgendaEntry(local: AgendaEntry?, incoming: AgendaEntry): AgendaEntry {
    if (local == null) return incoming
    // Une version locale non encore accusée défend son rang : tant qu'elle est
    // strictement plus récente, elle repartira et gagnera au serveur aussi.
    if (local.pending && !local.editedAt.toInstant().isBefore(incoming.editedAt.toInstant())) {
        return local
    }
    return incoming
}

/**
 * Décode un balayage descendant. Pur, donc prouvable sans réseau.
 *
 * Une entrée illisible est **comptée** et sautée, pas fatale : une seule ligne
 * abîmée ne doit pas priver l'écran de tout l'agenda. En revanche l'absence du
 * tableau lui-même rend `null` — cela signifie une réponse d'une autre forme,
 * et faire avancer le curseur sur cette base sauterait des entrées pour de bon.
 */
internal fun decodeAgendaDelta(body: String): AgendaDelta.Fresh? {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val array = root.optJSONArray("entries") ?: return null

    val entries = ArrayList<AgendaEntry>(array.length())
    var skipped = 0
    for (index in 0 until array.length()) {
        val entry = array.optJSONObject(index)?.let(AgendaEntryJson::decodeRemote)
        if (entry == null) skipped += 1 else entries += entry
    }

    return AgendaDelta.Fresh(
        entries = entries,
        cursor = (root.opt("cursor") as? String)?.takeIf(String::isNotBlank),
        hasMore = root.opt("has_more") as? Boolean ?: false,
        skipped = skipped,
    )
}

/** Décode les verdicts d'un envoi. `null` = réponse d'une autre forme. */
internal fun decodeAgendaResults(body: String): List<AgendaChangeResult>? {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val array = root.optJSONArray("results") ?: return null

    val results = ArrayList<AgendaChangeResult>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val id = (item.opt("id") as? String)?.takeIf(String::isNotBlank) ?: continue
        val status = (item.opt("status") as? String)?.takeIf(String::isNotBlank) ?: continue
        results += AgendaChangeResult(id, status, item.opt("reason") as? String)
    }
    return results
}

/** Instant courant au format que le serveur exige, décalage compris. */
internal fun agendaEditedNow(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): OffsetDateTime =
    OffsetDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone).withNano(0)
