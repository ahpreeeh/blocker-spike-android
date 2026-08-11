package com.albugimed.blockerspike.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.albugimed.blockerspike.reader.ReaderActivity
import com.albugimed.blockerspike.reader.ReadingPosition
import com.albugimed.blockerspike.study.AgendaRowBlock
import com.albugimed.blockerspike.study.AgendaState
import com.albugimed.blockerspike.study.StepRow
import com.albugimed.blockerspike.study.StepSheet
import com.albugimed.blockerspike.study.StudyQueueState
import com.albugimed.blockerspike.study.StudyRepository
import com.albugimed.blockerspike.study.buildAgendaHeaderPresentation
import com.albugimed.blockerspike.study.buildPathView
import com.albugimed.blockerspike.study.nextRows
import com.albugimed.blockerspike.study.pathCountsLabel
import com.albugimed.blockerspike.study.pendingLabel
import com.albugimed.blockerspike.sync.QueueItem
import com.albugimed.blockerspike.sync.SyncState
import com.albugimed.blockerspike.ui.AppIcon
import com.albugimed.blockerspike.ui.Glyph
import com.albugimed.blockerspike.ui.Kicker
import com.albugimed.blockerspike.ui.Notice
import com.albugimed.blockerspike.ui.NoticeTone
import com.albugimed.blockerspike.ui.PrimaryAction
import com.albugimed.blockerspike.ui.ScreenHeader
import com.albugimed.blockerspike.ui.SecondaryAction
import com.albugimed.blockerspike.ui.Section
import com.albugimed.blockerspike.ui.SurfaceCard
import com.albugimed.blockerspike.ui.mutedColor
import com.albugimed.blockerspike.ui.theme.LocalAlbugimedExtras

/**
 * L'accueil, refait sur ce que le brouillon demande.
 *
 * Trois choses, dans cet ordre : **les prochains chapitres du parcours**, la
 * **prochaine echeance**, les **48 heures**. Rien d'autre — c'est un ecran
 * qu'on regarde debout, pas un tableau de bord.
 *
 * **La carte de blocage n'est plus ici.** Elle ouvrait l'accueil sur un etat
 * qui ne change presque jamais et qui ne demande rien : « les refus tiennent »
 * occupait le premier ecran pour dire, chaque jour, la meme chose. Le blocage
 * reste entier et reste joignable — page Blocage, dans le tiroir — il a
 * seulement cesse d'etre la premiere chose qu'on lit en ouvrant l'application.
 * Ce qu'on vient y chercher, c'est ce qu'on a a faire.
 *
 * Le travail se presente en **liste**, plus en fiche. La version precedente
 * ouvrait l'accueil sur une seule etape detaillee, avec ses deux faits
 * etiquetes et ses trois boutons : elle repondait « par quoi je commence » mais
 * cachait tout le reste, et il fallait changer d'onglet pour savoir ce qui
 * suivait. Quatre lignes tiennent dans la meme hauteur et disent la meme chose
 * plus une : **ou j'en suis dans mon parcours**.
 *
 * Les etapes terminees ne sont pas montrees ici : l'accueil regarde devant. La
 * page Parcours, elle, les garde a leur place, grisees.
 *
 * Ce qui n'y est pas, et n'y sera pas : un tri par urgence. L'ordre affiche est
 * celui que l'utilisateur a lui-meme donne dans l'atelier (P4-02). L'application
 * ne choisit pas a sa place, elle se contente de ne pas lui faire chercher.
 */
@Composable
fun TodayScreen(
    repository: StudyRepository,
    positions: Map<String, ReadingPosition>,
    onOpenQueue: () -> Unit,
    onOpenAgenda: () -> Unit,
    onOpenReadings: () -> Unit,
    onDeclare: (QueueItem) -> Unit,
    onResume: (QueueItem) -> Unit,
) {
    val context = LocalContext.current
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

    // Le plus recemment ouvert. C'est un fait de position, pas un jugement sur
    // ce qu'il faudrait lire — l'application continue de n'avoir aucun avis
    // sur l'ordre (cadrage §1.1).
    val lastReading = remember(positions) {
        positions.values.maxByOrNull { it.updatedAtMillis }
    }

    // L'identifiant plutot que l'objet : la file se rafraichit sous la feuille
    // ouverte, et c'est la version rechargee qu'il faut afficher.
    var openStepId by rememberSaveable { mutableStateOf<String?>(null) }
    val openStep = queueState.items.firstOrNull { it.stepId == openStepId }
    val path = remember(queueState) { buildPathView(queueState) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader(title = "Aujourd'hui") }

        // Le parcours en premier, parce que c'est la question qu'on se pose en
        // ouvrant l'application : et maintenant ? Les terminees sont sautees —
        // la page dediee les montre, l'accueil regarde devant.
        item {
            Section(title = "Prochains chapitres", kicker = "Parcours") {
                val next = nextRows(path, STEP_PREVIEW)
                if (next.isEmpty()) {
                    Text(
                        if (path.rows.isEmpty()) {
                            "Le parcours est vide. Envoie des matières dedans depuis l'atelier."
                        } else {
                            "Tout le parcours est terminé."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = mutedColor,
                    )
                    SecondaryAction(text = "Ouvrir le parcours", onClick = onOpenQueue)
                } else {
                    next.forEachIndexed { index, row ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        StepRow(item = row.item, onOpen = { openStepId = row.item.stepId })
                    }
                    SecondaryAction(
                        text = pathCountsLabel(path),
                        onClick = onOpenQueue,
                    )
                }
            }
        }

        // Le document le plus recemment ouvert, et rien d'autre. Une pile de
        // « reprises » possibles serait une deuxieme liste a trancher, alors
        // que la question posee ici n'en a qu'une : reprendre ou pas.
        lastReading?.let { position ->
            item {
                ContinueReadingCard(
                    position = position,
                    onResume = {
                        context.startActivity(
                            ReaderActivity.intent(
                                context = context,
                                resourceId = position.resourceId,
                                resourceLabel = position.documentLabel,
                                // Aucune etape visee : on reprend une lecture,
                                // on ne declare pas un travail.
                                stepId = null,
                            ),
                        )
                    },
                    onOpenAll = onOpenReadings,
                )
            }
        }

        if (!syncState.enrolled || syncState.halted) {
            item {
                Notice(
                    "Cet appareil n'est pas relié à l'atelier. La liste et l'agenda " +
                        "resteront vides tant que l'enrôlement n'est pas fait.",
                    tone = NoticeTone.PROBLEM,
                )
            }
            item {
                PrimaryAction(text = "Enrôler l'appareil", onClick = onOpenQueue)
            }
        }

        if (queueState.pendingCount > 0) {
            item {
                Notice("${pendingLabel(queueState.pendingCount)} — la liste les renverra seule.")
            }
        }

        item {
            Section(title = "Prochaine échéance", kicker = "Agenda") {
                val nextLock = agenda.nextLock
                if (nextLock == null) {
                    Text(
                        "Aucune échéance connue.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = mutedColor,
                    )
                } else {
                    AgendaRowBlock(nextLock)
                }
                SecondaryAction(text = "Ouvrir l'agenda", onClick = onOpenAgenda)
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
 * « Continuer a lire » : la carte que la conception place juste sous le
 * blocage, et qui manquait completement ici.
 *
 * Elle affiche **la page, pas un pourcentage**. La maquette annonce « 36 %
 * lus » en gros ; le cadrage §1 interdit de resumer une progression a un
 * nombre unique, et pour une bonne raison — 36 % d'un polycopie n'est pas 36 %
 * du travail, et l'ecart entre les deux est exactement ce que l'application ne
 * doit pas laisser croire. La barre reste, parce qu'elle dit *ou l'on est dans
 * un document* et rien de plus ; le chiffre qui l'accompagne est un numero de
 * page, verifiable a l'oeil sur le document lui-meme.
 */
@Composable
private fun ContinueReadingCard(
    position: ReadingPosition,
    onResume: () -> Unit,
    onOpenAll: () -> Unit,
) {
    val label = position.documentLabel.ifBlank { "Document sans nom" }
    SurfaceCard {
        Kicker("Continuer à lire")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // La couverture coloree de la conception, reduite a la vignette
            // qu'un telephone peut se permettre. La couleur vient du libelle :
            // deux documents de la meme matiere se ressemblent, et c'est le but.
            Box(
                modifier = Modifier
                    .size(width = 52.dp, height = 68.dp)
                    .background(
                        LocalAlbugimedExtras.current.coverHue(label),
                        RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Glyph(AppIcon.FILE, size = 22.dp, tint = Color.White)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (position.pageCount > 0) {
                    ReadingBar(position.page, position.pageCount)
                    Text(
                        "page ${position.page} sur ${position.pageCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = mutedColor,
                    )
                } else {
                    Text(
                        "page ${position.page}",
                        style = MaterialTheme.typography.labelSmall,
                        color = mutedColor,
                    )
                }
            }
        }
        PrimaryAction(text = "Reprendre", onClick = onResume)
        SecondaryAction(text = "Toutes les lectures", onClick = onOpenAll)
    }
}

/** Ou l'on en est dans un document. Cinq points de haut, comme la conception. */
@Composable
private fun ReadingBar(page: Int, pageCount: Int) {
    val fraction = (page.toFloat() / pageCount.toFloat()).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(5.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
    }
}

private const val WINDOW_PREVIEW = 3

/**
 * Combien d'etapes l'accueil montre avant de renvoyer a l'onglet.
 *
 * Quatre : au-dela, la carte pousse l'agenda hors de l'ecran, et l'accueil
 * redevient ce qu'il ne doit pas etre — une deuxieme liste complete.
 */
private const val STEP_PREVIEW = 4
