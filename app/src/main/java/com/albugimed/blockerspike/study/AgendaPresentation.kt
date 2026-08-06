package com.albugimed.blockerspike.study

import com.albugimed.blockerspike.sync.AgendaItem
import com.albugimed.blockerspike.sync.AgendaSnapshot
import com.albugimed.blockerspike.sync.CachedAgenda
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

data class AgendaState(
    val cachedAtMillis: Long? = null,
    val snapshot: AgendaSnapshot = AgendaSnapshot.EMPTY,
    val storageHealthy: Boolean = true,
)

data class AgendaRowPresentation(
    val label: String,
    val kindLabel: String,
    val timeLabel: String,
    val location: String?,
)

data class AgendaHeaderPresentation(
    val freshnessLabel: String,
    val nextLock: AgendaRowPresentation?,
    val window48h: List<AgendaRowPresentation>,
    val warnings: List<String>,
)

fun CachedAgenda.toAgendaState(): AgendaState = AgendaState(
    cachedAtMillis = fetchedAtMillis,
    snapshot = snapshot,
    storageHealthy = storageHealthy,
)

fun buildAgendaHeaderPresentation(
    state: AgendaState,
    deviceZone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.FRENCH,
): AgendaHeaderPresentation {
    val zone = state.snapshot.timezone.toZoneIdOrNull() ?: deviceZone
    val warnings = buildList {
        if (!state.storageHealthy) add("Copie locale de l'agenda illisible.")
        if (state.snapshot.malformedFields > 0) {
            add("${state.snapshot.malformedFields} champ(s) d'agenda illisible(s).")
        }
        if (state.snapshot.skippedWindowItems > 0) {
            add("${state.snapshot.skippedWindowItems} élément(s) des 48 h illisible(s).")
        }
    }
    return AgendaHeaderPresentation(
        freshnessLabel = agendaFreshnessLabel(state.cachedAtMillis, zone, locale),
        nextLock = state.snapshot.nextLock?.toPresentation(zone, locale),
        window48h = state.snapshot.window48h.map { it.toPresentation(zone, locale) },
        warnings = warnings,
    )
}

/** La valeur inconnue est rendue telle quelle : c'est le contrat de compatibilité. */
internal fun String.displayAgendaKindLabel(): String = when (this) {
    "cours" -> "Cours"
    "stage" -> "Stage"
    "garde" -> "Garde"
    "rdv" -> "Rendez-vous"
    "autre" -> "Autre"
    else -> this
}

internal fun agendaFreshnessLabel(
    cachedAtMillis: Long?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.FRENCH,
): String {
    if (cachedAtMillis == null) return "Agenda à jour du —"
    val formatter = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
    return "Agenda à jour du ${formatter.format(Instant.ofEpochMilli(cachedAtMillis).atZone(zone))}"
}

private fun AgendaItem.toPresentation(
    zone: ZoneId,
    locale: Locale,
): AgendaRowPresentation = AgendaRowPresentation(
    label = label,
    kindLabel = kind.displayAgendaKindLabel(),
    timeLabel = agendaTimeLabel(zone, locale),
    location = location,
)

internal fun AgendaItem.agendaTimeLabel(
    zone: ZoneId,
    locale: Locale = Locale.FRENCH,
): String {
    val start = startsAt.atZoneSameInstant(zone)
    val end = endsAt?.atZoneSameInstant(zone)
    if (allDay) {
        val date = DateTimeFormatter.ofPattern("dd/MM/yyyy", locale)
        if (end == null) return date.format(start)
        // La borne de fin d'une journée entière est exclusive.
        val inclusiveEnd = end.minusNanos(1)
        return if (start.toLocalDate() == inclusiveEnd.toLocalDate()) {
            date.format(start)
        } else {
            "${date.format(start)} → ${date.format(inclusiveEnd)}"
        }
    }

    val dateTime = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", locale)
    if (end == null) return dateTime.format(start)
    return "${dateTime.format(start)} → ${dateTime.format(end)}"
}

private fun String.toZoneIdOrNull(): ZoneId? =
    takeIf(String::isNotBlank)?.let { runCatching { ZoneId.of(it) }.getOrNull() }
