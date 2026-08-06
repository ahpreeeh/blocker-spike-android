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
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.sync.AcademicNodeKind
import com.albugimed.blockerspike.sync.AcademicNodeRef
import com.albugimed.blockerspike.sync.ActivityKind
import com.albugimed.blockerspike.sync.Difficulty
import com.albugimed.blockerspike.sync.QueueItem
import com.albugimed.blockerspike.ui.Section
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

class DeclareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stepId = intent.getStringExtra(EXTRA_STEP_ID)
        val freeDeclaration = intent.getBooleanExtra(EXTRA_FREE_DECLARATION, false)
        val repository = StudyDependencies.repository(applicationContext)

        // V2.2 - venu du lecteur interne. La plage est PROPOSEE, pas imposee :
        // la position reste un fait declare (amendement §3).
        val readerResourceId = intent.getStringExtra(EXTRA_READER_RESOURCE_ID)
        val prefillFrom = intent.getIntExtra(EXTRA_PAGES_FROM, 0).takeIf { it >= 1 }
        val prefillTo = intent.getIntExtra(EXTRA_PAGES_TO, 0).takeIf { it >= 1 }
        val prefill = if (prefillFrom != null && prefillTo != null) {
            PageRangePrefill(prefillFrom, prefillTo)
        } else {
            null
        }
        val onSaved: () -> Unit = {
            if (readerResourceId != null && prefillTo != null) {
                // Le compte repart de la page suivante. Sans cela, la
                // prochaine seance reproposerait des pages deja declarees.
                readerScope.launch {
                    Graph.readingPositions.markDeclaredThrough(
                        resourceId = readerResourceId,
                        page = prefillTo,
                        nowMillis = System.currentTimeMillis(),
                    )
                }
            }
        }
        setContent {
            MaterialTheme {
                val queue by repository.queueState.collectAsStateWithLifecycle(
                    initialValue = StudyQueueState(),
                )
                val queueItem = queue.items.firstOrNull { it.stepId == stepId }
                if (freeDeclaration) {
                    DeclareScreen(
                        queueItem = null,
                        nodes = queue.nodes,
                        repository = repository,
                        onOpenResource = ::openResource,
                        onDone = ::finish,
                    )
                } else if (queueItem == null) {
                    MissingStepScreen(onClose = ::finish)
                } else {
                    DeclareScreen(
                        queueItem = queueItem,
                        nodes = emptyList(),
                        repository = repository,
                        onOpenResource = ::openResource,
                        onDone = ::finish,
                        prefill = prefill,
                        onSaved = onSaved,
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
        private const val EXTRA_FREE_DECLARATION = "study_free_declaration"
        private const val EXTRA_READER_RESOURCE_ID = "study_reader_resource_id"
        private const val EXTRA_PAGES_FROM = "study_pages_from"
        private const val EXTRA_PAGES_TO = "study_pages_to"

        /**
         * Portee applicative : la mise a jour du signet ne doit pas etre
         * annulee quand l'ecran se ferme juste apres l'enregistrement.
         */
        private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun intent(context: Context, stepId: String): Intent =
            Intent(context, DeclareActivity::class.java)
                .putExtra(EXTRA_STEP_ID, stepId)

        fun freeIntent(context: Context): Intent =
            Intent(context, DeclareActivity::class.java)
                .putExtra(EXTRA_FREE_DECLARATION, true)

        /** Declaration ouverte depuis le lecteur, plage de pages proposee. */
        fun readerIntent(
            context: Context,
            stepId: String,
            resourceId: String,
            pagesFrom: Int,
            pagesTo: Int,
        ): Intent = intent(context, stepId)
            .putExtra(EXTRA_READER_RESOURCE_ID, resourceId)
            .putExtra(EXTRA_PAGES_FROM, pagesFrom)
            .putExtra(EXTRA_PAGES_TO, pagesTo)
    }
}

/** Plage proposee par le lecteur. Modifiable dans le formulaire. */
data class PageRangePrefill(val from: Int, val to: Int)

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
    queueItem: QueueItem?,
    nodes: List<AcademicNodeRef>,
    repository: StudyRepository,
    onOpenResource: (String) -> String?,
    onDone: () -> Unit,
    prefill: PageRangePrefill? = null,
    onSaved: () -> Unit = {},
) {
    var durationMinutes by rememberSaveable { mutableStateOf("") }
    var unitTypeName by rememberSaveable { mutableStateOf(WorkUnitType.PAGES.name) }
    var pagesFrom by rememberSaveable { mutableStateOf(prefill?.from?.toString() ?: "") }
    var pagesTo by rememberSaveable { mutableStateOf(prefill?.to?.toString() ?: "") }
    var annaleLabel by rememberSaveable { mutableStateOf("") }
    var cardsCount by rememberSaveable { mutableStateOf("") }
    var freeLabel by rememberSaveable { mutableStateOf("") }
    var difficultyName by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var validationMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var resourceError by rememberSaveable { mutableStateOf<String?>(null) }
    var saved by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var selectedSubjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedChapterId by rememberSaveable { mutableStateOf<String?>(null) }
    var activityKindName by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val unitType = WorkUnitType.valueOf(unitTypeName)
    val selectedChapter = nodes.firstOrNull {
        it.kind == AcademicNodeKind.CHAPTER &&
            it.nodeId == selectedChapterId &&
            it.parentId == selectedSubjectId
    }
    val activityKind = ActivityKind.entries.firstOrNull { it.name == activityKindName }

    Scaffold { padding ->
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    if (queueItem == null) "Déclarer hors file" else "Déclarer le travail",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            item {
                if (queueItem == null) {
                    FreeTargetPicker(
                        nodes = nodes,
                        selectedSubjectId = selectedSubjectId,
                        selectedChapterId = selectedChapterId,
                        selectedActivityKind = activityKind,
                        onSubjectSelected = { subjectId ->
                            selectedSubjectId = subjectId
                            selectedChapterId = null
                            activityKindName = null
                        },
                        onChapterSelected = {
                            selectedChapterId = it
                            activityKindName = null
                        },
                        onActivityKindSelected = { activityKindName = it.name },
                    )
                } else {
                    StepReminder(queueItem)
                }
            }
            queueItem?.resource?.openUri?.let { openUri ->
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
                        val occurredAt = OffsetDateTime.now()
                        val result = if (queueItem == null) {
                            buildFreeActivityDeclaration(
                                chapter = selectedChapter,
                                activityKind = activityKind,
                                form = form,
                                occurredAt = occurredAt,
                            )
                        } else {
                            buildActivityDeclaration(
                                item = queueItem,
                                form = form,
                                occurredAt = occurredAt,
                            )
                        }
                        when (result) {
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
                                    onSaved()
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
private fun FreeTargetPicker(
    nodes: List<AcademicNodeRef>,
    selectedSubjectId: String?,
    selectedChapterId: String?,
    selectedActivityKind: ActivityKind?,
    onSubjectSelected: (String) -> Unit,
    onChapterSelected: (String) -> Unit,
    onActivityKindSelected: (ActivityKind) -> Unit,
) {
    val subjects = nodes.filter { it.kind == AcademicNodeKind.SUBJECT }
    val selectedSubject = subjects.firstOrNull { it.nodeId == selectedSubjectId }
    val chapters = if (selectedSubject == null) {
        emptyList()
    } else {
        nodes.filter {
            it.kind == AcademicNodeKind.CHAPTER && it.parentId == selectedSubject.nodeId
        }
    }
    val selectedChapter = chapters.firstOrNull { it.nodeId == selectedChapterId }

    Section("Cible") {
        Text("Matière", style = MaterialTheme.typography.labelLarge)
        if (subjects.isEmpty()) {
            Text("Aucune matière disponible dans la copie locale. Actualise la file.")
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                subjects.forEach { subject ->
                    FilterChip(
                        selected = subject.nodeId == selectedSubjectId,
                        onClick = { onSubjectSelected(subject.nodeId) },
                        label = { Text(subject.label) },
                    )
                }
            }
        }

        Text("Chapitre", style = MaterialTheme.typography.labelLarge)
        when {
            selectedSubject == null -> Text("Choisis d'abord une matière.")
            chapters.isEmpty() -> Text("Cette matière n'a aucun chapitre disponible.")
            else -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chapters.forEach { chapter ->
                    FilterChip(
                        selected = chapter.nodeId == selectedChapterId,
                        onClick = { onChapterSelected(chapter.nodeId) },
                        label = { Text(chapter.label) },
                    )
                }
            }
        }

        Text("Type de travail", style = MaterialTheme.typography.labelLarge)
        if (selectedChapter == null) {
            Text("Choisis d'abord un chapitre.")
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActivityKind.entries.forEach { kind ->
                    FilterChip(
                        selected = kind == selectedActivityKind,
                        onClick = { onActivityKindSelected(kind) },
                        label = { Text(kind.wireName.displayKindLabel()) },
                    )
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
            item.signals.lastWork?.let { lastWork ->
                Text(
                    "Dernier travail : ${lastWorkDisplayLabel(lastWork, item.resource)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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
