package com.albugimed.blockerspike.study

import com.albugimed.blockerspike.sync.LastWork
import com.albugimed.blockerspike.sync.LastWorkUnit
import com.albugimed.blockerspike.sync.ResourceRef
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

internal fun lastWorkDisplayLabel(
    lastWork: LastWork,
    currentResource: ResourceRef? = null,
    now: Instant = Instant.now(),
): String {
    val facts = mutableListOf(lastWork.unit.displayLabel())
    resourceIdentity(lastWork.resourceId, currentResource)?.let(facts::add)
    relativeAge(lastWork.occurredAt, now)?.let(facts::add)
    return facts.joinToString(", ")
}

private fun resourceIdentity(
    lastWorkResourceId: String?,
    currentResource: ResourceRef?,
): String? {
    val resourceId = lastWorkResourceId?.safeDisplayText()?.takeIf(String::isNotEmpty)
        ?: return null
    return when {
        currentResource?.resourceId == lastWorkResourceId ->
            currentResource.label.safeDisplayText().ifEmpty { resourceId }

        currentResource == null -> "ressource $resourceId"
        else -> "autre ressource ($resourceId)"
    }
}

internal fun LastWorkUnit.displayLabel(): String = when (this) {
    is LastWorkUnit.Pages -> if (from == to) {
        "page $from"
    } else {
        "pages $from → $to"
    }

    LastWorkUnit.Chapter -> "chapitre entier"
    is LastWorkUnit.Annale -> "annale « ${label.safeDisplayText()} »"
    is LastWorkUnit.Cards -> if (count == 1) "1 carte" else "$count cartes"
    is LastWorkUnit.Free -> label.safeDisplayText()
    is LastWorkUnit.Unknown -> rawJson.safeDisplayText().ifEmpty { "unité non reconnue" }
}

private fun relativeAge(occurredAt: String, now: Instant): String? {
    val instant = runCatching { OffsetDateTime.parse(occurredAt).toInstant() }
        .recoverCatching { Instant.parse(occurredAt) }
        .getOrNull()
        ?: return null
    val elapsed = Duration.between(instant, now)
    if (elapsed.isNegative) return null

    val minutes = elapsed.toMinutes()
    return when {
        minutes < 1 -> "à l'instant"
        minutes < 60 -> "il y a $minutes min"
        minutes < 24 * 60 -> "il y a ${elapsed.toHours()} h"
        else -> "il y a ${elapsed.toDays()} j"
    }
}

private fun String.safeDisplayText(): String =
    trim()
        .replace(Regex("\\s+"), " ")
        .take(MAX_DISPLAY_LABEL_LENGTH)

private const val MAX_DISPLAY_LABEL_LENGTH = 120
