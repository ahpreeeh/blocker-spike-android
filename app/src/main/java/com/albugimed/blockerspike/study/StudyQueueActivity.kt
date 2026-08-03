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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.albugimed.blockerspike.sync.EnrolOutcome
import com.albugimed.blockerspike.sync.QueueItem
import com.albugimed.blockerspike.sync.SyncState
import com.albugimed.blockerspike.ui.Section
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class StudyQueueActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = StudyDependencies.repository(applicationContext)
        setContent {
            MaterialTheme {
                StudyQueueScreen(
                    repository = repository,
                    onDeclare = { queueItem ->
                        startActivity(DeclareActivity.intent(this, queueItem.stepId))
                    },
                )
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

    LaunchedEffect(repository) {
        runCatching { repository.onQueueOpened() }
            .onFailure { operationError = "Actualisation impossible. La file en cache reste utilisable." }
    }

    if (!syncState.enrolled || syncState.halted) {
        EnrolmentScreen(
            repository = repository,
            syncState = syncState,
            queueState = state,
        )
        return
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("File", style = MaterialTheme.typography.headlineMedium)
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
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
            if (state.items.isEmpty()) {
                item {
                    Text(
                        "La file en cache est vide.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                items(items = state.items, key = QueueItem::stepId) { item ->
                    QueueItemCard(item = item, onClick = { onDeclare(item) })
                }
            }
        }
    }
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

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("File", style = MaterialTheme.typography.headlineMedium)
            if (syncState.halted) {
                Text(
                    "Jeton refusé",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (queueState.outboxStorageHealthy) {
                    Text(pendingLabel(queueState.pendingCount))
                } else {
                    Text(
                        "État de la file d'envoi illisible.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Section("Enrôler cet appareil") {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Adresse HTTPS de l'atelier") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Jeton de l'appareil") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(
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
                    modifier = Modifier.fillMaxWidth(),
                    enabled = baseUrl.isNotBlank() && token.isNotBlank() && !enrolling,
                ) {
                    if (enrolling) {
                        CircularProgressIndicator()
                    } else {
                        Text("Enrôler l'appareil")
                    }
                }
                outcomeMessage?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(cacheFreshnessLabel(state.cachedAtMillis))
            if (state.skippedQueueItems > 0) {
                Text(
                    "${state.skippedQueueItems} étape(s) reçue(s) illisible(s)",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!state.outboxStorageHealthy) {
                Text(
                    "État de la file d'envoi illisible.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.unreadableCount > 0) {
                Text(
                    "${state.unreadableCount} trace(s) locale(s) illisible(s)",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.pendingCount > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(pendingLabel(state.pendingCount))
                    OutlinedButton(onClick = onRetry, enabled = !retrying) {
                        Text(if (retrying) "Nouvel essai…" else "Réessayer")
                    }
                }
            }
            if (state.rejectedEvents.isNotEmpty()) {
                TextButton(onClick = onToggleRejections) {
                    Text(rejectedLabel(state.rejectedEvents.size))
                }
                if (showRejections) {
                    HorizontalDivider()
                    state.rejectedEvents.forEach { rejected ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                rejected.event.eventId,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(rejected.reason, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueItemCard(item: QueueItem, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(item.label, style = MaterialTheme.typography.titleMedium)
            Text(item.subject.label, style = MaterialTheme.typography.bodyMedium)
            Text(item.kind.displayKindLabel(), style = MaterialTheme.typography.bodySmall)
            item.resource?.let { resource ->
                Text("Ressource : ${resource.label}")
            }
            Text(
                "Fraîcheur : ${item.signals.freshnessDays?.let { "$it j" } ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

internal fun cacheFreshnessLabel(cachedAtMillis: Long?): String {
    if (cachedAtMillis == null) return "File à jour du —"
    val formatter = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.FRENCH)
    val cachedAt = Instant.ofEpochMilli(cachedAtMillis).atZone(ZoneId.systemDefault())
    return "File à jour du ${formatter.format(cachedAt)}"
}

internal fun pendingLabel(count: Int): String = "$count en attente d'envoi"

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
