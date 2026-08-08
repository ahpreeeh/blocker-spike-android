package com.albugimed.blockerspike.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.ui.Fact
import com.albugimed.blockerspike.ui.Notice
import com.albugimed.blockerspike.ui.NoticeTone
import com.albugimed.blockerspike.ui.PrimaryAction
import com.albugimed.blockerspike.ui.ScreenHeader
import com.albugimed.blockerspike.ui.SecondaryAction
import com.albugimed.blockerspike.ui.Section
import com.albugimed.blockerspike.ui.SurfaceCard
import com.albugimed.blockerspike.ui.mutedColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** Ce que la dernière saisie a réellement produit. Rien d'autre à retenir. */
private enum class CaptureOutcome { STORED, FAILED }

/**
 * Longueur de l'extrait affiché dans la file morte.
 *
 * Une capture peut peser 20 000 caractères. Les poser tels quels dans un
 * `Text`, même limité à trois lignes, ferait travailler la mise en page sur
 * tout le texte : on coupe avant, l'extrait n'a pas à être fidèle.
 */
private const val PREVIEW_CHARACTERS = 240

private val captureDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

private fun formatCaptureMoment(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(captureDateFormat)

/**
 * La capture vue de l'intérieur de l'application.
 *
 * Le partage Android reste le chemin principal : il coûte un geste et part de
 * n'importe où. Mais il ne montre rien — ni ce qui attend d'être envoyé, ni ce
 * que le serveur a refusé. Cet écran répond à ces deux questions, et sert de
 * porte d'entrée quand l'application est déjà ouverte.
 *
 * Comme le partage, il n'interprète rien : ni catégorie, ni destination, ni
 * rattachement (P4-06). Aucun sélecteur ne sera ajouté ici — le tri se fait
 * plus tard, à la main, dans l'atelier.
 */
@Composable
fun CaptureScreen() {
    val context = LocalContext.current
    val outbox = Graph.captureOutbox

    val pendingCount by outbox.pendingCount.collectAsStateWithLifecycle(initialValue = 0)
    val dead by outbox.dead
        .collectAsStateWithLifecycle(initialValue = emptyList<PendingCaptureRow>())

    var draft by rememberSaveable { mutableStateOf("") }
    var outcome by remember { mutableStateOf<CaptureOutcome?>(null) }
    var saving by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Capture",
                subtitle = "Écrire ce qui passe, sans rien décider maintenant.",
            )
        }

        item {
            Section(title = "Saisir une capture", kicker = "Rien n'est trié ici") {
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        // Le verdict précédent ne parle plus du texte à
                        // l'écran : le laisser afficherait un accusé de
                        // réception pour autre chose.
                        outcome = null
                    },
                    label = { Text("Ce qui passe") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    shape = MaterialTheme.shapes.medium,
                )
                PrimaryAction(
                    text = "Capturer",
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            saving = true
                            val now = System.currentTimeMillis()
                            // La clé est frappée ici, au moment de la
                            // capture. La déplacer vers l'envoi ferait d'un
                            // réessai une seconde capture, et l'index unique
                            // du serveur ne pourrait plus rien.
                            val capture = Capture(
                                captureId = newCaptureId(now),
                                kind = inferCaptureKind(text),
                                capturedAtMillis = now,
                                // Couper vaut mieux que perdre — même règle
                                // que pour un partage démesuré.
                                text = text.take(MAX_CAPTURE_TEXT_LENGTH),
                                // Ni titre séparé, ni application d'origine :
                                // une capture écrite ici ne vient de nulle
                                // part ailleurs, et rien ne serait vrai à
                                // mettre dans ces deux champs.
                                subject = null,
                                sourcePackage = null,
                            )
                            val application = context.applicationContext
                            // Portée applicative, comme pour le partage :
                            // changer d'onglet pendant l'écriture ne doit pas
                            // annuler l'enregistrement.
                            Graph.applicationScope.launch {
                                val stored = outbox.enqueue(capture)
                                if (stored) {
                                    CaptureUploadWorker.schedule(application)
                                    draft = ""
                                }
                                outcome = if (stored) {
                                    CaptureOutcome.STORED
                                } else {
                                    CaptureOutcome.FAILED
                                }
                                saving = false
                            }
                        }
                    },
                    enabled = draft.isNotBlank() && !saving,
                )
                // Le message dit ce qui s'est passé, et rien de plus. Un
                // « Envoyé » ici serait faux : l'envoi vient après, et il ne
                // conditionne rien.
                when (outcome) {
                    CaptureOutcome.STORED -> Notice(
                        "Enregistré. Le tri se fait plus tard, dans l'atelier.",
                    )

                    CaptureOutcome.FAILED -> Notice(
                        "Capture non enregistrée. Le texte est resté dans le champ, " +
                            "réessaie.",
                        tone = NoticeTone.PROBLEM,
                    )

                    null -> Unit
                }
            }
        }

        item {
            Section(title = "Ce qui attend", kicker = "File d'envoi") {
                // `Fact` rend « — » quand la valeur est nulle : un « 0 » se
                // lirait comme un reproche, alors qu'il n'y a rien à signaler.
                Fact("Captures en attente", pendingCount.takeIf { it > 0 }?.toString())
                Text(
                    "L'envoi part tout seul dès que le réseau le permet. Une capture " +
                        "ne quitte l'appareil que lorsque le serveur a confirmé " +
                        "l'avoir reçue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                )
                // Le cas « rien n'attend » appartient a cette carte : dehors,
                // c'etait un paragraphe flottant entre deux blocs.
                if (pendingCount == 0 && dead.isEmpty()) {
                    Text(
                        "Rien n'attend, rien n'a été refusé. Les captures que le " +
                            "serveur a confirmées ne sont plus listées ici : elles se " +
                            "trient dans l'atelier.",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedColor,
                    )
                }
            }
        }

        if (dead.isNotEmpty()) {
            item {
                Section(
                    title = "Ce qui a été refusé",
                    kicker = if (dead.size == 1) "1 capture" else "${dead.size} captures",
                ) {
                    Notice(
                        "Le serveur a refusé ces captures. Elles ne repartiront pas — " +
                            "le même envoi donnerait le même refus — et elles restent " +
                            "ici tant que tu ne les as pas oubliées.",
                        tone = NoticeTone.PROBLEM,
                    )
                }
            }
            items(dead, key = { it.captureId }) { row ->
                DeadCaptureCard(
                    row = row,
                    onForget = {
                        Graph.applicationScope.launch { outbox.forgetDead(row.captureId) }
                    },
                )
            }
        }

        item {
            Section(title = "Depuis une autre application", kicker = "Partage Android") {
                Text(
                    "Le menu de partage d'Android envoie n'importe quel texte ou lien " +
                        "vers cette application sans l'ouvrir. C'est le chemin le plus " +
                        "rapide, et il reste le principal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                )
            }
        }
    }
}

/**
 * Une capture refusée, telle qu'elle est.
 *
 * L'oubli demande deux gestes. Ce n'est pas de la prudence de principe :
 * c'est la seule suppression définitive de tout le flux de capture, et le
 * bouton se trouve à portée de pouce dans une liste qui défile.
 */
@Composable
private fun DeadCaptureCard(
    row: PendingCaptureRow,
    onForget: () -> Unit,
) {
    var confirming by rememberSaveable(row.captureId) { mutableStateOf(false) }

    SurfaceCard {
        Text(
            row.text.take(PREVIEW_CHARACTERS),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Fact("Motif du refus", row.deadReason)
        Fact("Capturée le", formatCaptureMoment(row.capturedAtMillis))

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
                    onClick = {
                        confirming = false
                        onForget()
                    },
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
