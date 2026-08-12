package com.albugimed.blockerspike.sync

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * Le vocabulaire du journal d'activité — contrat §3 et §5.
 *
 * Rien ici ne concerne la coercition. Aucune politique de blocage, aucun nom
 * de paquet, aucune statistique d'usage ne traverse ce contrat (P4-08-A1) :
 * ces types sont la frontière, et ils ne les nomment pas.
 */

enum class StudyEventType(val wireName: String) {
    ACTIVITY_RECORDED("activity_recorded"),
    STEP_COMPLETED("step_completed"),
    RESOURCE_OPENED("resource_opened"),
    ;

    companion object {
        fun fromWire(value: String): StudyEventType? = entries.firstOrNull { it.wireName == value }
    }
}

enum class Difficulty(val wireName: String) {
    EASY("easy"),
    OK("ok"),
    HARD("hard"),
    ;

    companion object {
        fun fromWire(value: String): Difficulty? = entries.firstOrNull { it.wireName == value }
    }
}

enum class ActivityKind(val wireName: String) {
    FIRST_STUDY("first_study"),
    REVISION("revision"),
    TRAINING("training"),
    READING("reading"),
    ;

    companion object {
        fun fromWire(value: String): ActivityKind? = entries.firstOrNull { it.wireName == value }
    }
}

/** Ce qui a été fait, dans l'unité qui a du sens pour l'activité déclarée. */
sealed interface ActivityUnit {
    data class Pages(val from: Int, val to: Int) : ActivityUnit
    data object Chapter : ActivityUnit
    data class Annale(val label: String) : ActivityUnit
    data class Cards(val count: Int) : ActivityUnit
    data class Free(val label: String) : ActivityUnit
}

data class StudyEvent(
    val eventId: String,
    val type: StudyEventType,
    /** ISO-8601 **avec décalage horaire**. Sans lui, le serveur refuse (contrat §6). */
    val occurredAt: String,
    val nodeId: String? = null,
    val stepId: String? = null,
    val resourceId: String? = null,
    val activityKind: ActivityKind? = null,
    val durationMinutes: Int? = null,
    val unit: ActivityUnit? = null,
    val difficulty: Difficulty? = null,
    val note: String? = null,
)

/**
 * Un événement que le serveur a refusé pour défaut de structure. Il ne
 * repart pas : le renvoyer donnerait éternellement le même refus. Il ne
 * disparaît pas non plus — c'est une trace, et l'écran la montre.
 */
data class DeadEvent(val event: StudyEvent, val reason: String)

private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

/**
 * Un ULID préfixé `evt_`, **frappé au moment de la saisie** et non de
 * l'envoi. C'est toute la mécanique d'idempotence du contrat §3 : un
 * événement renvoyé après une coupure porte le même identifiant, et l'index
 * unique du serveur le compte une seule fois.
 *
 * Déplacer cet appel vers le moment de l'envoi transformerait chaque
 * nouvelle tentative en un événement distinct. C'est l'erreur à ne pas
 * commettre.
 */
fun newEventId(nowMillis: Long, random: Random = Random.Default): String =
    "evt_" + ulidBody(nowMillis, random)

/**
 * Le corps d'un ULID : dix caractères d'horodatage, seize de hasard. Partagé
 * avec les commandes de parcours, qui frappent leur identifiant au même moment
 * et pour la même raison — au geste, jamais à l'envoi.
 */
internal fun ulidBody(nowMillis: Long, random: Random = Random.Default): String {
    val characters = CharArray(26)
    var remaining = nowMillis
    for (index in 9 downTo 0) {
        characters[index] = CROCKFORD[(remaining % 32L).toInt()]
        remaining /= 32L
    }
    for (index in 10 until 26) {
        characters[index] = CROCKFORD[random.nextInt(CROCKFORD.length)]
    }
    return String(characters)
}

/**
 * L'instant déclaré, à la seconde, décalage horaire compris. Le serveur
 * conserve les deux : l'instant absolu et le décalage sous lequel il a été
 * vécu — « 22 h un mardi » n'a pas le même sens que « 22 h UTC ».
 */
fun formatOccurredAt(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
        .truncatedTo(ChronoUnit.SECONDS)
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
