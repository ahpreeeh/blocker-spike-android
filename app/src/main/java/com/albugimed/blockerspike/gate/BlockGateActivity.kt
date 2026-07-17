package com.albugimed.blockerspike.gate

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.inference.InferenceClient
import com.albugimed.blockerspike.inference.InferenceService
import com.albugimed.blockerspike.inference.PromptTemplates
import com.albugimed.blockerspike.log.InterceptionLog
import com.albugimed.blockerspike.policy.PolicyState
import com.albugimed.blockerspike.policy.UnlockDecisionValidator
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Écran neutre affiché après interception (protocole §3) : explique quelle
 * application a été bloquée et, pour le spike, n'offre que des autorisations
 * manuelles de test. Le parcours de demande à l'IA viendra plus tard.
 */
class BlockGateActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val blockedPackage = intent.getStringExtra(EXTRA_PACKAGE) ?: "(package inconnu)"
        GateLaunchTracker.markShown(intent.getStringExtra(EXTRA_LAUNCH_TOKEN))
        setContent {
            MaterialTheme {
                GateScreen(blockedPackage = blockedPackage, onDone = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        GateLaunchTracker.markShown(intent.getStringExtra(EXTRA_LAUNCH_TOKEN))
    }

    companion object {
        private const val EXTRA_PACKAGE = "blocked_package"
        private const val EXTRA_LAUNCH_TOKEN = "launch_token"

        fun intent(
            context: Context,
            blockedPackage: String,
            launchToken: String? = null,
        ): Intent = Intent(context, BlockGateActivity::class.java)
            .putExtra(EXTRA_PACKAGE, blockedPackage)
            .apply { if (launchToken != null) putExtra(EXTRA_LAUNCH_TOKEN, launchToken) }
    }
}

@Composable
private fun GateScreen(blockedPackage: String, onDone: () -> Unit) {
    val repo = Graph.policyRepository
    val policy by repo.policy.collectAsStateWithLifecycle(initialValue = PolicyState())
    val scope = rememberCoroutineScope()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Application bloquée", style = MaterialTheme.typography.headlineSmall)
            Text(blockedPackage, style = MaterialTheme.typography.titleMedium)
            Text(
                "Le parcours de demande d'autorisation à l'IA arrivera dans une " +
                    "phase ultérieure. Pour ce spike, seules des autorisations " +
                    "manuelles de test sont disponibles.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (policy.failsafeOverride) {
                Text(
                    "Override de panne ACTIF : aucun blocage appliqué.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!policy.storageHealthy) {
                Text(
                    "Stockage illisible : consultez le diagnostic, l'override reste disponible.",
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // --- Parcours T3 : demande d'exception décidée par l'IA locale. ---
            val context = LocalContext.current
            var aiBusy by remember { mutableStateOf(false) }
            var aiResult by remember { mutableStateOf<String?>(null) }
            val modelPresent = remember { InferenceService.modelFile(context).exists() }

            Button(
                onClick = {
                    scope.launch {
                        aiBusy = true
                        aiResult = null
                        val prompt = PromptTemplates.unlockRequest(
                            packageName = blockedPackage,
                            localTime = SimpleDateFormat("EEEE HH:mm", Locale.FRANCE).format(Date()),
                            activeAllowances = policy.allowedUntil
                                .count { it.value > System.currentTimeMillis() },
                        )
                        aiResult = InferenceClient(context).generate(prompt).fold(
                            onSuccess = { r ->
                                when (val outcome =
                                    UnlockDecisionValidator.validate(r.text, blockedPackage, policy)) {
                                    is UnlockDecisionValidator.Outcome.Granted -> {
                                        repo.grantTemporaryAllowance(
                                            blockedPackage,
                                            outcome.durationMinutes * 60_000L,
                                        )
                                        InterceptionLog.add(
                                            InterceptionLog.TAG_AI,
                                            "Accordé ${outcome.durationMinutes} min " +
                                                "(${r.backend}, charge ${r.loadMs} ms, " +
                                                "génération ${r.genMs} ms) — ${outcome.reason}",
                                            packageName = blockedPackage,
                                        )
                                        "Accordé : ${outcome.durationMinutes} min — ${outcome.reason}"
                                    }
                                    is UnlockDecisionValidator.Outcome.Denied -> {
                                        InterceptionLog.add(
                                            InterceptionLog.TAG_AI,
                                            "Refusé par le modèle (génération ${r.genMs} ms) — ${outcome.reason}",
                                            packageName = blockedPackage,
                                        )
                                        "Refusé par le modèle : ${outcome.reason}"
                                    }
                                    is UnlockDecisionValidator.Outcome.Rejected -> {
                                        InterceptionLog.add(
                                            InterceptionLog.TAG_AI,
                                            "Sortie rejetée — ${outcome.why} — brut : ${r.text.take(200)}",
                                            packageName = blockedPackage,
                                        )
                                        "Rejeté par le validateur : ${outcome.why}"
                                    }
                                }
                            },
                            onFailure = { e ->
                                InterceptionLog.add(
                                    InterceptionLog.TAG_ERROR,
                                    "Inférence impossible : ${e.message}",
                                    packageName = blockedPackage,
                                )
                                "Erreur d'inférence : ${e.message}"
                            },
                        )
                        aiBusy = false
                    }
                },
                enabled = modelPresent && !aiBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (aiBusy) "Décision en cours…" else "Demander une exception (IA)") }
            if (!modelPresent) {
                Text(
                    "Modèle absent : ${InferenceService.modelFile(context).absolutePath}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            aiResult?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = {
                    scope.launch {
                        repo.grantTemporaryAllowance(blockedPackage, 2 * 60_000L)
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Autoriser 2 minutes (test S2)") }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        repo.grantTemporaryAllowance(blockedPackage, 15 * 60_000L)
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Autoriser 15 minutes (test)") }
            TextButton(onClick = onDone) { Text("Rester bloqué") }
        }
    }
}
