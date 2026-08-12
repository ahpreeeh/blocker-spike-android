package com.albugimed.blockerspike.study

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.reader.ReaderActivity
import com.albugimed.blockerspike.reader.ReadingPosition
import com.albugimed.blockerspike.sync.EnrolOutcome
import com.albugimed.blockerspike.sync.QueueItem
import com.albugimed.blockerspike.sync.SyncState
import com.albugimed.blockerspike.ui.Notice
import com.albugimed.blockerspike.ui.NoticeTone
import com.albugimed.blockerspike.ui.PrimaryAction
import com.albugimed.blockerspike.ui.ScreenHeader
import com.albugimed.blockerspike.ui.SecondaryAction
import com.albugimed.blockerspike.ui.Section
import com.albugimed.blockerspike.ui.SurfaceCard
import com.albugimed.blockerspike.ui.mutedColor
import com.albugimed.blockerspike.ui.theme.AlbugimedTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Le parcours, ouvert seul.
 *
 * Depuis V2.3 le parcours est surtout un onglet de la coque
 * (`com.albugimed.blockerspike.ui.AppShell`). Cette activite reste declaree
 * pour les entrees externes — un raccourci, une notification — et se contente
 * d'habiller le meme composable. C'est pour cela que [StudyQueueScreen] ne
 * porte plus de `Scaffold` : c'est l'hote qui en fournit un.
 */
class StudyQueueActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = StudyDependencies.repository(applicationContext)
        setContent {
            AlbugimedTheme {
                val positions by Graph.readingPositions.positions
                    .collectAsStateWithLifecycle(initialValue = emptyMap())
                Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                        StudyQueueScreen(
                            repository = repository,
                            onDeclare = { queueItem ->
                                startActivity(
                                DeclareActivity.intent(
                                    this@StudyQueueActivity,
                                    queueItem.stepId,
                                ),
                            )
                            },
                            onDeclareFree = {
                                startActivity(
                                    DeclareActivity.freeIntent(this@StudyQueueActivity),
                                )
                            },
                            positions = positions,
                            onResume = { queueItem ->
                                queueItem.resource?.let { resource ->
                                    startActivity(
                                        ReaderActivity.intent(
                                            context = this@StudyQueueActivity,
                                            resourceId = resource.resourceId,
                                            resourceLabel = resource.label,
                                            stepId = queueItem.stepId,
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, StudyQueueActivity::class.java)
    }
}

@Composable
internal fun StudyQueueScreen(
    repository: StudyRepository,
    onDeclare: (QueueItem) -> Unit,
    onDeclareFree: () -> Unit,
    positions: Map<String, ReadingPosition> = emptyMap(),
    onResume: (QueueItem) -> Unit = {},
) {
    val state by repository.queueState.collectAsStateWithLifecycle(
        initialValue = StudyQueueState(),
    )
    val syncState by repository.syncState.collectAsStateWithLifecycle(
        initialValue = SyncState(),
    )
    val scope = rememberCoroutineScope()
    var showRejections by remember { mutableStateOf(false) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var retrying by remember { mutableStateOf(false) }

    // L'identifiant plutot que l'objet : la liste se rafraichit sous la feuille
    // ouverte, et c'est la version rechargee qu'il faut afficher.
    var openStepId by rememberSaveable { mutableStateOf<String?>(null) }
    val openStep = state.items.firstOrNull { it.stepId == openStepId }
    val path = remember(state) { buildPathView(state) }

    // L'ordre en cours de manipulation. `null` = on ne reordonne pas. Il vit
    // ici et pas dans le magasin : tant qu'on n'a pas dit « Terminé », rien ne
    // part, et quitter l'ecran annule — deplacer une etape par erreur ne doit
    // pas se payer d'un aller-retour reseau.
    var draftOrder by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val rows = draftOrder?.let { order ->
        val byId = path.rows.associateBy { it.item.stepId }
        order.mapNotNull(byId::get) + path.rows.filterNot { it.item.stepId in order }
    } ?: path.rows

    LaunchedEffect(repository) {
        runCatching { repository.onQueueOpened() }
            .onFailure {
                operationError = "Actualisation impossible. La liste en cache reste utilisable."
            }
    }

    if (!syncState.enrolled || syncState.halted) {
        EnrolmentScreen(
            repository = repository,
            syncState = syncState,
            queueState = state,
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Parcours",
                subtitle = "Dans l'ordre que tu as donné. L'application ne le change pas.",
            )
        }
        item {
            Text(
                pathCountsLabel(path),
                style = MaterialTheme.typography.bodySmall,
                color = mutedColor,
            )
        }
        item {
            QueueStatusCard(
                state = state,
                showRejections = showRejections,
                retrying = retrying,
                onToggleRejections = { showRejections = !showRejections },
                onRetry = {
                    scope.launch {
                        retrying = true
                        operationError = null
                        runCatching { repository.retryPending() }
                            .onFailure {
                                operationError =
                                    "Nouvel essai impossible. Les éléments restent en attente."
                            }
                        retrying = false
                    }
                },
            )
        }
        operationError?.let { error ->
            item {
                Notice(error, tone = NoticeTone.PROBLEM)
            }
        }
        if (path.pendingPours > 0) {
            item {
                // On ne peut pas les afficher : les identifiants d'etape sont
                // frappes par l'atelier. Inventer des lignes en attendant
                // montrerait un parcours que personne n'a.
                Notice(pouringLabel(path.pendingPours))
            }
        }
        item {
            SecondaryAction(text = "Déclarer autre chose", onClick = onDeclareFree)
        }
        if (rows.isEmpty()) {
            item {
                Text(
                    "Le parcours est vide. Envoie des matières dedans depuis l'onglet " +
                        "Matières, puis mets-les dans l'ordre que tu veux.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = mutedColor,
                )
            }
        } else {
            item {
                ReorderBar(
                    reordering = draftOrder != null,
                    onStart = { draftOrder = rows.map { it.item.stepId } },
                    onCancel = { draftOrder = null },
                    onConfirm = {
                        val ordered = draftOrder.orEmpty()
                        draftOrder = null
                        scope.launch {
                            operationError = null
                            runCatching { repository.reorderPath(ordered) }
                                .onFailure {
                                    operationError =
                                        "Nouvel ordre non enregistré. Le parcours n'a pas bougé."
                                }
                        }
                    },
                )
            }
            // Une carte, des rangs — et non une carte par etape. Chaque etape
            // occupait un ecran entier de defilement : on ne voyait jamais sa
            // liste, seulement le morceau sous le pouce.
            //
            // Tout est montre, terminees comprises et a leur place : c'est la
            // difference entre un parcours et une file d'attente.
            item {
                SurfaceCard {
                    rows.forEachIndexed { index, row ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        StepRow(
                            item = row.item,
                            done = row.done,
                            onOpen = { openStepId = row.item.stepId },
                            onToggleDone = { done ->
                                scope.launch {
                                    operationError = null
                                    runCatching { repository.setStepDone(row.item.stepId, done) }
                                        .onFailure {
                                            operationError =
                                                "Coche non enregistrée. Rien n'a changé."
                                        }
                                }
                            },
                            moves = draftOrder?.let {
                                RowMoves(
                                    canMoveUp = index > 0,
                                    canMoveDown = index < rows.size - 1,
                                    onMoveUp = {
                                        draftOrder = rows.map { r -> r.item.stepId }
                                            .swapped(index, index - 1)
                                    },
                                    onMoveDown = {
                                        draftOrder = rows.map { r -> r.item.stepId }
                                            .swapped(index, index + 1)
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    openStep?.let { step ->
        StepSheet(
            item = step,
            position = step.resource?.let { positions[it.resourceId] },
            onDismiss = { openStepId = null },
            onResume = {
                openStepId = null
                onResume(step)
            },
            onDeclare = {
                openStepId = null
                onDeclare(step)
            },
        )
    }
}

/**
 * La barre du reordonnancement.
 *
 * Un mode, et non des fleches posees en permanence sur chaque rang : une liste
 * dont chaque ligne porte deux boutons cesse d'etre lisible, et c'est
 * exactement ce que la refonte des rangs a corrige. Tant qu'on n'a pas dit
 * « Terminé », rien ne part — l'ordre affiche est un brouillon.
 */
@Composable
private fun ReorderBar(
    reordering: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!reordering) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onStart) { Text("Réordonner") }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Déplace les étapes, puis valide.",
            style = MaterialTheme.typography.bodySmall,
            color = mutedColor,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Annuler") }
            TextButton(onClick = onConfirm) { Text("Terminé") }
        }
    }
}

/** Deux rangs echanges — et non « monte d'un cran », qui ne dirait rien de sur. */
private fun List<String>.swapped(from: Int, to: Int): List<String> {
    if (from !in indices || to !in indices) return this
    return toMutableList().also { it[from] = this[to]; it[to] = this[from] }
}

@Composable
private fun EnrolmentScreen(
    repository: StudyRepository,
    syncState: SyncState,
    queueState: StudyQueueState,
) {
    var baseUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var enrolling by remember { mutableStateOf(false) }
    var outcomeMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(
            title = "Parcours",
            subtitle = "Cet appareil n'est pas encore relié à l'atelier.",
        )
        if (syncState.halted) {
            Notice("Jeton refusé par le serveur.", tone = NoticeTone.PROBLEM)
            if (queueState.outboxStorageHealthy) {
                Notice("${pendingLabel(queueState.pendingCount)} — rien n'est perdu.")
            } else {
                Notice("État de la file d'envoi illisible.", tone = NoticeTone.PROBLEM)
            }
        }
        Section(title = "Enrôler cet appareil", kicker = "Une seule fois") {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Adresse HTTPS de l'atelier") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Jeton de l'appareil") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
            if (enrolling) {
                CircularProgressIndicator()
            } else {
                PrimaryAction(
                    text = "Enrôler l'appareil",
                    enabled = baseUrl.isNotBlank() && token.isNotBlank(),
                    onClick = {
                        scope.launch {
                            enrolling = true
                            outcomeMessage = null
                            runCatching { repository.enrolDevice(baseUrl, token) }
                                .onSuccess { outcome ->
                                    outcomeMessage = enrolmentMessage(outcome)
                                }
                                .onFailure {
                                    outcomeMessage = "Enrôlement interrompu avant vérification."
                                }
                            enrolling = false
                        }
                    },
                )
            }
            outcomeMessage?.let { message ->
                Notice(message, tone = NoticeTone.PROBLEM)
            }
        }
    }
}

@Composable
private fun QueueStatusCard(
    state: StudyQueueState,
    showRejections: Boolean,
    retrying: Boolean,
    onToggleRejections: () -> Unit,
    onRetry: () -> Unit,
) {
    SurfaceCard {
        Text(
            cacheFreshnessLabel(state.cachedAtMillis),
            style = MaterialTheme.typography.bodySmall,
            color = mutedColor,
        )
        if (state.skippedQueueItems > 0) {
            Notice(
                "${state.skippedQueueItems} étape(s) reçue(s) illisible(s)",
                tone = NoticeTone.PROBLEM,
            )
        }
        if (state.skippedQueueNodes > 0) {
            Notice(
                "${state.skippedQueueNodes} matière(s) ou chapitre(s) reçu(s) illisible(s)",
                tone = NoticeTone.PROBLEM,
            )
        }
        if (!state.outboxStorageHealthy) {
            Notice("État de la file d'envoi illisible.", tone = NoticeTone.PROBLEM)
        }
        if (state.unreadableCount > 0) {
            Notice(
                "${state.unreadableCount} trace(s) locale(s) illisible(s)",
                tone = NoticeTone.PROBLEM,
            )
        }
        if (state.pendingCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(pendingLabel(state.pendingCount), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry, enabled = !retrying) {
                    Text(if (retrying) "Nouvel essai…" else "Réessayer")
                }
            }
        }
        // Le compteur de rejets n'est pas decoratif : c'est la garantie
        // visible que rien ne disparait en silence (§7.1 point 3).
        if (state.rejectedEvents.isNotEmpty()) {
            TextButton(onClick = onToggleRejections) {
                Text(rejectedLabel(state.rejectedEvents.size))
            }
            if (showRejections) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                state.rejectedEvents.forEach { rejected ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            rejected.event.eventId,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            rejected.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedColor,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Le libelle du bouton du lecteur.
 *
 * Sans document rattache il dit ce qu'il va falloir faire, et surtout PAS
 * "Reprendre" : rien ne ramene encore a une page, et le mot serait un
 * mensonge. Avec un document, il nomme la page plutot que l'action seule —
 * "Reprendre" tout court obligerait a ouvrir pour savoir ou l'on atterrit.
 */
internal fun resumeButtonLabel(position: ReadingPosition?): String = when {
    position == null -> "Rattacher un PDF"
    position.pageCount > 0 -> "Reprendre page ${position.page} / ${position.pageCount}"
    else -> "Reprendre page ${position.page}"
}

internal fun cacheFreshnessLabel(cachedAtMillis: Long?): String {
    if (cachedAtMillis == null) return "Liste à jour du —"
    val formatter = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.FRENCH)
    val cachedAt = Instant.ofEpochMilli(cachedAtMillis).atZone(ZoneId.systemDefault())
    return "Liste à jour du ${formatter.format(cachedAt)}"
}

internal fun pendingLabel(count: Int): String = "$count en attente d'envoi"

/**
 * Le versement en cours.
 *
 * Il se dit et ne se montre pas : les etapes n'existent qu'une fois creees par
 * l'atelier, et les afficher par avance ferait promettre un parcours qui n'est
 * pas encore la.
 */
internal fun pouringLabel(count: Int): String =
    if (count == 1) "Une matière part dans le parcours. Elle apparaîtra à la prochaine synchro."
    else "$count matières partent dans le parcours. Elles apparaîtront à la prochaine synchro."

internal fun rejectedLabel(count: Int): String =
    if (count == 1) "1 rejeté" else "$count rejetés"

internal fun enrolmentMessage(outcome: EnrolOutcome): String? = when (outcome) {
    is EnrolOutcome.Enrolled -> null
    EnrolOutcome.InvalidUrl -> "Adresse invalide : utilise une adresse HTTPS."
    EnrolOutcome.InvalidToken -> "Jeton invalide : vérifie les 32 caractères."
    EnrolOutcome.Rejected -> "Jeton refusé par le serveur."
    EnrolOutcome.ServerTooOld -> "Le serveur ne fournit pas l'identité de l'appareil."
    is EnrolOutcome.Unreachable -> "Serveur injoignable : ${outcome.reason}"
}

internal fun String.displayKindLabel(): String = when (this) {
    "first_study" -> "Première étude"
    "revision" -> "Révision"
    "training" -> "Entraînement"
    "reading" -> "Lecture"
    else -> this
}
