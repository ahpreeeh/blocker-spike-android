package com.albugimed.blockerspike.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.albugimed.blockerspike.ui.Kicker
import com.albugimed.blockerspike.ui.Notice
import com.albugimed.blockerspike.ui.NoticeTone
import com.albugimed.blockerspike.ui.ScreenHeader
import com.albugimed.blockerspike.ui.Section
import com.albugimed.blockerspike.ui.mutedColor

/**
 * L'agenda, enfin un ecran.
 *
 * Il n'existait que comme un en-tete pose au-dessus de la file : deux
 * informations serrees dans une carte qu'on faisait defiler pour atteindre
 * autre chose. L'agenda repond pourtant a une question distincte — « qu'est-ce
 * qui est deja pose dans mon temps ? » — et merite sa place.
 *
 * Ce qu'il ne fait toujours pas, volontairement : placer du travail dans des
 * creneaux. Le cadrage §8 l'exclut, et l'application n'a pas d'avis sur
 * l'ordre.
 */
@Composable
fun AgendaScreen(repository: StudyRepository) {
    val agendaState by repository.agendaState.collectAsStateWithLifecycle(
        initialValue = AgendaState(),
    )
    val content = buildAgendaHeaderPresentation(agendaState)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Agenda",
                subtitle = "Ce qui est déjà posé. L'application n'y ajoute rien toute seule.",
            )
        }

        item {
            Text(
                content.freshnessLabel,
                style = MaterialTheme.typography.bodySmall,
                color = mutedColor,
            )
        }

        items(content.warnings.size) { index ->
            Notice(content.warnings[index], tone = NoticeTone.PROBLEM)
        }

        item {
            Section(title = "Prochain verrou", kicker = "La grande échéance") {
                val nextLock = content.nextLock
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
                if (content.window48h.isEmpty()) {
                    Text(
                        "Rien dans les 48 prochaines heures.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = mutedColor,
                    )
                } else {
                    content.window48h.forEachIndexed { index, row ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        AgendaRowBlock(row)
                    }
                }
            }
        }
    }
}

/**
 * Une entree d'agenda, rendue comme un fait.
 *
 * Aucune couleur d'alerte, aucun badge : le cadrage §1.1 interdit de
 * hierarchiser. « Demain 7 h » se lit deja tout seul.
 */
@Composable
internal fun AgendaRowBlock(row: AgendaRowPresentation) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Kicker(row.kindLabel)
        Text(row.label, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                row.timeLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = mutedColor,
            )
        }
        row.location?.takeIf { it.isNotBlank() }?.let {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(it, style = MaterialTheme.typography.bodySmall, color = mutedColor)
        }
    }
}
