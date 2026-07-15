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
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.diagnostics.openAccessibilitySettings
import com.albugimed.blockerspike.diagnostics.openBatterySettings
import com.albugimed.blockerspike.diagnostics.openNotificationSettings
import com.albugimed.blockerspike.diagnostics.openOverlaySettings
import com.albugimed.blockerspike.diagnostics.readDiagnostics
import com.albugimed.blockerspike.log.InterceptionLog
import com.albugimed.blockerspike.policy.PolicyState
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

    var diagnostics by remember { mutableStateOf(readDiagnostics(context)) }
    LifecycleResumeEffect(Unit) {
        diagnostics = readDiagnostics(context)
        onPauseOrDispose { }
    }

    var newPackage by remember { mutableStateOf("") }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Section("Diagnostic") {
                    DiagnosticRow(
                        label = "Service d'accessibilité",
                        ok = diagnostics.accessibilityEnabled,
                        onOpen = { openAccessibilitySettings(context) },
                    )
                    DiagnosticRow(
                        label = "Notifications",
                        ok = diagnostics.notificationsEnabled,
                        onOpen = { openNotificationSettings(context) },
                    )
                    DiagnosticRow(
                        label = "Affichage au-dessus des apps (T2-B)",
                        ok = diagnostics.canDrawOverlays,
                        onOpen = { openOverlaySettings(context) },
                    )
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
                            onGrant = { scope.launch { repo.grantTemporaryAllowance(pkg, 2 * 60_000L) } },
                            onRevoke = { scope.launch { repo.revokeAllowance(pkg) } },
                            onRemove = { scope.launch { repo.removeBlockedPackage(pkg) } },
                        )
                    }
                }
            }

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
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
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
private fun DiagnosticRow(label: String, ok: Boolean, onOpen: () -> Unit) {
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
    onGrant: () -> Unit,
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
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (allowanceActive) {
                TextButton(onClick = onRevoke) { Text("Révoquer") }
            } else {
                TextButton(onClick = onGrant) { Text("Autoriser 2 min") }
            }
            TextButton(onClick = onRemove) { Text("Retirer") }
        }
    }
}

@Composable
private fun ToggleRow(
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
