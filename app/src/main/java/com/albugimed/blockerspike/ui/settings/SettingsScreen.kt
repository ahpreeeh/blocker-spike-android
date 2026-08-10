package com.albugimed.blockerspike.ui.settings

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.albugimed.blockerspike.guide.GuideAvailability
import com.albugimed.blockerspike.guide.blockGuidePreview
import com.albugimed.blockerspike.guide.displayText
import com.albugimed.blockerspike.guide.formatGuideImportDate
import com.albugimed.blockerspike.policy.PolicyState
import com.albugimed.blockerspike.ui.AppDestination
import com.albugimed.blockerspike.ui.Chevron
import com.albugimed.blockerspike.ui.DiagnosticRow
import com.albugimed.blockerspike.ui.Fact
import com.albugimed.blockerspike.ui.Notice
import com.albugimed.blockerspike.ui.NoticeTone
import com.albugimed.blockerspike.ui.PrimaryAction
import com.albugimed.blockerspike.ui.ScreenHeader
import com.albugimed.blockerspike.ui.SecondaryAction
import com.albugimed.blockerspike.ui.Section
import com.albugimed.blockerspike.ui.mutedColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Le hall : les pages qu'on ouvre rarement, puis ce qui se regle.
 *
 * L'ecran d'accueil precedent melangeait la file d'etudes, les autorisations
 * Android, le journal de debogage et les leviers d'experimentation dans une
 * seule colonne de neuf cartes. Le decoupage tient a une question simple :
 * **est-ce que je viens ici pour regler quelque chose, ou pour observer ?**
 * Ce qui sert a observer est parti dans [InstrumentsScreen].
 *
 * L'ecran portait aussi, un temps, une liste de portes vers Blocage, Notes et
 * Lectures. Le tiroir les montre desormais toutes en meme temps : garder le
 * hall reviendrait a demander deux fois le meme chemin, et a laisser croire
 * que ces pages appartiennent aux reglages alors qu'elles n'y ont jamais
 * appartenu.
 */
@Composable
fun SettingsScreen(onOpen: (AppDestination) -> Unit) {
    val context = LocalContext.current
    val repo = Graph.policyRepository
    val policy by repo.policy.collectAsStateWithLifecycle(initialValue = PolicyState())
    val ownerRuntime by Graph.deviceOwnerController.runtime.collectAsStateWithLifecycle()
    val guideState by Graph.blockGuideRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var diagnostics by remember { mutableStateOf(readDiagnostics(context)) }
    LifecycleResumeEffect(Unit) {
        diagnostics = readDiagnostics(context)
        onPauseOrDispose { }
    }

    var showOwnerRemovalDialog by remember { mutableStateOf(false) }
    var showOwnershipTransferDialog by remember { mutableStateOf(false) }

    val guidePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    val sourceName = runCatching {
                        context.contentResolver.query(
                            uri,
                            arrayOf(OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null,
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }
                    }.getOrNull()
                    val stream = runCatching {
                        context.contentResolver.openInputStream(uri)
                    }.getOrNull()
                    if (stream == null) {
                        Graph.blockGuideRepository.importGuide(
                            sourceName = sourceName,
                            input = object : java.io.InputStream() {
                                override fun read(): Int = throw java.io.IOException(
                                    "Document inaccessible",
                                )
                            },
                        )
                    } else {
                        Graph.blockGuideRepository.importGuide(sourceName, stream)
                    }
                }
            }
        }
    }

    if (showOwnershipTransferDialog) {
        AlertDialog(
            onDismissRequest = { showOwnershipTransferDialog = false },
            title = { Text("Transférer le Device Owner ?") },
            text = {
                Text(
                    "Le spike perdra définitivement le rôle au profit de " +
                        "l'identité permanente com.albugimed.app.",
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
                ) { Text("Transférer") }
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
                    "Toutes les applications seront désuspendues avant que le " +
                        "prototype abandonne son contrôle de l'appareil.",
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                // « Appareil » et non « Réglages » : le tiroir range maintenant
                // cette page *dans* une famille qui porte ce nom, et un écran
                // homonyme de sa section se lit comme un doublon. C'est bien de
                // l'appareil qu'il s'agit — autorisations, Device Owner, batterie.
                title = "Appareil",
                subtitle = "Ce qui se règle une fois pour tenir ensuite tout seul.",
            )
        }

        item {
            Section(title = "Autorisations Android", kicker = "Ce que le système accorde") {
                DiagnosticRow(
                    label = "Device Owner",
                    ok = diagnostics.deviceOwner,
                    onOpen = { openDeviceAdminSettings(context) },
                )
                DiagnosticRow(
                    label = "Alarmes exactes",
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
                        label = "Affichage au-dessus des apps",
                        ok = diagnostics.canDrawOverlays,
                        onOpen = { openOverlaySettings(context) },
                    )
                }
                DiagnosticRow(
                    label = "Batterie sans restriction",
                    ok = diagnostics.ignoringBatteryOptimizations,
                    onOpen = { openBatterySettings(context) },
                )
            }
        }

        item {
            GuideSection(
                availability = guideState.availability,
                onImport = { guidePicker.launch(arrayOf("text/markdown", "text/plain")) },
                content = {
                    when (guideState.availability) {
                        GuideAvailability.ABSENT -> Text(
                            "Aucun guide importé.",
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        GuideAvailability.CORRUPTED -> Notice(
                            "Guide illisible : aucune de ses règles n'est exposée.",
                            tone = NoticeTone.PROBLEM,
                        )

                        GuideAvailability.ACTIVE -> {
                            val guide = requireNotNull(guideState.active)
                            Fact("Version", guide.metadata.guideVersion)
                            Fact("Mis à jour le", guide.metadata.updatedAt.toString())
                            Fact(
                                "Importé le",
                                formatGuideImportDate(guide.metadata.importedAtMillis),
                            )
                            Fact("Taille", "${guide.metadata.sizeBytes} octets")
                            Text(
                                "SHA-256 ${guide.metadata.sha256}",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedColor,
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(
                                blockGuidePreview(guide.body),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    guideState.storageProblem?.let { problem ->
                        Notice(problem, tone = NoticeTone.PROBLEM)
                    }

                    guideState.lastReport?.let { report ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            if (report.accepted) {
                                "Dernière validation : acceptée"
                            } else {
                                "Dernière validation : refusée"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (report.accepted) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        report.sourceName?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedColor,
                            )
                        }
                        report.issues.forEach { issue ->
                            Text(
                                issue.displayText(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        }

        item {
            Section(title = "Appareil", kicker = "Suspension système") {
                Text(
                    when {
                        ownerRuntime.deviceOwner && BuildConfig.PERMANENT_IDENTITY ->
                            "Albugimed est le Device Owner permanent."

                        ownerRuntime.deviceOwner ->
                            "Spike Device Owner : prêt pour la migration vers Albugimed."

                        else ->
                            "Device Owner inactif : la suspension système ne peut pas s'appliquer."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ownerRuntime.deviceOwner) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Fact(
                    "Applications suspendues",
                    "${ownerRuntime.suspendedPackages.size} / ${ownerRuntime.managedTargetCount}",
                )
                if (BuildConfig.PERMANENT_IDENTITY) {
                    Fact(
                        "Sauvegarde Google",
                        if (ownerRuntime.backupServiceEnabled) "Active" else "Inactive",
                    )
                }
                Fact("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                ownerRuntime.lastError?.let { error ->
                    Notice(error, tone = NoticeTone.PROBLEM)
                }
                SecondaryAction(
                    text = "Réappliquer maintenant",
                    onClick = { scope.launch { Graph.deviceOwnerController.reconcile(policy) } },
                )
                if (ownerRuntime.deviceOwner && !BuildConfig.PERMANENT_IDENTITY) {
                    SecondaryAction(
                        text = "Transférer vers Albugimed",
                        onClick = { showOwnershipTransferDialog = true },
                    )
                    TextButton(onClick = { showOwnerRemovalDialog = true }) {
                        Text("Retirer le Device Owner (secours)")
                    }
                }
            }
        }

        item {
            Section(title = "Instruments de test", kicker = "Observation") {
                Text(
                    "Mesures, variantes d'interruption, modèle local et journal. " +
                        "Rien ici ne se règle : ça s'observe.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = mutedColor,
                )
                SecondaryAction(
                    text = "Ouvrir les instruments",
                    onClick = { onOpen(AppDestination.INSTRUMENTS) },
                )
            }
        }
    }
}

/**
 * Le guide, avec la phrase que l'ecran precedent ne disait pas.
 *
 * Le magasin verifie le fichier, en garde l'empreinte et l'affiche — mais
 * **aucune regle de blocage ne le lit encore**. Le taire donnait l'illusion
 * d'une fonctionnalite finie ; le dire coute une ligne et rend l'ecran
 * honnete.
 */
@Composable
private fun GuideSection(
    availability: GuideAvailability,
    onImport: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Section(title = "Guide de blocage", kicker = "Importé, pas encore appliqué") {
        Notice(
            "Le guide est vérifié et conservé, mais aucune règle de blocage ne " +
                "le lit dans cette version. Importer un guide ne change pas ce " +
                "que l'appareil bloque.",
        )
        content()
        PrimaryAction(
            text = if (availability == GuideAvailability.ABSENT) {
                "Importer un guide .md"
            } else {
                "Remplacer le guide"
            },
            onClick = onImport,
        )
    }
}

