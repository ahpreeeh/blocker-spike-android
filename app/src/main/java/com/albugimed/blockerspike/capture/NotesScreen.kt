package com.albugimed.blockerspike.capture

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.ui.Chevron
import com.albugimed.blockerspike.ui.EmptyState
import com.albugimed.blockerspike.ui.Fact
import com.albugimed.blockerspike.ui.Kicker
import com.albugimed.blockerspike.ui.PrimaryAction
import com.albugimed.blockerspike.ui.ScreenHeader
import com.albugimed.blockerspike.ui.SecondaryAction
import com.albugimed.blockerspike.ui.SurfaceCard
import com.albugimed.blockerspike.ui.mutedColor
import com.albugimed.blockerspike.ui.theme.ActionShape
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Ou vont les notes une fois gardees.
 *
 * Cette page repare le defaut le plus couteux du partage : une capture
 * confirmee par le serveur etait **effacee de l'appareil**. Un partage reussi
 * ne laissait donc aucune trace, et rien ne le distinguait d'un partage qui
 * n'avait jamais eu lieu. C'est de la que venait l'impression que le partage
 * ne fonctionnait pas.
 *
 * Ce que l'etat dit, et ce qu'il ne dit pas : le telephone sait si la note est
 * **partie**. Il ne sait pas si elle a ete **triee** dans l'atelier — l'atelier
 * ne le lui raconte pas encore. Les libelles s'arretent donc a « Envoyée », et
 * aucun d'eux ne dit « traitée ».
 */
@Composable
fun NotesScreen(onCapture: () -> Unit) {
    val notes by Graph.captureOutbox.notes
        .collectAsStateWithLifecycle(initialValue = emptyList<PendingCaptureRow>())

    var openNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    val openNote = notes.firstOrNull { it.captureId == openNoteId }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Notes",
                trailing = {
                    Button(
                        onClick = onCapture,
                        shape = ActionShape,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                    ) {
                        Text("Noter", style = MaterialTheme.typography.labelLarge)
                    }
                },
            )
        }

        if (notes.isEmpty()) {
            item {
                EmptyState(
                    "Rien de gardé pour l'instant. Le partage d'Android dépose ici ce " +
                        "que tu envoies depuis une autre application.",
                )
            }
        } else {
            item {
                SurfaceCard {
                    notes.forEachIndexed { index, note ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        NoteRow(note = note, onOpen = { openNoteId = note.captureId })
                    }
                }
            }
        }
    }

    openNote?.let { note ->
        NoteSheet(
            note = note,
            onDismiss = { openNoteId = null },
            onForget = {
                openNoteId = null
                Graph.applicationScope.launch {
                    Graph.captureOutbox.forget(note.captureId)
                }
            },
        )
    }
}

/**
 * Une note, reduite a ce qui permet de la reconnaitre.
 *
 * Deux lignes d'extrait, pas trois : le texte complet peut peser vingt mille
 * caracteres, et une liste ou chaque entree occupe un ecran n'est plus une
 * liste. On coupe avant de mesurer — l'extrait n'a pas a etre fidele.
 */
@Composable
private fun NoteRow(note: PendingCaptureRow, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .heightIn(min = 56.dp)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                note.text.take(PREVIEW_CHARACTERS),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${noteDayLabel(note.capturedAtMillis)} · ${stateLabel(note.state)}",
                style = MaterialTheme.typography.labelSmall,
                color = mutedColor,
            )
        }
        Chevron()
    }
}

/**
 * La note entiere, et le seul endroit d'ou elle peut disparaitre.
 *
 * L'oubli demande deux gestes : c'est la seule suppression definitive de tout
 * le flux de capture, et le bouton se trouve a portee de pouce dans une liste
 * qui defile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteSheet(
    note: PendingCaptureRow,
    onDismiss: () -> Unit,
    onForget: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirming by rememberSaveable(note.captureId) { mutableStateOf(false) }

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
            Kicker(stateLabel(note.state))
            Text(
                note.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Fact("Gardée le", noteMomentLabel(note.capturedAtMillis))
            note.deadReason?.let { Fact("Motif du refus", it) }

            if (confirming) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SecondaryAction(
                        text = "Annuler",
                        onClick = { confirming = false },
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryAction(
                        text = "Oublier",
                        onClick = onForget,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                SecondaryAction(
                    text = "Oublier définitivement",
                    onClick = { confirming = true },
                )
            }
        }
    }
}

/**
 * Longueur de l'extrait affiche dans la liste.
 *
 * Une note peut peser 20 000 caracteres. Les poser tels quels dans un `Text`,
 * meme limite a deux lignes, ferait travailler la mise en page sur tout le
 * texte.
 */
private const val PREVIEW_CHARACTERS = 200

private fun stateLabel(state: CaptureState): String = when (state) {
    CaptureState.PENDING -> "En attente d'envoi"
    // Jamais « traitée » : ce que le telephone constate, c'est que le serveur
    // a accuse reception. Le tri dans l'atelier ne lui est pas raconte.
    CaptureState.SENT -> "Envoyée à l'atelier"
    CaptureState.REFUSED -> "Refusée"
}

private val dayFormat = DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH)
private val momentFormat = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.FRENCH)

private fun noteDayLabel(epochMillis: Long): String =
    dayFormat.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

private fun noteMomentLabel(epochMillis: Long): String =
    momentFormat.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
