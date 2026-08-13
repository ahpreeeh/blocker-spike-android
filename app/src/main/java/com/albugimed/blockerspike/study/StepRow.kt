package com.albugimed.blockerspike.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.albugimed.blockerspike.reader.ReadingPosition
import com.albugimed.blockerspike.sync.QueueItem
import com.albugimed.blockerspike.ui.AppIcon
import com.albugimed.blockerspike.ui.Chevron
import com.albugimed.blockerspike.ui.Fact
import com.albugimed.blockerspike.ui.GlyphButton
import com.albugimed.blockerspike.ui.Kicker
import com.albugimed.blockerspike.ui.PrimaryAction
import com.albugimed.blockerspike.ui.SecondaryAction
import com.albugimed.blockerspike.ui.SubjectDot
import com.albugimed.blockerspike.ui.TickBox
import com.albugimed.blockerspike.ui.mutedColor
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Un chapitre du parcours, en deux lignes et rien de plus.
 *
 * Elle remplace la carte haute qui portait le titre, deux faits etiquetes et
 * jusqu'a trois boutons. Une file de six etapes faisait alors six ecrans de
 * defilement : on ne voyait jamais sa liste, seulement un morceau.
 *
 * Les deux lignes disent **quoi** et **dans quelle matiere**. Le type de
 * travail et la progression restent hors du parcours : l'un se choisit dans
 * la declaration, l'autre se lit dans Matières.
 *
 * Aucun tri, aucun badge, aucune couleur d'alerte : la pastille identifie la
 * matiere sans classer le chapitre (cadrage §1.1).
 *
 * La coche est la seule action posee sur le rang, et elle y est parce qu'elle
 * est le geste le plus frequent du parcours : l'enfouir sous un chevron
 * demanderait deux appuis pour dire « c'est fait ». Les fleches, elles,
 * n'apparaissent qu'en mode reordonnancement, ou elles remplacent le chevron
 * plutot que de s'y ajouter.
 */
@Composable
internal fun StepRow(
    item: QueueItem,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    done: Boolean = false,
    onToggleDone: ((Boolean) -> Unit)? = null,
    moves: RowMoves? = null,
    reordering: Boolean = false,
) {
    // Grise et barre, sans jamais retirer de la liste ni changer de place. Une
    // etape terminee reste lisible : c'est ce qu'on a franchi, pas un dechet.
    val labelColor = if (done) mutedColor else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            // En reordonnancement la ligne cesse d'etre un raccourci : le seul
            // geste possible est de la deplacer.
            .then(if (!reordering) Modifier.clickable(onClick = onOpen) else Modifier)
            .heightIn(min = 56.dp)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onToggleDone != null && !reordering) {
            TickBox(checked = done, onCheckedChange = onToggleDone)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                item.label,
                style = MaterialTheme.typography.titleMedium,
                color = labelColor,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SubjectDot(item.subject.label)
                Text(
                    item.subject.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (reordering) {
            moves?.let {
                GlyphButton(
                    icon = AppIcon.ARROW_UP,
                    label = "Monter",
                    enabled = it.canMoveUp,
                    onClick = it.onMoveUp,
                )
                GlyphButton(
                    icon = AppIcon.ARROW_DOWN,
                    label = "Descendre",
                    enabled = it.canMoveDown,
                    onClick = it.onMoveDown,
                )
            }
        } else {
            Chevron()
        }
    }
}

/** Ce qu'une ligne peut faire d'elle-meme pendant un reordonnancement. */
internal data class RowMoves(
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val onMoveUp: () -> Unit,
    val onMoveDown: () -> Unit,
)

/**
 * Ce que l'etape a deja recu, en une ligne.
 *
 * « Jamais travaille » et non « — » : ici l'absence de trace **est** une
 * information utile, et c'est la seule fois ou elle se dit en toutes lettres.
 * Le tiret reste la regle partout ou l'absence pourrait se lire comme un zero
 * (cadrage §1.2).
 */
internal fun stepTraceLabel(item: QueueItem, now: Instant = Instant.now()): String {
    val moment = item.signals.lastActivityAt?.let(::parseMoment)
        ?: return "Jamais travaillé"

    val date = moment.atZone(ZoneId.systemDefault()).toLocalDate()
    return "${elapsedLabel(moment, now)} · ${dayFormat.format(date)}"
}

private val dayFormat = DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH)

private fun parseMoment(raw: String): Instant? = runCatching {
    OffsetDateTime.parse(raw).toInstant()
}
    .recoverCatching { Instant.parse(raw) }
    .recoverCatching { LocalDate.parse(raw).atStartOfDay(ZoneId.systemDefault()).toInstant() }
    .getOrNull()

private fun elapsedLabel(moment: Instant, now: Instant): String {
    val days = Duration.between(moment, now).toDays()
    return when {
        days < 0L -> "à venir"
        days == 0L -> "aujourd'hui"
        days == 1L -> "hier"
        else -> "il y a $days jours"
    }
}

/**
 * L'etape ouverte : ses faits, puis ses gestes.
 *
 * Elle existe parce qu'un chevron promet quelque chose. Faire de la ligne un
 * raccourci vers l'action la plus probable — ouvrir le document, ou declarer
 * si aucun n'est rattache — voudrait dire que le meme geste fait deux choses
 * differentes selon une condition invisible. Une feuille coute un appui de
 * plus et ne ment jamais.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StepSheet(
    item: QueueItem,
    position: ReadingPosition?,
    onDismiss: () -> Unit,
    onResume: () -> Unit,
    onDeclare: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Kicker(item.subject.label)
            Text(item.label, style = MaterialTheme.typography.headlineSmall)
            item.resource?.let { resource ->
                Text(
                    resource.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                )
            }

            Fact("Dernière trace", stepTraceLabel(item).takeIf { it != "Jamais travaillé" })
            Fact("Fraîcheur", item.signals.freshnessDays?.let { "$it j" })
            item.signals.lastWork?.let { lastWork ->
                Fact("Dernier travail", lastWorkDisplayLabel(lastWork, item.resource))
            }
            if (position != null && position.pageCount > 0) {
                Fact("Signet", "page ${position.page} sur ${position.pageCount}")
            }

            if (item.resource != null) {
                // Le lecteur d'abord : reprendre a la page ou l'on s'est
                // arrete est la seule chose qu'aucune autre application ne
                // sait faire ici.
                PrimaryAction(text = resumeButtonLabel(position), onClick = onResume)
                SecondaryAction(text = "Déclarer ce travail", onClick = onDeclare)
            } else {
                PrimaryAction(text = "Déclarer ce travail", onClick = onDeclare)
            }
        }
    }
}
