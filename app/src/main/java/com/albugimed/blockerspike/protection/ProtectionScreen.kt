package com.albugimed.blockerspike.protection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.gate.BlockGateActivity
import com.albugimed.blockerspike.policy.PolicyState
import com.albugimed.blockerspike.ui.PrimaryAction
import com.albugimed.blockerspike.ui.ProtectionCard
import com.albugimed.blockerspike.ui.Section
import com.albugimed.blockerspike.ui.mutedColor
import com.albugimed.blockerspike.ui.settings.formatTime
import kotlinx.coroutines.launch

/**
 * La page Blocage, rangee derriere « Plus ».
 *
 * Elle n'existait pas : ce que l'appareil refuse — la seule fonction dont
 * l'effet se manifeste **en dehors** de l'application — se reglait au fond de
 * l'ecran des reglages, entre les autorisations Android et le journal de
 * debogage. Le sortir de la est le changement de structure le plus important
 * de cette refonte.
 *
 * Rien ici ne demande de confirmation : une application ajoutee est refusee
 * immediatement, une application retiree cesse de l'etre immediatement. Le
 * seul geste qui s'accompagne d'un avertissement est la suspension generale,
 * et il vit dans [ProtectionCard].
 */
@Composable
fun ProtectionScreen() {
    val context = LocalContext.current
    val repo = Graph.policyRepository
    val policy by repo.policy.collectAsStateWithLifecycle(initialValue = PolicyState())
    val ownerRuntime by Graph.deviceOwnerController.runtime.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var newPackage by rememberSaveable { mutableStateOf("") }
    val blocked = remember(policy.blockedPackages) { policy.blockedPackages.sorted() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Pas de `ScreenHeader` ici : la carte porte deja le mot « Blocage »
        // et dit l'etat en toutes lettres. Un titre au-dessus faisait lire deux
        // fois la meme chose, juste apres l'avoir lue sur l'accueil.
        item {
            ProtectionCard(
                blockedCount = blocked.size,
                failsafeOverride = policy.failsafeOverride,
                storageHealthy = policy.storageHealthy,
                onSuspend = { scope.launch { repo.setFailsafeOverride(true) } },
                onRestore = { scope.launch { repo.setFailsafeOverride(false) } },
            )
        }

        item {
            Text(
                "Autonome et hors ligne : les refus tiennent sans réseau et ne se " +
                    "contournent pas en fermant l'application.",
                style = MaterialTheme.typography.bodySmall,
                color = mutedColor,
            )
        }

        item {
            Section(title = "Ce qui reste fermé", kicker = "Liste") {
                if (blocked.isEmpty()) {
                    Text(
                        "Aucune application refusée pour l'instant.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = mutedColor,
                    )
                } else {
                    blocked.forEachIndexed { index, pkg ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        BlockedPackageRow(
                            pkg = pkg,
                            policy = policy,
                            actuallySuspended = pkg in ownerRuntime.suspendedPackages,
                            onRequest = {
                                context.startActivity(BlockGateActivity.intent(context, pkg))
                            },
                            onRevoke = { scope.launch { repo.revokeAllowance(pkg) } },
                            onRemove = { scope.launch { repo.removeBlockedPackage(pkg) } },
                        )
                    }
                }
            }
        }

        item {
            Section(title = "Ajouter une application", kicker = "Refusée dès l'ajout") {
                OutlinedTextField(
                    value = newPackage,
                    onValueChange = { newPackage = it },
                    label = { Text("Nom du package") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { newPackage = "com.instagram.android" },
                        label = { Text("Instagram") },
                    )
                    AssistChip(
                        onClick = { newPackage = "com.zhiliaoapp.musically" },
                        label = { Text("TikTok") },
                    )
                }
                PrimaryAction(
                    text = "Refuser cette application",
                    onClick = {
                        scope.launch {
                            repo.addBlockedPackage(newPackage)
                            newPackage = ""
                        }
                    },
                    enabled = newPackage.isNotBlank(),
                )
            }
        }
    }
}

@Composable
private fun BlockedPackageRow(
    pkg: String,
    policy: PolicyState,
    actuallySuspended: Boolean,
    onRequest: () -> Unit,
    onRevoke: () -> Unit,
    onRemove: () -> Unit,
) {
    val until = policy.allowedUntil[pkg]
    val allowanceActive = until != null && until > System.currentTimeMillis()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(pkg, style = MaterialTheme.typography.bodyMedium)
        Text(
            when {
                policy.failsafeOverride -> "Refus suspendus — ouvrable"
                allowanceActive -> "Autorisée jusqu'à ${formatTime(until!!)}"
                else -> "Refusée"
            },
            style = MaterialTheme.typography.bodySmall,
            color = mutedColor,
        )
        Text(
            if (actuallySuspended) "Android : suspendue" else "Android : non suspendue",
            style = MaterialTheme.typography.bodySmall,
            color = mutedColor,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (allowanceActive) {
                TextButton(onClick = onRevoke) { Text("Révoquer") }
            } else {
                TextButton(onClick = onRequest) { Text("Demander une exception") }
            }
            TextButton(onClick = onRemove) { Text("Retirer") }
        }
    }
}
