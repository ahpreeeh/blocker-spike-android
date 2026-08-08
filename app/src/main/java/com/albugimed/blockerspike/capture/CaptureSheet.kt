package com.albugimed.blockerspike.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.ui.Kicker
import com.albugimed.blockerspike.ui.Notice
import com.albugimed.blockerspike.ui.NoticeTone
import com.albugimed.blockerspike.ui.PrimaryAction
import com.albugimed.blockerspike.ui.mutedColor
import kotlinx.coroutines.launch

/**
 * La capture, reduite a ce qu'elle est : un champ et un bouton.
 *
 * L'ecran precedent expliquait, en quatre paragraphes, que rien n'etait trie
 * ici, que l'envoi partait tout seul, et que le partage Android existait. Une
 * fonction qu'il faut decrire pour qu'on l'utilise n'est pas intuitive — et
 * ces explications occupaient la place au moment precis ou l'on est presse.
 *
 * Une feuille et non un ecran : capturer prend cinq secondes et rend la main
 * la ou l'on etait. Passer par un onglet obligerait a revenir.
 *
 * Rien n'est interprete ici : ni categorie, ni destination, ni rattachement
 * (P4-06). Aucun selecteur ne sera ajoute — le tri se fait plus tard, a la
 * main, dans l'atelier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var draft by rememberSaveable { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Kicker("Capture rapide")
            Text(
                "Garde l'idée, le tri attendra.",
                style = MaterialTheme.typography.headlineSmall,
            )
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    failed = false
                },
                placeholder = {
                    Text(
                        "Un lien, un cours déplacé, une consigne…",
                        color = mutedColor,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp),
                shape = MaterialTheme.shapes.medium,
            )
            PrimaryAction(
                text = "Garder pour plus tard",
                enabled = draft.isNotBlank() && !saving,
                onClick = {
                    val text = draft.trim()
                    if (text.isEmpty()) return@PrimaryAction
                    saving = true
                    failed = false
                    val now = System.currentTimeMillis()
                    // La cle est frappee ici, au moment de la capture. La
                    // deplacer vers l'envoi ferait d'un reessai une seconde
                    // capture, et l'index unique du serveur ne pourrait plus
                    // rien.
                    val capture = Capture(
                        captureId = newCaptureId(now),
                        kind = inferCaptureKind(text),
                        capturedAtMillis = now,
                        // Couper vaut mieux que perdre — meme regle que pour
                        // un partage demesure.
                        text = text.take(MAX_CAPTURE_TEXT_LENGTH),
                        // Ni titre separe, ni application d'origine : une note
                        // ecrite ici ne vient de nulle part ailleurs.
                        subject = null,
                        sourcePackage = null,
                    )
                    val application = context.applicationContext
                    // Portee applicative : fermer la feuille pendant
                    // l'ecriture ne doit pas annuler l'enregistrement.
                    Graph.applicationScope.launch {
                        val stored = Graph.captureOutbox.enqueue(capture)
                        if (stored) {
                            CaptureUploadWorker.schedule(application)
                            draft = ""
                            saving = false
                            onDismiss()
                        } else {
                            // Le seul cas ou l'on perd vraiment quelque chose
                            // est celui d'un accuse de reception mensonger :
                            // la feuille reste ouverte, le texte reste dedans.
                            failed = true
                            saving = false
                        }
                    }
                },
            )
            if (failed) {
                Notice(
                    "Note non enregistrée. Le texte est resté dans le champ, réessaie.",
                    tone = NoticeTone.PROBLEM,
                )
            }
        }
    }
}
