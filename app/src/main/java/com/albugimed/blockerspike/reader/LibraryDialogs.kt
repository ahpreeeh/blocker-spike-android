package com.albugimed.blockerspike.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.albugimed.blockerspike.ui.mutedColor

/**
 * Les quatre boites de dialogue de la bibliotheque.
 *
 * Des `AlertDialog` et non des feuilles glissantes : nommer un dossier ou
 * choisir une destination sont des gestes courts, et une feuille qui monte du
 * bas coute une animation et un espace vide pour trois lignes de contenu.
 */

/** Ce sur quoi porte une action : un dossier ou un livre. */
sealed interface LibraryTarget {
    val label: String

    data class Folder(val folder: LibraryFolder) : LibraryTarget {
        override val label: String get() = folder.name
    }

    data class Book(val book: LibraryBook) : LibraryTarget {
        override val label: String get() = book.title
    }
}

/**
 * Nommer : un dossier qu'on cree, un dossier ou un livre qu'on renomme.
 *
 * Le bouton reste inactif tant que le champ est vide plutot que d'accepter et
 * de retomber sur un nom par defaut : « Dossier » apparu tout seul se cherche
 * ensuite parmi cinq autres « Dossier ».
 */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    supporting: String? = null,
) {
    var entry by rememberSaveable(initial) { mutableStateOf(initial) }
    val valide = entry.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = entry,
                    onValueChange = { entry = it.take(120) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    label = { Text("Nom") },
                    modifier = Modifier.fillMaxWidth(),
                )
                supporting?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = mutedColor)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(entry.trim()) }, enabled = valide) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/**
 * Le titre d'un livre qu'on ajoute, et les fichiers qu'il portera.
 *
 * Le titre arrive **deja rempli** avec le nom du premier fichier, nettoye de
 * son extension et de ses tirets. C'est le point de la question posee au
 * depart : le nom du fichier est presque toujours un titre acceptable, et
 * presque jamais le bon — il faut donc le proposer, pas l'imposer.
 */
@Composable
fun AddBookDialog(
    files: List<PickedPdf>,
    destination: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val propose = remember(files) {
        files.firstOrNull()?.name?.let(::bookTitleFromFileName).orEmpty()
    }
    var entry by rememberSaveable(propose) { mutableStateOf(propose) }
    val pages = files.sumOf { it.pageCount }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (files.size > 1) "Ajouter un livre en ${files.size} fichiers" else "Ajouter un livre") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = entry,
                    onValueChange = { entry = it.take(120) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    label = { Text("Titre") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Proposé d'après le nom du fichier — modifie-le si besoin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                )
                files.forEach { file ->
                    Text(
                        "• ${file.name} — ${file.pageCount} pages",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    if (files.size > 1) {
                        "$pages pages en tout, rangé dans « $destination »."
                    } else {
                        "Rangé dans « $destination »."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(entry.trim()) }, enabled = entry.isNotBlank()) {
                Text("Ajouter")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/**
 * Ou ranger ce qu'on deplace.
 *
 * L'arbre entier est montre a plat, indente par sa profondeur : la liste des
 * destinations est le seul endroit ou voir la forme complete du rangement a du
 * sens, et descendre dossier par dossier pour en choisir un serait plus long
 * que le deplacement lui-meme.
 *
 * Les destinations impossibles sont **absentes** plutot que grisees — un
 * dossier ne peut pas entrer dans lui-meme, et l'expliquer coute plus qu'il ne
 * rapporte.
 */
@Composable
fun MoveDialog(
    library: Library,
    target: LibraryTarget,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val exclu = (target as? LibraryTarget.Folder)?.folder?.id
    val destinations = remember(library, exclu) { library.destinationsFor(exclu) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Déplacer « ${target.label} »") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                DestinationRow(name = "Lectures", depth = 0, onClick = { onPick(null) })
                destinations.forEach { dossier ->
                    DestinationRow(
                        name = dossier.name,
                        depth = library.path(dossier.id).size,
                        onClick = { onPick(dossier.id) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun DestinationRow(name: String, depth: Int, onClick: () -> Unit) {
    Text(
        name,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (depth * 16).dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
    )
}

/**
 * Ce qu'on peut faire d'une tuile : renommer, deplacer, supprimer.
 *
 * Le texte de suppression dit ce qui disparait **et ce qui reste**. Supprimer
 * un dossier ne supprime rien de ce qu'il contenait ; supprimer un livre
 * n'efface pas le PDF sur le telephone. Sans ces deux phrases, le geste se
 * tente en retenant son souffle.
 */
@Composable
fun TargetMenuDialog(
    target: LibraryTarget,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onFiles: (() -> Unit)? = null,
) {
    var confirming by remember { mutableStateOf(false) }

    if (confirming) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Supprimer « ${target.label} » ?") },
            text = {
                Text(
                    when (target) {
                        is LibraryTarget.Folder ->
                            "Le dossier disparaît. Ce qu'il contenait remonte d'un cran, " +
                                "rien n'est supprimé."

                        is LibraryTarget.Book ->
                            "Le livre sort de la bibliothèque et sa page mémorisée est " +
                                "oubliée. Le fichier PDF, lui, reste sur le téléphone."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = onDelete) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(target.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                MenuRow("Renommer", onRename)
                MenuRow("Déplacer", onMove)
                // Presente seulement pour un livre : c'est la porte du second
                // fichier, et donc du seul endroit ou un livre en tomes se
                // constitue.
                onFiles?.let { MenuRow("Fichiers du livre", it) }
                MenuRow("Supprimer") { confirming = true }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    )
}
