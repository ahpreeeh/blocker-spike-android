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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.albugimed.blockerspike.sync.AcademicNodeKind
import com.albugimed.blockerspike.sync.AcademicNodeRef
import com.albugimed.blockerspike.sync.ActivityKind
import com.albugimed.blockerspike.sync.Difficulty
import com.albugimed.blockerspike.sync.QueueItem
import com.albugimed.blockerspike.ui.EmptyState
import com.albugimed.blockerspike.ui.Fact
import com.albugimed.blockerspike.ui.Kicker
import com.albugimed.blockerspike.ui.Notice
import com.albugimed.blockerspike.ui.NoticeTone
import com.albugimed.blockerspike.ui.PrimaryAction
import com.albugimed.blockerspike.ui.ScreenHeader
import com.albugimed.blockerspike.ui.SecondaryAction
import com.albugimed.blockerspike.ui.Section
import com.albugimed.blockerspike.ui.SubjectDot
import com.albugimed.blockerspike.ui.SurfaceCard
import com.albugimed.blockerspike.ui.mutedColor
import com.albugimed.blockerspike.ui.theme.AlbugimedTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

private val declarationPostSaveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                declarationPostSaveScope.launch {
                    Graph.readingPositions.markDeclaredThrough(
                        resourceId = readerResourceId,
                        page = prefillTo,
                        nowMillis = System.currentTimeMillis(),
                    )
                }
            }
        }
        setContent {
            AlbugimedTheme {
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
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenHeader(title = "Déclaration")
            EmptyState("Cette étape n'est plus disponible dans le parcours en cache.")
            SecondaryAction(text = "Retour au parcours", onClick = onClose)
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
    var unitTypeName by rememberSaveable {
        mutableStateOf(if (prefill == null) null else WorkUnitType.PAGES.name)
    }
    var pagesFrom by rememberSaveable { mutableStateOf(prefill?.from?.toString() ?: "") }
    var pagesTo by rememberSaveable { mutableStateOf(prefill?.to?.toString() ?: "") }
    var annaleLabel by rememberSaveable { mutableStateOf("") }
    var cardsCount by rememberSaveable { mutableStateOf("") }
    var freeLabel by rememberSaveable { mutableStateOf("") }
    var difficultyName by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var customDuration by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(prefill != null) }
    var validationMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var resourceError by rememberSaveable { mutableStateOf<String?>(null) }
    var saved by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var selectedSubjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedChapterId by rememberSaveable { mutableStateOf<String?>(null) }
    var activityKindName by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val unitType = unitTypeName?.let(WorkUnitType::valueOf)
    val selectedChapter = nodes.firstOrNull {
        it.kind == AcademicNodeKind.CHAPTER &&
            it.nodeId == selectedChapterId &&
            it.parentId == selectedSubjectId
    }
    val activityKind = ActivityKind.entries.firstOrNull { it.name == activityKindName }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScreenHeader(
                    title = if (queueItem == null) "Déclarer hors parcours" else "Déclarer",
                    subtitle = "Ce que tu as réellement fait.",
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
                    SecondaryAction(
                        text = "Ouvrir la ressource",
                        onClick = { resourceError = onOpenResource(openUri) },
                    )
                    resourceError?.let { error ->
                        Notice(error, tone = NoticeTone.PROBLEM)
                    }
                }
            }
            if (queueItem != null) {
                item {
                    Section("Type de travail") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ActivityKind.entries.forEach { kind ->
                                FilterChip(
                                    selected = kind == activityKind,
                                    onClick = { activityKindName = kind.name },
                                    label = { Text(kind.wireName.displayKindLabel()) },
                                )
                            }
                        }
                    }
                }
            }
            item {
                // Duree et difficulte tenaient deux cartes pour deux rangees de
                // pastilles. La declaration se fait apres coup, souvent debout :
                // ce qui coute ici, c'est le defilement, pas le nombre de
                // champs. Les deux rangees se lisent d'un coup d'oeil et gardent
                // chacune son intitule.
                Section("Durée") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(15, 30, 45, 60).forEach { minutes ->
                            FilterChip(
                                selected =
                                    !customDuration && durationMinutes == minutes.toString(),
                                onClick = {
                                    customDuration = false
                                    durationMinutes = minutes.toString()
                                },
                                label = { Text("$minutes min") },
                            )
                        }
                        // Le champ libre derriere une pastille plutot qu'en
                        // permanence : quatre fois sur cinq la duree est un des
                        // quatre nombres, et le champ ne servait qu'a occuper la
                        // hauteur d'une ligne de saisie.
                        FilterChip(
                            selected = customDuration,
                            onClick = {
                                customDuration = !customDuration
                                if (customDuration) durationMinutes = ""
                            },
                            label = { Text("Autre") },
                        )
                    }
                    if (customDuration) {
                        OutlinedTextField(
                            value = durationMinutes,
                            onValueChange = { durationMinutes = it.filter(Char::isDigit) },
                            label = { Text("Minutes") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
            }
            if (!showDetails) {
                item {
                    SecondaryAction(
                        text = "Ajouter des détails",
                        onClick = { showDetails = true },
                    )
                }
            } else item {
                Section("Unité travaillée") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = unitType == null,
                            onClick = { unitTypeName = null },
                            label = { Text("Non précisée") },
                        )
                        WorkUnitType.entries.forEach { type ->
                            FilterChip(
                                selected = unitType == type,
                                onClick = { unitTypeName = type.name },
                                label = { Text(type.displayLabel()) },
                            )
                        }
                    }
                    when (unitType) {
                        null -> Text(
                            "Aucune unité précisée.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = mutedColor,
                        )
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

                        WorkUnitType.CHAPTER -> Text(
                            "Chapitre entier",
                            style = MaterialTheme.typography.bodyMedium,
                            color = mutedColor,
                        )

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
            if (showDetails) item {
                Section("Difficulté facultative") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Difficulty.entries.forEach { difficulty ->
                            FilterChip(
                                selected = difficultyName == difficulty.name,
                                onClick = {
                                    difficultyName = if (difficultyName == difficulty.name) {
                                        null
                                    } else {
                                        difficulty.name
                                    }
                                },
                                label = { Text(difficulty.displayLabel()) },
                            )
                        }
                    }
                }
            }
            if (showDetails) item {
                // La note est facultative et l'etait deja, mais elle occupait
                // une carte entiere et trois lignes de saisie a chaque
                // declaration. Elle reste entiere, a un appui : c'est le seul
                // element de l'ecran dont on peut dire qu'il ne sert presque
                // jamais, et il prenait le plus de hauteur.
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
                    Notice(message, tone = NoticeTone.PROBLEM)
                }
            }
            item {
                PrimaryAction(
                    enabled = !saving && !saved,
                    text = when {
                        saving -> "Enregistrement…"
                        saved -> "Enregistré"
                        else -> "Enregistrer"
                    },
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
                                activityKind = activityKind,
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
                                    declarationPostSaveScope.launch {
                                        runCatching { repository.syncAfterLocalSave() }
                                    }
                                    onDone()
                                }.onFailure {
                                    validationMessage =
                                        "L'enregistrement local a échoué. Rien n'a été envoyé."
                                }
                                saving = false
                            }
                        }
                    },
                )
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

    Section(title = "Cible", kicker = "Sur quoi tu as travaillé") {
        Kicker("Matière")
        if (subjects.isEmpty()) {
            Text(
                "Aucune matière disponible dans la copie locale. Actualise le parcours.",
                style = MaterialTheme.typography.bodyMedium,
                color = mutedColor,
            )
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

        Kicker("Chapitre")
        when {
            selectedSubject == null -> Text(
                "Choisis d'abord une matière.",
                style = MaterialTheme.typography.bodyMedium,
                color = mutedColor,
            )

            chapters.isEmpty() -> Text(
                "Cette matière n'a aucun chapitre disponible.",
                style = MaterialTheme.typography.bodyMedium,
                color = mutedColor,
            )

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

        Kicker("Type de travail")
        if (selectedChapter == null) {
            Text(
                "Choisis d'abord un chapitre.",
                style = MaterialTheme.typography.bodyMedium,
                color = mutedColor,
            )
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

/** Le rappel de l'etape : de quoi verifier qu'on declare la bonne chose. */
@Composable
private fun StepReminder(item: QueueItem) {
    SurfaceCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SubjectDot(item.subject.label)
            Text(
                item.subject.label,
                style = MaterialTheme.typography.bodySmall,
                color = mutedColor,
                modifier = Modifier.weight(1f),
            )
        }
        Text(item.label, style = MaterialTheme.typography.titleMedium)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Fact(
            "Dernier travail",
            item.signals.lastWork?.let { lastWorkDisplayLabel(it, item.resource) },
        )
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
