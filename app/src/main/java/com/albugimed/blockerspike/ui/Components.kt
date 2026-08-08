package com.albugimed.blockerspike.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.albugimed.blockerspike.ui.theme.ActionShape
import com.albugimed.blockerspike.ui.theme.LocalAlbugimedExtras

/**
 * Les briques communes a tous les ecrans.
 *
 * Elles existent pour une raison precise : avant, chaque ecran redecidait
 * seul de ses marges, de ses tailles de texte et de sa notion de « carte ».
 * Le resultat se lisait comme un prototype parce que c'en etait un.
 */

/** Surtitre : ce que la section est, dit en tout petit au-dessus du titre. */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = LocalAlbugimedExtras.current.textMuted,
        modifier = modifier,
    )
}

/**
 * L'en-tete d'un ecran. Un titre, une phrase qui dit a quoi il sert.
 *
 * `trailing` accueille l'action propre a l'ecran — meme convention que
 * [Section]. C'est la que vit « Noter vite » : un bouton flottant se posait
 * par-dessus les cartes et masquait du texte en plein milieu de la liste.
 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalAlbugimedExtras.current.textMuted,
            )
        }
    }
}

/**
 * Une carte titree. Meme signature qu'avant — les ecrans File et Declaration
 * l'utilisent deja et n'ont pas a changer d'appel.
 */
@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    kicker: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SurfaceCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                kicker?.let { Kicker(it) }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            trailing?.invoke()
        }
        content()
    }
}

/** La carte nue : bord fin, coin doux, aucune ombre lourde. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/**
 * Un fait, affiche comme un fait.
 *
 * `value == null` rend **« — »** et jamais « 0 » : cadrage §1.2, absence de
 * trace n'est pas absence de travail.
 */
@Composable
fun Fact(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = LocalAlbugimedExtras.current.textMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            value ?: "—",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Pastille de matiere. Elle identifie, elle ne classe pas. */
@Composable
fun SubjectDot(subjectLabel: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .background(
                LocalAlbugimedExtras.current.subjectHue(subjectLabel),
                CircleShape,
            ),
    )
}

enum class NoticeTone { NEUTRAL, PROBLEM }

/**
 * Ce que l'application doit dire sans le dramatiser.
 *
 * Deux tons seulement, et `PROBLEM` sert aux **pannes** — stockage
 * illisible, refus du serveur — jamais a signaler du retard.
 */
@Composable
fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.NEUTRAL,
) {
    val background = when (tone) {
        NoticeTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
        NoticeTone.PROBLEM -> MaterialTheme.colorScheme.errorContainer
    }
    val foreground = when (tone) {
        NoticeTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        NoticeTone.PROBLEM -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(background, MaterialTheme.shapes.medium)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = foreground)
    }
}

/** Un vide se raconte, il ne se laisse pas blanc. */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalAlbugimedExtras.current.textMuted,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
    )
}

@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        shape = ActionShape,
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SecondaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        shape = ActionShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun DiagnosticRow(label: String, ok: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                    CircleShape,
                ),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        )
        Text(
            if (ok) "Actif" else "Inactif",
            style = MaterialTheme.typography.labelMedium,
            color = if (ok) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        TextButton(onClick = onOpen) { Text("Ouvrir") }
    }
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        // Sans cette gouttiere, la derniere ligne du sous-titre vient toucher
        // l'interrupteur et on lit une collision la ou il n'y en a pas.
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = LocalAlbugimedExtras.current.textMuted,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Le gris des faits neutres, accessible sans importer le thème partout. */
val mutedColor: Color
    @Composable get() = LocalAlbugimedExtras.current.textMuted
