package com.albugimed.blockerspike.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.albugimed.blockerspike.ui.Chevron
import com.albugimed.blockerspike.ui.Kicker
import com.albugimed.blockerspike.ui.Notice
import com.albugimed.blockerspike.ui.NoticeTone
import com.albugimed.blockerspike.ui.PrimaryAction
import com.albugimed.blockerspike.ui.ScreenHeader
import com.albugimed.blockerspike.ui.SecondaryAction
import com.albugimed.blockerspike.ui.Section
import com.albugimed.blockerspike.ui.ToggleRow
import com.albugimed.blockerspike.ui.mutedColor
import com.albugimed.blockerspike.sync.AGENDA_KINDS
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * L'agenda : une semaine, verticale, qu'on déroule jour après jour.
 *
 * C'est la même semaine que celle de l'atelier web, tenue par le même magasin
 * synchronisé — le téléphone la donne à la verticale parce qu'un écran de
 * téléphone ne tient pas sept colonnes lisibles, l'ordinateur la donne à
 * l'horizontale. Une seule matière, deux formes.
 *
 * La grande date reste en tête : c'est ce que promet l'interrupteur du
 * formulaire, et une échéance qu'on cherche ne se cherche pas.
 *
 * Ce que l'écran ne fait toujours pas, volontairement : placer du travail dans
 * des créneaux, classer par urgence, signaler du retard. Le cadrage §1.1 et §8
 * l'excluent — l'application n'a pas d'avis sur l'ordre, elle tient le temps.
 */
@Composable
fun AgendaScreen(repository: StudyRepository) {
    val agendaState by repository.agendaState.collectAsStateWithLifecycle(
        initialValue = AgendaState(),
    )
    val entriesState by repository.agendaEntriesState.collectAsStateWithLifecycle(
        initialValue = AgendaEntriesState(),
    )
    val scope = rememberCoroutineScope()

    val summary = buildAgendaHeaderPresentation(agendaState)
    // L'instant de référence est figé à la composition : recalculé à chaque
    // image, il ferait glisser la semaine sous les doigts en plein défilé.
    val nowMillis = remember { System.currentTimeMillis() }

    // Le feuilletage est un décalage en semaines, pas une date retenue : revenir
    // à zéro ramène toujours à la semaine d'aujourd'hui, même le lendemain.
    var weekOffset by remember { mutableIntStateOf(0) }
    val week = buildAgendaWeekPresentation(entriesState, nowMillis, weekOffset)

    var editing by remember { mutableStateOf<AgendaDraft?>(null) }
    var creating by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val byId = entriesState.entries.associateBy { it.id }

    LazyColumnAgenda(
        summary = summary,
        week = week,
        pendingCount = entriesState.pendingCount,
        saveError = saveError,
        onCreate = { creating = true },
        onOpen = { id -> editing = byId[id]?.toDraft() },
        onWeekStep = { step -> weekOffset += step },
        onThisWeek = { weekOffset = 0 },
    )

    if (creating || editing != null) {
        AgendaEditorSheet(
            draft = editing,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { draft ->
                scope.launch {
                    saveError = runCatching { repository.saveAgendaEntry(draft) }
                        .fold(
                            onSuccess = {
                                creating = false
                                editing = null
                                // L'envoi est une tentative séparée : il ne
                                // conditionne jamais le succès de la saisie.
                                repository.syncAfterLocalSave()
                                null
                            },
                            onFailure = { "Saisie non enregistrée sur l'appareil. Rien n'est perdu du côté serveur." },
                        )
                }
            },
            onDelete = { id ->
                scope.launch {
                    saveError = runCatching { repository.deleteAgendaEntry(id) }
                        .fold(
                            onSuccess = {
                                creating = false
                                editing = null
                                repository.syncAfterLocalSave()
                                null
                            },
                            onFailure = { "Retrait non enregistré sur l'appareil." },
                        )
                }
            },
        )
    }
}

@Composable
private fun LazyColumnAgenda(
    summary: AgendaHeaderPresentation,
    week: AgendaWeekPresentation,
    pendingCount: Int,
    saveError: String?,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    onWeekStep: (Int) -> Unit,
    onThisWeek: () -> Unit,
) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Agenda",
                subtitle = "Ce qui est déjà posé. L'application n'y ajoute rien toute seule.",
                trailing = { TextButton(onClick = onCreate) { Text("Ajouter") } },
            )
        }

        item {
            Text(
                summary.freshnessLabel,
                style = MaterialTheme.typography.bodySmall,
                color = mutedColor,
            )
        }

        saveError?.let { item { Notice(it, tone = NoticeTone.PROBLEM) } }
        items(summary.warnings.size) { index ->
            Notice(summary.warnings[index], tone = NoticeTone.PROBLEM)
        }
        items(week.warnings.size) { index ->
            Notice(week.warnings[index], tone = NoticeTone.PROBLEM)
        }

        if (pendingCount > 0) {
            item {
                // Ni alarme ni couleur : c'est un fait, pas un problème. La
                // saisie est acquise sur l'appareil, elle attend le réseau.
                Notice("$pendingCount modification(s) en attente d'envoi.")
            }
        }

        item {
            Section(title = "Prochaine échéance", kicker = "La grande date") {
                val nextLock = summary.nextLock
                if (nextLock == null) {
                    Text(
                        "Aucune échéance connue.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = mutedColor,
                    )
                } else {
                    AgendaRowBlock(nextLock)
                }
            }
        }

        item { WeekNavigation(week, onStep = onWeekStep, onThisWeek = onThisWeek) }

        // Sept jours, y compris les vides : le creux du jeudi est une
        // information, le masquer donnerait une file au lieu d'une semaine.
        items(week.days.size) { index ->
            val day = week.days[index]
            Section(
                title = day.heading.replaceFirstChar { it.uppercase() },
                kicker = if (day.isToday) "Aujourd'hui" else null,
            ) {
                if (day.entries.isEmpty()) {
                    Text("—", style = MaterialTheme.typography.bodyMedium, color = mutedColor)
                } else {
                    day.entries.forEachIndexed { position, entry ->
                        if (position > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        AgendaEntryRow(entry, onOpen)
                    }
                }
            }
        }
    }
}

/**
 * Le feuilletage des semaines.
 *
 * « Cette semaine » s'éteint quand on y est déjà : un bouton qui ne fait rien
 * se présente comme tel plutôt que de laisser croire à une panne.
 */
@Composable
private fun WeekNavigation(
    week: AgendaWeekPresentation,
    onStep: (Int) -> Unit,
    onThisWeek: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = { onStep(-1) }) { Text("Précédente") }
            TextButton(enabled = !week.isCurrentWeek, onClick = onThisWeek) {
                Text("Cette semaine")
            }
            TextButton(onClick = { onStep(1) }) { Text("Suivante") }
        }
        Text(week.title, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Une entrée d'agenda, rendue comme un fait.
 *
 * Aucune couleur d'alerte, aucun badge : le cadrage §1.1 interdit de
 * hierarchiser. « Demain 7 h » se lit deja tout seul.
 */
@Composable
internal fun AgendaRowBlock(row: AgendaRowPresentation) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Kicker(row.kindLabel)
        Text(row.label, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                row.timeLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = mutedColor,
            )
        }
        row.location?.takeIf { it.isNotBlank() }?.let {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(it, style = MaterialTheme.typography.bodySmall, color = mutedColor)
        }
    }
}

/** La même chose, mais ouvrable : c'est la matière, elle se corrige. */
@Composable
private fun AgendaEntryRow(entry: AgendaEntryPresentation, onOpen: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(entry.id) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Kicker(if (entry.isLock) "${entry.kindLabel} · Grande date" else entry.kindLabel)
            Text(
                entry.label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(entry.timeLabel, style = MaterialTheme.typography.bodyMedium, color = mutedColor)
            entry.location?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = mutedColor)
            }
            if (entry.pending) {
                Text(
                    "En attente d'envoi",
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedColor,
                )
            }
        }
        Chevron()
    }
}

/**
 * Le formulaire.
 *
 * Un créneau se saisit avec un **début et une fin explicites, date comprise**.
 * Déduire la date de fin de celle du début rendrait une garde de nuit
 * impossible à noter correctement — et une garde de nuit est exactement le cas
 * pour lequel cet agenda existe.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgendaEditorSheet(
    draft: AgendaDraft?,
    onDismiss: () -> Unit,
    onSave: (AgendaDraft) -> Unit,
    onDelete: (String) -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val today = remember { LocalDate.now(zone) }

    var label by remember { mutableStateOf(draft?.label.orEmpty()) }
    var kind by remember { mutableStateOf(draft?.kind ?: "cours") }
    var allDay by remember { mutableStateOf(draft?.allDay ?: false) }
    var isLock by remember { mutableStateOf(draft?.isLock ?: false) }
    var location by remember { mutableStateOf(draft?.location.orEmpty()) }

    var startDate by remember {
        mutableStateOf(
            draft?.startDate
                ?: draft?.startsAt?.atZoneSameInstant(zone)?.toLocalDate()
                ?: today,
        )
    }
    var endDate by remember {
        mutableStateOf(
            draft?.endDate
                ?: draft?.endsAt?.atZoneSameInstant(zone)?.toLocalDate()
                ?: startDate,
        )
    }
    var startTime by remember {
        mutableStateOf(
            draft?.startsAt?.atZoneSameInstant(zone)?.toLocalTime() ?: LocalTime.of(8, 0),
        )
    }
    var endTime by remember {
        mutableStateOf(
            draft?.endsAt?.atZoneSameInstant(zone)?.toLocalTime() ?: LocalTime.of(10, 0),
        )
    }

    var picking by remember { mutableStateOf(PickerTarget.NONE) }

    val startsAt = startDate.atTime(startTime).atZone(zone).toOffsetDateTime()
    val endsAt = endDate.atTime(endTime).atZone(zone).toOffsetDateTime()
    val problem = when {
        label.isBlank() -> "Il faut un intitulé."
        allDay && endDate.isBefore(startDate) -> "La fin est avant le début."
        !allDay && !endsAt.toInstant().isAfter(startsAt.toInstant()) ->
            "La fin doit être après le début."
        else -> null
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (draft == null) "Poser une échéance" else "Corriger l'échéance",
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Intitulé") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Kicker("Nature")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AGENDA_KINDS.forEach { candidate ->
                        FilterChip(
                            selected = kind == candidate,
                            onClick = { kind = candidate },
                            label = { Text(candidate.displayAgendaKindLabel()) },
                        )
                    }
                }
            }

            ToggleRow(
                title = "Journée entière",
                subtitle = "Sans horaire : un stage, un congé, une date.",
                checked = allDay,
                onCheckedChange = { allDay = it },
            )

            if (allDay) {
                PickerRow("Du", formatDate(startDate)) { picking = PickerTarget.START_DATE }
                PickerRow("Au", formatDate(endDate)) { picking = PickerTarget.END_DATE }
            } else {
                PickerRow("Début", formatDate(startDate)) { picking = PickerTarget.START_DATE }
                PickerRow("Heure de début", formatTime(startTime)) {
                    picking = PickerTarget.START_TIME
                }
                PickerRow("Fin", formatDate(endDate)) { picking = PickerTarget.END_DATE }
                PickerRow("Heure de fin", formatTime(endTime)) { picking = PickerTarget.END_TIME }
            }

            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text("Lieu (facultatif)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            ToggleRow(
                title = "Grande date",
                subtitle = "Elle apparaîtra en tête d'écran. Rien d'autre ne change.",
                checked = isLock,
                onCheckedChange = { isLock = it },
            )

            problem?.let { Notice(it, tone = NoticeTone.PROBLEM) }

            PrimaryAction(
                text = "Enregistrer",
                enabled = problem == null,
                onClick = {
                    onSave(
                        AgendaDraft(
                            id = draft?.id,
                            label = label,
                            kind = kind,
                            allDay = allDay,
                            startsAt = if (allDay) null else startsAt,
                            endsAt = if (allDay) null else endsAt,
                            startDate = if (allDay) startDate else null,
                            endDate = if (allDay) endDate else null,
                            isLock = isLock,
                            location = location,
                        ),
                    )
                },
            )

            draft?.id?.let { id ->
                SecondaryAction(text = "Retirer", onClick = { onDelete(id) })
            }
        }
    }

    when (picking) {
        PickerTarget.NONE -> Unit
        PickerTarget.START_DATE -> DatePickerSheet(startDate, { picking = PickerTarget.NONE }) {
            startDate = it
            // La fin ne peut pas précéder le début : la pousser évite un état
            // invalide qu'il faudrait ensuite deviner et corriger à la main.
            if (endDate.isBefore(it)) endDate = it
        }
        PickerTarget.END_DATE -> DatePickerSheet(endDate, { picking = PickerTarget.NONE }) {
            endDate = it
        }
        PickerTarget.START_TIME -> TimePickerSheet(startTime, { picking = PickerTarget.NONE }) {
            startTime = it
        }
        PickerTarget.END_TIME -> TimePickerSheet(endTime, { picking = PickerTarget.NONE }) {
            endTime = it
        }
    }
}

private enum class PickerTarget { NONE, START_DATE, END_DATE, START_TIME, END_TIME }

@Composable
private fun PickerRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = mutedColor,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
        Chevron()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    // Le sélecteur raisonne en millisecondes **UTC** de minuit. Le convertir
    // dans le fuseau local ferait basculer la date d'un jour à l'est de
    // Greenwich comme à l'ouest ; le décalage n'est pas une opinion ici.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onPick(
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(),
                        )
                    }
                    onDismiss()
                },
            ) { Text("Valider") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerSheet(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onPick: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onPick(LocalTime.of(state.hour, state.minute))
                    onDismiss()
                },
            ) { Text("Valider") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        text = { TimePicker(state = state) },
    )
}

private fun formatDate(date: LocalDate): String =
    DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH).format(date)

private fun formatTime(time: LocalTime): String =
    DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH).format(time)
