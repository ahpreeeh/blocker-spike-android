package com.albugimed.blockerspike.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.albugimed.blockerspike.ui.theme.ActionShape
import com.albugimed.blockerspike.ui.theme.Citron
import com.albugimed.blockerspike.ui.theme.LocalAlbugimedExtras

/**
 * La carte de protection, en haut de l'accueil.
 *
 * Elle existe parce que la raison d'etre de l'application etait invisible :
 * le blocage vivait au fond des reglages, sous un interrupteur, entre
 * l'import de guide et le journal de debogage.
 *
 * **Le blocage n'est pas un mode que l'on choisit.** C'est l'etat normal et
 * permanent de l'appareil. Il n'y a donc pas ici de bascule a deux positions
 * equivalentes, et c'est deliberement dissymetrique :
 *
 * - dans l'etat normal, la seule action ordinaire est *voir ce qui est
 *   refuse*. La suspension n'existe que sous la forme d'un lien discret, qui
 *   n'agit pas : il ouvre un panneau d'avertissement ;
 * - dans l'etat suspendu, la carte devient brune et cerclee d'ambre. Ce n'est
 *   pas une seconde facon normale de se servir du telephone, c'est une
 *   anomalie affichee en permanence, et le retour a la normale est la seule
 *   action mise en avant.
 *
 * La sortie de secours reste **atteignable et sans reseau** — c'est la
 * garantie de ne jamais rester enferme avec son propre telephone. La cacher
 * serait une faute ; l'inviter en etait une autre.
 */
@Composable
fun ProtectionCard(
    blockedCount: Int,
    failsafeOverride: Boolean,
    storageHealthy: Boolean,
    onSuspend: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
    // Nul dans l'onglet Protection : la liste est deja sous la carte, et un
    // bouton « Voir les applications » qui ne mene nulle part est un mensonge.
    onOpenProtection: (() -> Unit)? = null,
) {
    val extras = LocalAlbugimedExtras.current
    var askExit by rememberSaveable { mutableStateOf(false) }

    val shape = RoundedCornerShape(24.dp)
    val background = if (failsafeOverride) extras.protectionPaused else extras.protectionActive
    val bodyColor = if (failsafeOverride) extras.onPausedBody else extras.onProtectionBody
    val kickerColor = if (failsafeOverride) extras.onPausedKicker else extras.onProtectionKicker

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(background, shape)
            // L'anneau ambre n'est dessine que pendant la suspension : c'est le
            // seul signal qui survit a un coup d'oeil d'une seconde en marchant.
            .then(
                if (failsafeOverride) {
                    Modifier.border(2.dp, extras.protectionRing, shape)
                } else {
                    Modifier
                },
            )
            .clip(shape),
    ) {
        // Le cercle qui deborde par le coin bas-droit, repris de la conception.
        // Un trait, pas un aplat : il donne de la profondeur au vert sans rien
        // ajouter a lire, et il est coupe par le `clip` du coin arrondi —
        // c'est ce debordement qui fait que la carte a l'air posee sur quelque
        // chose de plus grand qu'elle.
        Canvas(modifier = Modifier.matchParentSize()) {
            drawCircle(
                color = Citron.Vif.copy(alpha = 0.24f),
                radius = 105.dp.toPx(),
                center = Offset(size.width - 30.dp.toPx(), size.height - 14.dp.toPx()),
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        // « Protection » ne disait pas ce qui se passe : on protege
                        // de quoi, et comment ? « Blocage » nomme le mecanisme, et
                        // c'est le mot que l'utilisateur emploie lui-meme.
                        "BLOCAGE",
                        style = MaterialTheme.typography.labelSmall,
                        color = kickerColor,
                    )
                    Text(
                        if (failsafeOverride) {
                            "Les refus sont suspendus."
                        } else {
                            "Les refus tiennent."
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = extras.onProtectionTitle,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                StatePill(
                    text = if (failsafeOverride) "SUSPENDU" else "EN COURS",
                    dotColor = if (failsafeOverride) {
                        extras.protectionRing
                    } else {
                        extras.onProtectionKicker
                    },
                    textColor = extras.onProtectionTitle,
                )
            }

            Text(
                protectionSummary(failsafeOverride, blockedCount),
                style = MaterialTheme.typography.bodyMedium,
                color = bodyColor,
            )

            if (!storageHealthy) {
                Text(
                    "Le stockage des règles est illisible : aucun refus ne s'applique, " +
                        "quel que soit l'état affiché ci-dessus.",
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.protectionRing,
                )
            }

            if (onOpenProtection != null || failsafeOverride) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    onOpenProtection?.let { open ->
                        // La pilule citron de la conception, avec sa fleche.
                        // Un bouton simplement cercle sur du vert profond se
                        // lisait comme un cadre vide : c'est le seul endroit
                        // de la carte ou l'on peut agir, il doit se voir.
                        Button(
                            onClick = open,
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = ActionShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Citron.Clair,
                                contentColor = extras.protectionActive,
                            ),
                        ) {
                            Text(
                                "Voir les applications",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Glyph(
                                AppIcon.ARROW,
                                size = 15.dp,
                                tint = extras.protectionActive,
                                modifier = Modifier.padding(start = 7.dp),
                            )
                        }
                    }
                    if (failsafeOverride) {
                        Button(
                            onClick = onRestore,
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = ActionShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = extras.protectionRing,
                                contentColor = extras.protectionPaused,
                            ),
                        ) {
                            Text("Rétablir", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            // Le lien n'agit pas : il ouvre l'avertissement. Un dernier recours
            // qui se declenche du premier coup n'est pas un dernier recours.
            if (!failsafeOverride) {
                TextButton(
                    onClick = { askExit = true },
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) {
                    Text(
                        "Sortie de secours",
                        style = MaterialTheme.typography.bodySmall,
                        color = extras.onProtectionLink,
                        textDecoration = TextDecoration.Underline,
                    )
                }
            }
        }
    }

    if (askExit) {
        EmergencyExitSheet(
            onDismiss = { askExit = false },
            onConfirm = {
                askExit = false
                onSuspend()
            },
        )
    }
}

private fun protectionSummary(failsafeOverride: Boolean, blockedCount: Int): String = when {
    failsafeOverride ->
        "Tout est ouvrable sur cet appareil. Cet état reste affiché ici tant que " +
            "les refus ne sont pas rétablis."

    blockedCount == 0 ->
        "Aucune application n'est refusée. Rien ne sera empêché tant que la liste est vide."

    blockedCount == 1 ->
        "Une application reste indisponible sur cet appareil."

    else ->
        "$blockedCount applications restent indisponibles sur cet appareil."
}

@Composable
private fun StatePill(text: String, dotColor: androidx.compose.ui.graphics.Color, textColor: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .background(
                androidx.compose.ui.graphics.Color.White.copy(alpha = 0.14f),
                CircleShape,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(7.dp).background(dotColor, CircleShape))
        Text(text, style = MaterialTheme.typography.labelSmall, color = textColor)
    }
}

/**
 * L'avertissement, avant de suspendre.
 *
 * Il ne dissuade pas et ne culpabilise pas : il **constate** ce que l'action
 * fait, et rappelle qu'elle ne depend ni du reseau ni du modele local. C'est
 * cette independance qui fait d'elle une garantie et non une fonctionnalite.
 *
 * `ModalBottomSheet` est encore marque experimental dans Material 3 : c'est la
 * seule feuille modale du projet, et l'alternative — une `AlertDialog` — ne
 * donne ni la hauteur ni la place au bas de l'ecran, la ou le pouce se trouve.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmergencyExitSheet(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val extras = LocalAlbugimedExtras.current
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
            Kicker("Dernier recours · sans réseau")
            Text(
                "Une urgence t'empêche d'avancer ?",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "Cette action suspend tous les refus sur cet appareil. C'est un état " +
                    "anormal : il restera affiché sur l'accueil jusqu'à ce que tu " +
                    "rétablisses les refus toi-même.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Elle fonctionne sans réseau et sans le modèle local. C'est ce qui en " +
                    "fait une garantie de ne jamais rester enfermé, et non une " +
                    "fonctionnalité de confort.",
                style = MaterialTheme.typography.bodySmall,
                color = mutedColor,
            )
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = ActionShape,
                border = BorderStroke(1.dp, extras.exitBorder),
                colors = ButtonDefaults.buttonColors(
                    containerColor = extras.exitBackground,
                    contentColor = extras.exitForeground,
                ),
            ) {
                Text("Suspendre les refus", style = MaterialTheme.typography.labelLarge)
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text("Ne rien changer")
            }
        }
    }
}
