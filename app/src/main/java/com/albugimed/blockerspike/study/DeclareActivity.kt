package com.albugimed.blockerspike.study

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.albugimed.blockerspike.sync.Difficulty
import com.albugimed.blockerspike.sync.QueueItem
import com.albugimed.blockerspike.ui.Section
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

class DeclareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stepId = intent.getStringExtra(EXTRA_STEP_ID)
        val repository = StudyDependencies.repository(applicationContext)
        setContent {
            MaterialTheme {
                val queue by repository.queueState.collectAsStateWithLifecycle(
                    initialValue = StudyQueueState(),
                )
                val queueItem = queue.items.firstOrNull { it.stepId == stepId }
                if (queueItem == null) {
                    MissingStepScreen(onClose = ::finish)
                } else {
                    DeclareScreen(
                        queueItem = queueItem,
                        repository = repository,
                        onOpenResource = ::openResource,
                        onDone = ::finish,
                    )
                }
            }
        }
    }

    private fun openResource(uri: String): String? = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        null
    } catch (_: ActivityNotFoundException) {
        "Aucune application ne peut ouvrir cette ressource."
    } catch (_: SecurityException) {
        "Android a refusé l'ouverture de cette ressource."
    }

    companion object {
        private const val EXTRA_STEP_ID = "study_step_id"

        fun intent(context: Context, stepId: String): Intent =
            Intent(context, DeclareActivity::class.java)
                .putExtra(EXTRA_STEP_ID, stepId)
    }
}

@Composable
private fun MissingStepScreen(onClose: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Déclaration", style = MaterialTheme.typography.headlineMedium)
            Text("Cette étape n'est plus disponible dans la file en cache.")
            TextButton(onClick = onClose) { Text("Retour à la file") }
        }
    }
}

@Composable
internal fun DeclareScreen(
    queueItem: QueueItem,
    repository: StudyRepository,
    onOpenResource: (String) -> String?,
    onDone: () -> Unit,
) {
    var durationMinutes by rememberSaveable { mutableStateOf("") }
    var unitTypeName by rememberSaveable { mutableStateOf(WorkUnitType.PAGES.name) }
    var pagesFrom by rememberSaveable { mutableStateOf("") }
    var pagesTo by rememberSaveable { mutableStateOf("") }
    var annaleLabel by rememberSaveable { mutableStateOf("") }
    var cardsCount by rememberSaveable { mutableStateOf("") }
    var freeLabel by rememberSaveable { mutableStateOf("") }
    var difficultyName by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var validationMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var resourceError by rememberSaveable { mutableStateOf<String?>(null) }
    var saved by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val unitType = WorkUnitType.valueOf(unitTypeName)

    Scaffold { padding ->
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("Déclarer le travail", style = MaterialTheme.typography.headlineMedium)
            }
            item {
                StepReminder(queueItem)
            }
            queueItem.resource?.openUri?.let { openUri ->
                item {
                    OutlinedButton(
                        onClick = {
                            resourceError = onOpenResource(openUri)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Ouvrir la ressource")
                    }
                    resourceError?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item {
                Section("Durée") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(15, 30, 45, 60).forEach { minutes ->
                            FilterChip(
                                selected = durationMinutes == minutes.toString(),
                                onClick = { durationMinutes = minutes.toString() },
                                label = { Text("$minutes min") },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = durationMinutes,
                        onValueChange = { durationMinutes = it.filter(Char::isDigit) },
                        label = { Text("Durée libre (minutes)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
            item {
                Section("Unité travaillée") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WorkUnitType.entries.forEach { type ->
                            FilterChip(
                                selected = unitType == type,
                                onClick = { unitTypeName = type.name },
                                label = { Text(type.displayLabel()) },
                            )
                        }
                    }
                    when (unitType) {
                        WorkUnitType.PAGES -> Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = pagesFrom,
                                onValueChange = { pagesFrom = it.filter(Char::isDigit) },
                                label = { Text("De") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                ),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = pagesTo,
                                onValueChange = { pagesTo = it.filter(Char::isDigit) },
                                label = { Text("À") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                ),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                        }

                        WorkUnitType.CHAPTER -> Text("Chapitre entier")
                        WorkUnitType.ANNALE -> OutlinedTextField(
                            value = annaleLabel,
                            onValueChange = { annaleLabel = it },
                            label = { Text("Libellé de l'annale") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )

                        WorkUnitType.CARDS -> OutlinedTextField(
                            value = cardsCount,
                            onValueChange = { cardsCount = it.filter(Char::isDigit) },
                            label = { Text("Nombre de cartes") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )

                        WorkUnitType.FREE -> OutlinedTextField(
                            value = freeLabel,
                            onValueChange = { freeLabel = it },
                            label = { Text("Unité travaillée") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
            }
            item {
                Section("Difficulté") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Difficulty.entries.forEach { difficulty ->
                            FilterChip(
                                selected = difficultyName == difficulty.name,
                                onClick = { difficultyName = difficulty.name },
                                label = { Text(difficulty.displayLabel()) },
                            )
                        }
                    }
                }
            }
            item {
                Section("Note facultative") {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(MAX_NOTE_LENGTH) },
                        label = { Text("Note") },
                        supportingText = if (note.length >= NOTE_COUNTER_THRESHOLD) {
                            { Text("${note.length} / $MAX_NOTE_LENGTH") }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                    )
                }
            }
            validationMessage?.let { message ->
                item {
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
            }
            item {
                Button(
                    onClick = {
                        val form = DeclareFormState(
                            durationMinutes = durationMinutes,
                            unitType = unitType,
                            pagesFrom = pagesFrom,
                            pagesTo = pagesTo,
                            annaleLabel = annaleLabel,
                            cardsCount = cardsCount,
                            freeLabel = freeLabel,
                            difficulty = difficultyName?.let(Difficulty::valueOf),
                            note = note,
                        )
                        when (val result = buildActivityDeclaration(
                            item = queueItem,
                            form = form,
                            occurredAt = OffsetDateTime.now(),
                        )) {
                            is DeclarationBuildResult.Invalid -> {
                                validationMessage = result.errors.joinToString("\n")
                            }

                            is DeclarationBuildResult.Valid -> scope.launch {
                                saving = true
                                validationMessage = null
                                runCatching {
                                    repository.saveActivityLocally(result.declaration)
                                }.onSuccess {
                                    saved = true
                                    scope.launch {
                                        runCatching { repository.syncAfterLocalSave() }
                                    }
                                }.onFailure {
                                    validationMessage =
                                        "L'enregistrement local a échoué. Rien n'a été envoyé."
                                }
                                saving = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving && !saved,
                ) {
                    if (saving) {
                        CircularProgressIndicator()
                    } else {
                        Text("Enregistrer")
                    }
                }
            }
            if (saved) {
                item {
                    Text(
                        "Enregistré",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = onDone) { Text("Retour à la file") }
                }
            }
        }
    }
}

@Composable
private fun StepReminder(item: QueueItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(item.label, style = MaterialTheme.typography.titleMedium)
            Text(item.subject.label)
            Text(item.kind.displayKindLabel(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun WorkUnitType.displayLabel(): String = when (this) {
    WorkUnitType.PAGES -> "Pages"
    WorkUnitType.CHAPTER -> "Chapitre"
    WorkUnitType.ANNALE -> "Annale"
    WorkUnitType.CARDS -> "Cartes"
    WorkUnitType.FREE -> "Libre"
}

private fun Difficulty.displayLabel(): String = when (this) {
    Difficulty.EASY -> "Facile"
    Difficulty.OK -> "Correct"
    Difficulty.HARD -> "Difficile"
}
