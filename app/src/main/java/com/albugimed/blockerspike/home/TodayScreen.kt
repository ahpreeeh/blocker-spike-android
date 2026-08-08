package com.albugimed.blockerspike.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.policy.PolicyState
import com.albugimed.blockerspike.reader.ReadingPosition
import com.albugimed.blockerspike.study.AgendaRowBlock
import com.albugimed.blockerspike.study.AgendaState
import com.albugimed.blockerspike.study.StudyQueueState
import com.albugimed.blockerspike.study.StudyRepository
import com.albugimed.blockerspike.study.buildAgendaHeaderPresentation
import com.albugimed.blockerspike.study.displayKindLabel
import com.albugimed.blockerspike.study.lastWorkDisplayLabel
import com.albugimed.blockerspike.study.pendingLabel
import com.albugimed.blockerspike.study.resumeButtonLabel
import com.albugimed.blockerspike.sync.QueueItem
import com.albugimed.blockerspike.sync.SyncState
import com.albugimed.blockerspike.ui.Kicker
import com.albugimed.blockerspike.ui.Notice
import com.albugimed.blockerspike.ui.NoticeTone
import com.albugimed.blockerspike.ui.PrimaryAction
import com.albugimed.blockerspike.ui.ProtectionCard
import com.albugimed.blockerspike.ui.ScreenHeader
import com.albugimed.blockerspike.ui.SecondaryAction
import com.albugimed.blockerspike.ui.Section
import com.albugimed.blockerspike.ui.SubjectDot
import com.albugimed.blockerspike.ui.mutedColor
import com.albugimed.blockerspike.ui.theme.ActionShape
import kotlinx.coroutines.launch

/**
 * L'accueil, refait sur ce que le brouillon demande.
 *
 * Quatre choses, dans cet ordre : **l'etat de la protection**, **ce qui doit
 * etre fait maintenant**, le **prochain verrou**, les **48 heures**. Rien
 * d'autre — c'est un ecran qu'on regarde debout, pas un tableau de bord.
 *
 * La protection passe en premier parce que c'est la seule chose ici dont
 * l'effet se produit **en dehors** de l'application : tout le reste est une
 * lecture de ce que l'atelier a decide ailleurs. La capture, elle, tient dans
 * l'en-tete : elle sert quelques secondes, pas au terme d'un defilement — et
 * un bouton flottant se posait par-dessus les cartes, masquant du texte en
 * plein milieu de la liste.
 *
 * Ce qui n'y est pas, et n'y sera pas : un tri par urgence. L'action en cours
 * est simplement **le premier element de la file**, dans l'ordre que
 * l'utilisateur a lui-meme donne (P4-02). L'application ne choisit pas a sa
 * place, elle se contente de ne pas lui faire chercher.
 */
@Composable
fun TodayScreen(
    repository: StudyRepository,
    positions: Map<String, ReadingPosition>,
    onOpenQueue: () -> Unit,
    onOpenAgenda: () -> Unit,
    onOpenProtection: () -> Unit,
    onOpenCapture: () -> Unit,
    onDeclare: (QueueItem) -> Unit,
    onResume: (QueueItem) -> Unit,
) {
    val policyRepo = Graph.policyRepository
    val policy by policyRepo.policy.collectAsStateWithLifecycle(initialValue = PolicyState())
    val scope = rememberCoroutineScope()
    val queueState by repository.queueState.collectAsStateWithLifecycle(
        initialValue = StudyQueueState(),
    )
    val agendaState by repository.agendaState.collectAsStateWithLifecycle(
        initialValue = AgendaState(),
    )
    val syncState by repository.syncState.collectAsStateWithLifecycle(
        initialValue = SyncState(),
    )

    // Rafraichir a l'ouverture, sans jamais bloquer l'affichage : le cache
    // reste utilisable meme si le reseau ne repond pas.
    LaunchedEffect(repository) {
        runCatching { repository.onQueueOpened() }
    }

    val agenda = buildAgendaHeaderPresentation(agendaState)
    val current = queueState.items.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Aujourd'hui",
                trailing = {
                    Button(
                        onClick = onOpenCapture,
                        shape = ActionShape,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                    ) {
                        Text("Noter vite", style = MaterialTheme.typography.labelLarge)
                    }
                },
            )
        }

        item {
            ProtectionCard(
                blockedCount = policy.blockedPackages.size,
                failsafeOverride = policy.failsafeOverride,
                storageHealthy = policy.storageHealthy,
                onOpenProtection = onOpenProtection,
                onSuspend = { scope.launch { policyRepo.setFailsafeOverride(true) } },
                onRestore = { scope.launch { policyRepo.setFailsafeOverride(false) } },
            )
        }

        if (!syncState.enrolled || syncState.halted) {
            item {
                Notice(
                    "Cet appareil n'est pas relié à l'atelier. La file et l'agenda " +
                        "resteront vides tant que l'enrôlement n'est pas fait.",
                    tone = NoticeTone.PROBLEM,
                )
            }
            item {
                PrimaryAction(text = "Enrôler l'appareil", onClick = onOpenQueue)
            }
        }

        item {
            Section(
                title = current?.label ?: "Rien dans la file",
                kicker = "À faire maintenant",
            ) {
                if (current == null) {
                    Text(
                        "La file en cache est vide. Choisis dans l'atelier ce que tu veux " +
                            "travailler ensuite.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = mutedColor,
                    )
                } else {
                    val position = current.resource?.let { positions[it.resourceId] }
                    CurrentStepDetails(item = current, position = position)
                    if (current.resource != null) {
                        // Le lecteur d'abord : reprendre a la page ou l'on
                        // s'est arrete est la seule chose qu'aucune autre
                        // application ne sait faire ici.
                        PrimaryAction(
                            text = resumeButtonLabel(position),
                            onClick = { onResume(current) },
                        )
                        SecondaryAction(
                            text = "Déclarer ce travail",
                            onClick = { onDeclare(current) },
                        )
                    } else {
                        PrimaryAction(
                            text = "Déclarer ce travail",
                            onClick = { onDeclare(current) },
                        )
                    }
                }
                SecondaryAction(text = "Voir toute la file", onClick = onOpenQueue)
            }
        }

        if (queueState.pendingCount > 0) {
            item {
                Notice("${pendingLabel(queueState.pendingCount)} — la file les renverra seule.")
            }
        }

        item {
            Section(title = "Prochain verrou", kicker = "Agenda") {
                val nextLock = agenda.nextLock
                if (nextLock == null) {
                    Text(
                        "Aucun verrou connu.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = mutedColor,
                    )
                } else {
                    AgendaRowBlock(nextLock)
                }
            }
        }

        // Une seule carte, pas un titre nu suivi de cartes : partout ailleurs
        // dans l'application un bloc de sens est une carte, et l'exception se
        // lisait comme un morceau d'ecran oublie.
        item {
            Section(title = "Dans les 48 heures", kicker = "Ce qui arrive") {
                if (agenda.window48h.isEmpty()) {
                    Text(
                        "Rien dans les 48 prochaines heures.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = mutedColor,
                    )
                } else {
                    // Trois entrees suffisent ici : l'accueil montre ce qui
                    // arrive, l'onglet Agenda montre tout.
                    agenda.window48h.take(WINDOW_PREVIEW).forEachIndexed { index, row ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        AgendaRowBlock(row)
                    }
                    if (agenda.window48h.size > WINDOW_PREVIEW) {
                        SecondaryAction(
                            text = "Voir les ${agenda.window48h.size} entrées",
                            onClick = onOpenAgenda,
                        )
                    }
                }
            }
        }

    }
}

private const val WINDOW_PREVIEW = 3

@Composable
private fun CurrentStepDetails(item: QueueItem, position: ReadingPosition?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SubjectDot(item.subject.label)
            Text(
                item.subject.label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                item.kind.displayKindLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = mutedColor,
            )
        }
        item.resource?.let { resource ->
            Text(
                resource.label,
                style = MaterialTheme.typography.bodySmall,
                color = mutedColor,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Kicker("Fraîcheur")
                Text(
                    item.signals.freshnessDays?.let { "$it j" } ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item.signals.lastWork?.let { lastWork ->
                Column {
                    Kicker("Dernier travail")
                    Text(
                        lastWorkDisplayLabel(lastWork, item.resource),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (position != null && position.pageCount > 0) {
            Text(
                "Signet posé page ${position.page} sur ${position.pageCount}.",
                style = MaterialTheme.typography.bodySmall,
                color = mutedColor,
            )
        }
    }
}
