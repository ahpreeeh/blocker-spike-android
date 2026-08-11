package com.albugimed.blockerspike.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.albugimed.blockerspike.ui.ScreenHeader
import com.albugimed.blockerspike.ui.SubjectDot
import com.albugimed.blockerspike.ui.SurfaceCard
import com.albugimed.blockerspike.ui.mutedColor

/**
 * Les matieres et leurs chapitres, avec ce qui a deja ete declare dessus.
 *
 * **Cet ecran ne fait pas faire, il fait voir.** Le parcours dit quoi faire
 * ensuite ; les matieres disent ou l'on en est. Rien n'est donc affiche deux
 * fois, et il n'y a ici ni bouton « declarer » ni reordonnancement : les
 * ajouter ramenerait la moitie du parcours dans une page qui n'est pas la
 * sienne.
 *
 * Aucun pourcentage, aucun score : les cinq dimensions restent separees telles
 * que le serveur les envoie, et une dimension sans trace s'ecrit « — ».
 */
@Composable
fun SubjectsScreen(repository: StudyRepository) {
    val state by repository.queueState.collectAsStateWithLifecycle(
        initialValue = StudyQueueState(),
    )
    val subjects = remember(state) { buildSubjectViews(state) }

    // Redemander la synchro en ouvrant la page, comme le fait l'accueil. Sans
    // cela l'ecran n'affiche que le cache : arriver ici par le tiroir sans
    // repasser par « Aujourd'hui » montrait un instantane d'avant le dernier
    // deploiement, donc « aucune trace » partout alors que le serveur avait la
    // progression. L'affichage n'attend pas la reponse : le cache reste
    // lisible si le reseau ne repond pas.
    LaunchedEffect(repository) {
        runCatching { repository.onQueueOpened() }
    }

    // Une seule matiere ouverte a la fois. Un accordeon plutot qu'un jeu de
    // cases : sur un telephone, trois matieres depliees font perdre la liste.
    var openSubjectId by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Matières",
                subtitle = "Dans l'ordre de l'atelier. Ce qui a été déclaré, rien de plus.",
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

        if (subjects.isEmpty()) {
            item {
                EmptyState(
                    "Aucune matière reçue de l'atelier. Crée-les là-bas, elles " +
                        "arriveront à la prochaine synchronisation.",
                )
            }
        } else {
            items(subjects, key = { it.nodeId }) { subject ->
                SubjectCard(
                    subject = subject,
                    expanded = subject.nodeId == openSubjectId,
                    onToggle = {
                        openSubjectId = if (subject.nodeId == openSubjectId) {
                            null
                        } else {
                            subject.nodeId
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SubjectCard(
    subject: SubjectView,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    SurfaceCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
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
            }
            Chevron()
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

/**
 * Les faits d'un noeud sur une ligne : ce qui a ete declare, puis la fraicheur.
 *
 * « dans le parcours » est ecrit et non colore. Le citron ne sert qu'a la
 * marque et a l'element ouvert : s'il disait ici l'appartenance au parcours,
 * il se mettrait a classer (cadrage §1.1).
 */
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
