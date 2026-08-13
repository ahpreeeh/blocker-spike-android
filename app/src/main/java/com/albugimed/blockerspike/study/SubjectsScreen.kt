package com.albugimed.blockerspike.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.albugimed.blockerspike.sync.NodeProgress
import com.albugimed.blockerspike.ui.Chevron
import com.albugimed.blockerspike.ui.EmptyState
import com.albugimed.blockerspike.ui.Notice
import com.albugimed.blockerspike.ui.NoticeTone
import com.albugimed.blockerspike.ui.PrimaryAction
import com.albugimed.blockerspike.ui.ScreenHeader
import com.albugimed.blockerspike.ui.SubjectDot
import com.albugimed.blockerspike.ui.SurfaceCard
import com.albugimed.blockerspike.ui.mutedColor
import kotlinx.coroutines.launch

/**
 * Referentiel simple : une matiere se deplie sur ses chapitres. La case sert a
 * preparer un ajout groupe ; elle ne deplie jamais la ligne.
 */
@Composable
fun SubjectsScreen(
    repository: StudyRepository,
    onOpenPath: () -> Unit = {},
) {
    val state by repository.queueState.collectAsStateWithLifecycle(
        initialValue = StudyQueueState(),
    )
    val subjects = remember(state) { buildSubjectViews(state) }
    val scope = rememberCoroutineScope()
    var operationError by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var openSubjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(repository) {
        runCatching { repository.onQueueOpened() }
    }

    // Une synchronisation peut rendre une matiere complete pendant qu'elle est
    // selectionnee. Elle quitte alors le lot : il n'y a plus rien a ajouter.
    LaunchedEffect(subjects) {
        val selectable = subjects.filter(SubjectView::canAdd).mapTo(mutableSetOf()) { it.nodeId }
        selectedIds = selectedIds.filter { it in selectable }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScreenHeader(
                    title = "Matières",
                    subtitle = "Sélectionne plusieurs matières, puis ajoute-les en une fois.",
                )
            }
            item {
                Text(
                    cacheFreshnessLabel(state.cachedAtMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                )
            }
            if (state.skippedQueueNodes > 0) {
                item {
                    Notice(
                        "${state.skippedQueueNodes} matière(s) ou chapitre(s) reçu(s) illisible(s)",
                        tone = NoticeTone.PROBLEM,
                    )
                }
            }
            operationError?.let { error ->
                item { Notice(error, tone = NoticeTone.PROBLEM) }
            }

            if (subjects.isEmpty()) {
                item {
                    EmptyState(
                        "Aucune matière reçue de l'atelier. Crée-les là-bas, elles " +
                            "arriveront à la prochaine synchronisation.",
                    )
                }
            } else {
                items(subjects, key = SubjectView::nodeId) { subject ->
                    SubjectCard(
                        subject = subject,
                        expanded = subject.nodeId == openSubjectId,
                        selected = subject.nodeId in selectedIds,
                        onToggle = {
                            openSubjectId = if (subject.nodeId == openSubjectId) {
                                null
                            } else {
                                subject.nodeId
                            }
                        },
                        onSelectionChange = { selected ->
                            selectedIds = if (selected) {
                                (selectedIds + subject.nodeId).distinct()
                            } else {
                                selectedIds - subject.nodeId
                            }
                        },
                    )
                }
            }
        }

        if (selectedIds.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 3.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    PrimaryAction(
                        text = if (adding) {
                            "Ajout en cours…"
                        } else {
                            "Ajouter au parcours (${selectedIds.size})"
                        },
                        enabled = !adding,
                        onClick = {
                            val batch = selectedIds
                            scope.launch {
                                adding = true
                                operationError = null
                                runCatching { repository.addSubjectsToPath(batch) }
                                    .onSuccess {
                                        selectedIds = emptyList()
                                        onOpenPath()
                                    }
                                    .onFailure {
                                        operationError =
                                            "Ajout non enregistré. Le parcours n'a pas bougé."
                                    }
                                adding = false
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SubjectCard(
    subject: SubjectView,
    expanded: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onSelectionChange: (Boolean) -> Unit,
) {
    SurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = onSelectionChange,
                enabled = subject.canAdd,
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onToggle)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SubjectDot(subject.label)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        subject.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subjectCountsLabel(subject),
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedColor,
                    )
                    pathPresenceLabel(subject)?.let { status ->
                        Text(
                            status,
                            style = MaterialTheme.typography.labelSmall,
                            color = mutedColor,
                        )
                    }
                }
                Chevron()
            }
        }

        NodeFacts(subject.progress)

        if (expanded) {
            if (subject.chapters.isEmpty()) {
                Text(
                    "Aucun chapitre dans cette matière.",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                )
            } else {
                subject.chapters.forEach { chapter ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ChapterRow(chapter)
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(chapter: ChapterView) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            chapter.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        NodeFacts(chapter.progress, inPath = chapter.inPath)
    }
}

@Composable
private fun NodeFacts(
    progress: NodeProgress,
    inPath: Boolean = false,
) {
    val hint = progressHint(progress)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (inPath) "$hint · dans le parcours" else hint,
            style = MaterialTheme.typography.bodySmall,
            color = mutedColor,
            modifier = Modifier.weight(1f),
        )
        Text(
            freshnessLabel(progress.freshnessDays) ?: "—",
            style = MaterialTheme.typography.bodySmall,
            color = mutedColor,
        )
    }
}
