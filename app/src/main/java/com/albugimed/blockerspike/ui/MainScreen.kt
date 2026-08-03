package com.albugimed.blockerspike.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.albugimed.blockerspike.BuildConfig
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.diagnostics.openAccessibilitySettings
import com.albugimed.blockerspike.diagnostics.openBatterySettings
import com.albugimed.blockerspike.diagnostics.openDeviceAdminSettings
import com.albugimed.blockerspike.diagnostics.openExactAlarmSettings
import com.albugimed.blockerspike.diagnostics.openNotificationSettings
import com.albugimed.blockerspike.diagnostics.openOverlaySettings
import com.albugimed.blockerspike.diagnostics.readDiagnostics
import com.albugimed.blockerspike.gate.BlockGateActivity
import com.albugimed.blockerspike.inference.ModelLocator
import com.albugimed.blockerspike.log.InterceptionLog
import com.albugimed.blockerspike.policy.PolicyState
import com.albugimed.blockerspike.study.StudyQueueActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun formatTime(millis: Long): String = timeFormat.format(Date(millis))

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val repo = Graph.policyRepository
    val policy by repo.policy.collectAsStateWithLifecycle(initialValue = PolicyState())
    val logEntries by InterceptionLog.entries.collectAsStateWithLifecycle()
    val metrics = InterceptionLog.metrics()
    val scope = rememberCoroutineScope()
    val ownerRuntime by Graph.deviceOwnerController.runtime.collectAsStateWithLifecycle()

    var diagnostics by remember { mutableStateOf(readDiagnostics(context)) }
    var localModelPath by remember {
        mutableStateOf(ModelLocator.findModel(context).getOrNull()?.absolutePath)
    }
    LifecycleResumeEffect(Unit) {
        diagnostics = readDiagnostics(context)
        localModelPath = ModelLocator.findModel(context).getOrNull()?.absolutePath
        onPauseOrDispose { }
    }

    var newPackage by remember { mutableStateOf("") }
    var showOwnerRemovalDialog by remember { mutableStateOf(false) }
    var showOwnershipTransferDialog by remember { mutableStateOf(false) }

    if (showOwnershipTransferDialog) {
        AlertDialog(
            onDismissRequest = { showOwnershipTransferDialog = false },
            title = { Text("Transferer le Device Owner ?") },
            text = {
                Text(
                    "Le spike perdra definitivement le role au profit de " +
                        "l'identite permanente com.albugimed.app."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showOwnershipTransferDialog = false
                        scope.launch {
                            Graph.deviceOwnerController.transferToPermanentOwner(policy)
                        }
                    },
                ) { Text("Transferer") }
            },
            dismissButton = {
                TextButton(onClick = { showOwnershipTransferDialog = false }) {
                    Text("Annuler")
                }
            },
        )
    }

    if (showOwnerRemovalDialog && !BuildConfig.PERMANENT_IDENTITY) {
        AlertDialog(
            onDismissRequest = { showOwnerRemovalDialog = false },
            title = { Text("Retirer le Device Owner ?") },
            text = {
                Text(
                    "Toutes les applications seront desuspendues avant que le " +
                        "prototype abandonne son controle de l'appareil."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showOwnerRemovalDialog = false
                        scope.launch { Graph.deviceOwnerController.relinquishDeviceOwner() }
                    },
                ) { Text("Retirer") }
            },
            dismissButton = {
                TextButton(onClick = { showOwnerRemovalDialog = false }) {
                    Text("Annuler")
                }
            },
        )
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Section("Études") {
                    Button(
                        onClick = {
                            context.startActivity(StudyQueueActivity.intent(context))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Ouvrir la file")
                    }
                }
            }

            item {
                Section("Suspension systeme") {
                    Text(
                        if (ownerRuntime.deviceOwner && BuildConfig.PERMANENT_IDENTITY) {
                            "Albugimed V0 est le Device Owner permanent."
                        } else if (ownerRuntime.deviceOwner) {
                            "Spike Device Owner : pret pour la migration vers Albugimed V0."
                        } else {
                            "Device Owner inactif : la suspension systeme ne peut pas etre appliquee."
                        },
                        color = if (ownerRuntime.deviceOwner) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Text(
                        "${ownerRuntime.suspendedPackages.size}/${ownerRuntime.managedTargetCount} " +
                            "package(s) actuellement suspendu(s)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (BuildConfig.PERMANENT_IDENTITY) {
                        Text(
                            if (ownerRuntime.backupServiceEnabled) {
                                "Sauvegarde Google active"
                            } else {
                                "Sauvegarde Google inactive"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ownerRuntime.lastError?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error)
                    }
                    Button(
                        onClick = { scope.launch { Graph.deviceOwnerController.reconcile(policy) } },
                    ) { Text("Reappliquer maintenant") }
                    if (ownerRuntime.deviceOwner && !BuildConfig.PERMANENT_IDENTITY) {
                        Button(onClick = { showOwnershipTransferDialog = true }) {
                            Text("Transferer vers Albugimed V0")
                        }
                        TextButton(onClick = { showOwnerRemovalDialog = true }) {
                            Text("Retirer le Device Owner (secours)")
                        }
                    }
                }
            }

            item {
                Section("Diagnostic") {
                    DiagnosticRow(
                        label = "Device Owner",
                        ok = diagnostics.deviceOwner,
                        onOpen = { openDeviceAdminSettings(context) },
                    )
                    DiagnosticRow(
                        label = "Alarmes exactes (fin des autorisations)",
                        ok = diagnostics.exactAlarmsAllowed,
                        onOpen = { openExactAlarmSettings(context) },
                    )
                    if (!BuildConfig.PERMANENT_IDENTITY) {
                        DiagnosticRow(
                            label = "Service d'accessibilité",
                            ok = diagnostics.accessibilityEnabled,
                            onOpen = { openAccessibilitySettings(context) },
                        )
                    }
                    DiagnosticRow(
                        label = "Notifications",
                        ok = diagnostics.notificationsEnabled,
                        onOpen = { openNotificationSettings(context) },
                    )
                    if (!BuildConfig.PERMANENT_IDENTITY) {
                        DiagnosticRow(
                            label = "Affichage au-dessus des apps (T2-B)",
                            ok = diagnostics.canDrawOverlays,
                            onOpen = { openOverlaySettings(context) },
                        )
                    }
                    DiagnosticRow(
                        label = "Batterie sans restriction",
                        ok = diagnostics.ignoringBatteryOptimizations,
                        onOpen = { openBatterySettings(context) },
                    )
                    if (!policy.storageHealthy) {
                        Text(
                            "⚠ Stockage illisible : blocages inactifs, override disponible.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    val lastEvent = logEntries.firstOrNull { it.tag == InterceptionLog.TAG_EVENT }
                    val lastIntercept =
                        logEntries.firstOrNull { it.tag == InterceptionLog.TAG_INTERCEPT }
                    Text(
                        "Dernier événement : " +
                            (lastEvent?.let { "${formatTime(it.atMillis)} — ${it.message}" }
                                ?: "aucun"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Dernière interception : " +
                            (lastIntercept?.let { "${formatTime(it.atMillis)} — ${it.message}" }
                                ?: "aucune"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Mesures S1 : ${metrics.interceptionCount} interception(s), " +
                            "retour accueil OK ${metrics.successfulHomeActions}/" +
                            "${metrics.interceptionCount}, médiane " +
                            (metrics.medianLatencyMillis?.let { "$it ms" } ?: "—"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item {
                Section("IA locale T3") {
                    Text(
                        if (localModelPath != null) {
                            "Modèle local détecté : ${localModelPath?.substringAfterLast('\\')}"
                        } else {
                            "Modèle .litertlm absent : toute demande sera refusée proprement."
                        },
                        color = if (localModelPath != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    if (localModelPath == null) {
                        Text(
                            "Fichier attendu : ${ModelLocator.adbModelFile(context).path}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "Inférence LiteRT-LM isolée ; validation déterministe avant toute autorisation.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item {
                Section("Packages bloqués") {
                    Text(
                        "État initial d'un package sélectionné : bloqué.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = newPackage,
                        onValueChange = { newPackage = it },
                        label = { Text("Nom du package") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
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
                    Button(
                        onClick = {
                            scope.launch {
                                repo.addBlockedPackage(newPackage)
                                newPackage = ""
                            }
                        },
                        enabled = newPackage.isNotBlank(),
                    ) { Text("Bloquer ce package") }

                    policy.blockedPackages.sorted().forEach { pkg ->
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

            if (!BuildConfig.PERMANENT_IDENTITY) {
                item {
                    Section("Variante d'interruption") {
                        ToggleRow(
                            title = if (policy.variantB) "T2-B — écran de blocage immédiat"
                            else "T2-A — retour accueil + notification",
                            subtitle = "T2-B exige l'affichage au-dessus des apps ; " +
                                "repli automatique sur la notification sinon.",
                            checked = policy.variantB,
                            onCheckedChange = { scope.launch { repo.setVariantB(it) } },
                        )
                    }
                }
            }

            item {
                Section("Override de panne") {
                    ToggleRow(
                        title = if (policy.failsafeOverride) "Override ACTIF — aucun blocage"
                        else "Override inactif",
                        subtitle = "Fonctionne sans IA et sans réseau (scénario S5).",
                        checked = policy.failsafeOverride,
                        onCheckedChange = { scope.launch { repo.setFailsafeOverride(it) } },
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Journal",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = InterceptionLog::clear) { Text("Effacer") }
                }
            }
            items(logEntries.take(50)) { entry ->
                Text(
                    "${formatTime(entry.atMillis)}  [${entry.tag}] ${entry.message}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun DiagnosticRow(label: String, ok: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (ok) "✔" else "✘", color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Text(
            label,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        TextButton(onClick = onOpen) { Text("Ouvrir") }
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(pkg, style = MaterialTheme.typography.bodyMedium)
        Text(
            when {
                policy.failsafeOverride -> "override actif — non bloqué"
                allowanceActive -> "autorisé jusqu'à ${formatTime(until!!)}"
                else -> "bloqué"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            if (actuallySuspended) "Android : suspendu" else "Android : non suspendu",
            style = MaterialTheme.typography.bodySmall,
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

@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
