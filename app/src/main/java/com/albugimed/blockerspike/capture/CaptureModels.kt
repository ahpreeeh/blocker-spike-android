package com.albugimed.blockerspike.capture

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.random.Random

private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

/**
 * Un ULID préfixé `cap_`, **frappé au moment du partage** et non de l'envoi.
 *
 * Même mécanique que `newEventId`, et la même erreur à ne pas commettre :
 * déplacer cet appel vers l'envoi ferait d'un réessai une seconde capture.
 * L'index unique `(user_id, capture_id)` du serveur ne pourrait plus rien.
 */
fun newCaptureId(nowMillis: Long, random: Random = Random.Default): String {
    val characters = CharArray(26)
    var remaining = nowMillis
    for (index in 9 downTo 0) {
        characters[index] = CROCKFORD[(remaining % 32L).toInt()]
        remaining /= 32L
    }
    for (index in 10 until 26) {
        characters[index] = CROCKFORD[random.nextInt(CROCKFORD.length)]
    }
    return "cap_" + String(characters)
}

/**
 * Les deux types que cette version sait produire. Le serveur, lui, accepte
 * n'importe quelle chaîne : c'est ce qui permettra d'en ajouter un troisième
 * sans attendre un déploiement du serveur (contrat §16 règle 2).
 */
object CaptureKind {
    const val TEXT = "text"
    const val LINK = "link"
}

/** Plafond du contrat §16 : au-delà, ce n'est plus une capture rapide. */
const val MAX_CAPTURE_TEXT_LENGTH = 20_000

/**
 * Ce qui a été partagé, tel qu'il a été partagé.
 *
 * Rien n'est interprété ici : ni catégorie, ni destination, ni rattachement.
 * P4-06 — capturer enregistre l'information brute et ne modifie rien
 * ailleurs. Le tri se fait plus tard, à la main, dans l'atelier.
 */
data class Capture(
    val captureId: String,
    val kind: String,
    val capturedAtMillis: Long,
    val text: String,
    val subject: String? = null,
    val sourcePackage: String? = null,
)

/**
 * Décide du `kind` à partir du texte partagé.
 *
 * Android ne dit pas si un `text/plain` est un lien : la plupart des
 * applications partagent « un titre https://… ». On regarde donc le contenu,
 * et en cas de doute on choisit `text` — le type le moins engageant.
 */
fun inferCaptureKind(text: String): String =
    if (Regex("""\bhttps?://\S""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
        CaptureKind.LINK
    } else {
        CaptureKind.TEXT
    }

/** L'instant du partage, décalage horaire compris — même règle qu'au §6. */
fun formatCapturedAt(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
        .truncatedTo(ChronoUnit.SECONDS)
        .toString()

/**
 * Construit une capture à partir de ce qu'un `ACTION_SEND` a apporté.
 *
 * Renvoie `null` si rien d'exploitable n'est arrivé : une capture vide
 * occuperait la corbeille sans rien dire, et le serveur la refuserait de
 * toute façon. Le texte est tronqué, jamais rejeté pour sa longueur —
 * couper vaut mieux que perdre.
 */
fun captureFromShare(
    text: String?,
    subject: String?,
    sourcePackage: String?,
    nowMillis: Long,
    random: Random = Random.Default,
): Capture? {
    val body = text?.trim().orEmpty()
    val title = subject?.trim()?.takeIf { it.isNotEmpty() }
    if (body.isEmpty() && title == null) return null

    val content = body.ifEmpty { title.orEmpty() }
    return Capture(
        captureId = newCaptureId(nowMillis, random),
        kind = inferCaptureKind(content),
        capturedAtMillis = nowMillis,
        text = content.take(MAX_CAPTURE_TEXT_LENGTH),
        subject = title,
        sourcePackage = sourcePackage?.takeIf { it.isNotBlank() },
    )
}

/** Verdict du serveur pour une capture — contrat §16. */
data class CaptureResult(val captureId: String, val status: String, val reason: String?) {
    /** `duplicate` est un **succès** : c'est la preuve que le rejeu marche. */
    val isSettled: Boolean get() = status == "accepted" || status == "duplicate"
    val isRejected: Boolean get() = status == "rejected"
}

sealed interface CaptureDelivery {
    data class Answered(val results: List<CaptureResult>) : CaptureDelivery

    data object Unauthorized : CaptureDelivery

    /** Le lot est trop volumineux : le couper, ne rien abandonner. */
    data object TooLarge : CaptureDelivery

    data class Failed(val reason: String, val retryable: Boolean) : CaptureDelivery
}
