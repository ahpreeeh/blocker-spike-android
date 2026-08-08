package com.albugimed.blockerspike.study

import com.albugimed.blockerspike.sync.AgendaEntry
import com.albugimed.blockerspike.sync.AgendaItem
import com.albugimed.blockerspike.sync.AgendaSnapshot
import com.albugimed.blockerspike.sync.AgendaStoreState
import com.albugimed.blockerspike.sync.CachedAgenda
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
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

// ---------------------------------------------------------------------------
// La matière modifiable — V1.4.
//
// Ce qui suit ne dérive pas de l'instantané du serveur : c'est la copie locale
// des entrées, celle qu'on peut créer, corriger et retirer. Les deux vues
// coexistent à l'écran parce qu'elles répondent à deux questions différentes —
// « qu'est-ce qui arrive ? » et « qu'est-ce qu'il y a ? ».
// ---------------------------------------------------------------------------

data class AgendaEntriesState(
    val entries: List<AgendaEntry> = emptyList(),
    val unreadable: Int = 0,
    val storageHealthy: Boolean = true,
) {
    /** Ce qui n'a pas encore été accusé par le serveur. Jamais tu. */
    val pendingCount: Int get() = entries.count { it.pending }
}

fun AgendaStoreState.toAgendaEntriesState(): AgendaEntriesState = AgendaEntriesState(
    entries = entries,
    unreadable = unreadable,
    storageHealthy = storageHealthy,
)

/** Ce que le formulaire produit. L'heure de modification est posée après. */
data class AgendaDraft(
    /** `null` = création. Une correction garde l'identifiant de l'entrée. */
    val id: String? = null,
    val label: String,
    val kind: String,
    val allDay: Boolean,
    val startsAt: OffsetDateTime? = null,
    val endsAt: OffsetDateTime? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val isLock: Boolean = false,
    val location: String? = null,
)

internal fun AgendaDraft.toEntry(id: String, editedAt: OffsetDateTime): AgendaEntry = AgendaEntry(
    id = id,
    label = label.trim(),
    kind = kind,
    allDay = allDay,
    startsAt = if (allDay) null else startsAt,
    endsAt = if (allDay) null else endsAt,
    startDate = if (allDay) startDate else null,
    endDate = if (allDay) (endDate ?: startDate) else null,
    isLock = isLock,
    location = location?.trim()?.takeIf(String::isNotEmpty),
    editedAt = editedAt,
    deleted = false,
    pending = true,
)

fun AgendaEntry.toDraft(): AgendaDraft = AgendaDraft(
    id = id,
    label = label,
    kind = kind,
    allDay = allDay,
    startsAt = startsAt,
    endsAt = endsAt,
    startDate = startDate,
    endDate = endDate,
    isLock = isLock,
    location = location,
)

data class AgendaEntryPresentation(
    val id: String,
    val label: String,
    val kindLabel: String,
    val timeLabel: String,
    val location: String?,
    val isLock: Boolean,
    /** Vrai tant que le serveur n'a pas accusé cette version. */
    val pending: Boolean,
)

data class AgendaDayPresentation(
    val date: LocalDate,
    /** « jeudi 6 » : le mois et l'année sont déjà dans le titre de la semaine. */
    val heading: String,
    val isToday: Boolean,
    val entries: List<AgendaEntryPresentation>,
)

data class AgendaWeekPresentation(
    /** « 3 – 9 août 2026 ». */
    val title: String,
    /** Toujours sept, du lundi au dimanche, y compris les jours vides. */
    val days: List<AgendaDayPresentation>,
    val isCurrentWeek: Boolean,
    val warnings: List<String>,
)

/**
 * La semaine, jour après jour.
 *
 * C'est **la** vue de l'agenda sur téléphone, et elle est verticale : sept
 * jours qu'on déroule, du lundi au dimanche. La question à laquelle elle
 * répond est « qu'est-ce que j'ai ce jour-là ». Ranger les entrées en paquets
 * — bientôt, plus tard, déjà passé — répond à une autre question et fait
 * perdre celle-là : on ne voit plus les jours, seulement une file.
 *
 * Un jour sans rien reste affiché, vide. **C'est l'information principale** :
 * les trous d'un emploi du temps ne se voient que si les jours creux gardent
 * leur place.
 *
 * Une entrée qui court sur plusieurs jours apparaît sur chacun d'eux, à
 * l'heure vue depuis ce jour-là — « dès 20:00 » le soir de la garde, « jusqu'à
 * 08:00 » le lendemain matin. La répéter en entier ferait croire à deux gardes.
 *
 * Une entrée retirée ne figure nulle part. Elle reste en magasin — le retrait
 * doit pouvoir être défait par une correction plus récente venue du PC — mais
 * elle n'occupe plus de temps.
 *
 * Rien n'est trié par urgence, rien n'est signalé en retard : cadrage §1.1.
 */
fun buildAgendaWeekPresentation(
    state: AgendaEntriesState,
    nowMillis: Long,
    weekOffset: Int = 0,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.FRENCH,
): AgendaWeekPresentation {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    // La semaine commence le lundi. `dayOfWeek.value` vaut 1 le lundi.
    val monday = today
        .minusDays((today.dayOfWeek.value - 1).toLong())
        .plusWeeks(weekOffset.toLong())

    val living = state.entries.filterNot { it.deleted }
    val heading = DateTimeFormatter.ofPattern("EEEE d", locale)

    val days = (0L..6L).map { offset ->
        val date = monday.plusDays(offset)
        AgendaDayPresentation(
            date = date,
            heading = heading.format(date),
            isToday = date == today,
            entries = living
                .mapNotNull { entry -> entry.sliceOn(date, zone, locale)?.let { entry to it } }
                // L'ordre est celui de l'horloge, et rien d'autre. Les journées
                // entières passent devant : elles n'ont pas d'heure à comparer.
                .sortedWith(compareBy({ it.second.sortKey }, { it.first.label }))
                .map { (entry, slice) -> entry.toDayPresentation(slice.timeLabel) },
        )
    }

    val warnings = buildList {
        if (!state.storageHealthy) add("Agenda local illisible.")
        if (state.unreadable > 0) add("${state.unreadable} entrée(s) locale(s) illisible(s).")
    }

    return AgendaWeekPresentation(
        title = weekTitle(monday, locale),
        days = days,
        isCurrentWeek = weekOffset == 0,
        warnings = warnings,
    )
}

private fun weekTitle(monday: LocalDate, locale: Locale): String {
    val sunday = monday.plusDays(6)
    val dayMonth = DateTimeFormatter.ofPattern("d MMMM", locale)
    // Le mois n'est répété que s'il change en cours de semaine.
    val start = if (monday.month == sunday.month) {
        DateTimeFormatter.ofPattern("d", locale).format(monday)
    } else {
        dayMonth.format(monday)
    }
    return "$start – ${dayMonth.format(sunday)} ${sunday.year}"
}

/** Ce qu'une entrée occupe d'un jour donné, ou `null` si elle ne le touche pas. */
private data class DaySlice(val sortKey: Int, val timeLabel: String)

private fun AgendaEntry.sliceOn(date: LocalDate, zone: ZoneId, locale: Locale): DaySlice? {
    if (allDay) {
        val first = startDate ?: return null
        // `end_date` est **inclusive** ici, contrairement à la borne de fin de
        // l'instantané calculé : ne pas les confondre retirerait un jour.
        val last = endDate ?: first
        if (date.isBefore(first) || date.isAfter(last)) return null
        // Les journées entières remontent en tête du jour : sans heure, elles
        // n'ont rien à disputer aux créneaux.
        return DaySlice(sortKey = -1, timeLabel = "Toute la journée")
    }

    val start = startsAt?.atZoneSameInstant(zone) ?: return null
    val end = endsAt?.atZoneSameInstant(zone)
    val firstDay = start.toLocalDate()
    val lastDay = when {
        end == null -> firstDay
        // Une fin à minuit pile ferme la veille. Sans ça, un créneau qui
        // s'arrête à 00:00 déborderait sur un jour où il n'y a rien.
        end.toLocalTime() == LocalTime.MIDNIGHT && end.toLocalDate().isAfter(firstDay) ->
            end.toLocalDate().minusDays(1)
        else -> end.toLocalDate()
    }
    if (date.isBefore(firstDay) || date.isAfter(lastDay)) return null

    val hhmm = DateTimeFormatter.ofPattern("HH:mm", locale)
    val startsHere = date == firstDay
    val endsHere = date == lastDay
    return DaySlice(
        // Un jour traversé de part en part commence à minuit : il se lit avant
        // tout ce qui démarre dans la journée.
        sortKey = if (startsHere) start.hour * 60 + start.minute else 0,
        timeLabel = when {
            end == null -> hhmm.format(start)
            startsHere && endsHere -> "${hhmm.format(start)} → ${hhmm.format(end)}"
            startsHere -> "dès ${hhmm.format(start)}"
            endsHere -> "jusqu'à ${hhmm.format(end)}"
            else -> "Toute la journée"
        },
    )
}

private fun AgendaEntry.toDayPresentation(timeLabel: String): AgendaEntryPresentation =
    AgendaEntryPresentation(
        id = id,
        label = label,
        kindLabel = kind.displayAgendaKindLabel(),
        timeLabel = timeLabel,
        location = location,
        isLock = isLock,
        pending = pending,
    )
