package com.albugimed.blockerspike.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.albugimed.blockerspike.ui.mutedColor

/**
 * Un livre et ses fichiers.
 *
 * Un livre a un seul fichier s'ouvre directement dans le lecteur : passer par
 * une liste d'un element serait un ecran de plus pour rien. Cette vue-ci sert
 * aux livres en tomes, et c'est la que la question posee au depart trouve sa
 * reponse : la progression du **livre** est un pourcentage, celle de chaque
 * **fichier** nomme sa page.
 */
@Composable
fun BookSheet(
    book: LibraryBook,
    positions: Map<String, ReadingPosition>,
    onOpenFile: (LibraryBookFile) -> Unit,
    onAddFile: () -> Unit,
    onRenameFile: (LibraryBookFile, String) -> Unit,
    onRemoveFile: (LibraryBookFile) -> Unit,
    onDismiss: () -> Unit,
) {
    var menuFile by remember { mutableStateOf<LibraryBookFile?>(null) }
    var renamingFile by remember { mutableStateOf<LibraryBookFile?>(null) }
    val progression = remember(book, positions) { bookProgress(book, positions) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    progression.percent?.let { "$it % lus — ${book.files.size} fichiers" }
                        ?: "${book.files.size} fichiers",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                )
                ProgressBar(fraction = progression.fraction())
                book.files.forEach { fichier ->
                    FileRow(
                        file = fichier,
                        position = positions[fichier.resourceId],
                        onOpen = { onOpenFile(fichier) },
                        onMenu = { menuFile = fichier },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onAddFile) { Text("Ajouter un fichier") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )

    menuFile?.let { fichier ->
        AlertDialog(
            onDismissRequest = { menuFile = null },
            title = { Text(fileLabel(fichier, positions[fichier.resourceId])) },
            text = {
                Column {
                    Text(
                        "Renommer",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                renamingFile = fichier
                                menuFile = null
                            }
                            .padding(vertical = 14.dp),
                    )
                    Text(
                        // Le mot compte : « retirer » et non « supprimer ». Le
                        // PDF reste sur le telephone, seul le rattachement part.
                        "Retirer du livre",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onRemoveFile(fichier)
                                menuFile = null
                            }
                            .padding(vertical = 14.dp),
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { menuFile = null }) { Text("Fermer") } },
        )
    }

    renamingFile?.let { fichier ->
        NameDialog(
            title = "Renommer le fichier",
            initial = fileLabel(fichier, positions[fichier.resourceId]),
            confirmLabel = "Renommer",
            supporting = "« Tome 1 » se lit mieux qu'un nom de fichier.",
            onConfirm = { nom ->
                onRenameFile(fichier, nom)
                renamingFile = null
            },
            onDismiss = { renamingFile = null },
        )
    }
}

@Composable
private fun FileRow(
    file: LibraryBookFile,
    position: ReadingPosition?,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                fileLabel(file, position),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                pageLabel(position) ?: "Aucun document rattaché",
                style = MaterialTheme.typography.labelSmall,
                color = mutedColor,
            )
        }
        TextButton(onClick = onMenu) { Text("⋯") }
    }
}

/** Le nom donne a la main, sinon celui du fichier, sinon un repli neutre. */
private fun fileLabel(file: LibraryBookFile, position: ReadingPosition?): String =
    file.label.ifBlank { position?.documentLabel.orEmpty() }.ifBlank { "Fichier" }

/**
 * La page, et le pourcentage a cote.
 *
 * Les deux ensemble, ici seulement : dans une liste de tomes, « 21 % » ne dit
 * pas ou reprendre et « page 67 » ne dit pas ce qu'il reste.
 */
private fun pageLabel(position: ReadingPosition?): String? {
    if (position == null) return null
    if (position.pageCount <= 0) return "page ${position.page}"
    val part = fileProgress(position).percent
    return "page ${position.page} sur ${position.pageCount} — $part %"
}

/** La barre pleine a la fraction lue, ou vide quand rien n'est connu. */
@Composable
internal fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(5.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
}

internal fun ReadingProgress.fraction(): Float =
    if (pageTotal <= 0) 0f else (pagesRead.toFloat() / pageTotal.toFloat()).coerceIn(0f, 1f)
