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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.inference.UnlockRequestResult
import com.albugimed.blockerspike.inference.UnlockRequestStatus
import com.albugimed.blockerspike.policy.PolicyState
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

/** Neutral gate routing every temporary exception through the T3 validator. */
class BlockGateActivity : ComponentActivity() {
    private val blockedPackage = MutableStateFlow(UNKNOWN_PACKAGE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        blockedPackage.value = packageFrom(intent)
        GateLaunchTracker.markShown(intent.getStringExtra(EXTRA_LAUNCH_TOKEN))
        setContent {
            MaterialTheme {
                val packageName by blockedPackage.collectAsStateWithLifecycle()
                // Reset the form and cancel its coroutine if singleTop receives
                // a different blocked package while this Activity is visible.
                key(packageName) {
                    GateScreen(blockedPackage = packageName, onDone = { finish() })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        blockedPackage.value = packageFrom(intent)
        GateLaunchTracker.markShown(intent.getStringExtra(EXTRA_LAUNCH_TOKEN))
    }

    companion object {
        private const val EXTRA_PACKAGE = "blocked_package"
        private const val EXTRA_LAUNCH_TOKEN = "launch_token"
        private const val UNKNOWN_PACKAGE = "(package inconnu)"

        private fun packageFrom(intent: Intent): String =
            intent.getStringExtra(EXTRA_PACKAGE) ?: UNKNOWN_PACKAGE

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
    val policy by Graph.policyRepository.policy.collectAsStateWithLifecycle(
        initialValue = PolicyState()
    )
    val scope = rememberCoroutineScope()
    var justification by remember { mutableStateOf("") }
    var requestRunning by remember { mutableStateOf(false) }
    var requestResult by remember { mutableStateOf<UnlockRequestResult?>(null) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Application bloquee", style = MaterialTheme.typography.headlineSmall)
            Text(blockedPackage, style = MaterialTheme.typography.titleMedium)
            Text(
                "Explique pourquoi un acces ponctuel est necessaire. Le moteur local " +
                    "peut refuser et ne peut jamais accorder plus de 30 minutes.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (policy.failsafeOverride) {
                Text(
                    "Override de panne ACTIF : aucun blocage applique.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!policy.storageHealthy) {
                Text(
                    "Stockage illisible : aucune exception ne sera ecrite.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(
                value = justification,
                onValueChange = {
                    justification = it.take(800)
                    requestResult = null
                },
                label = { Text("Justification") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                enabled = !requestRunning,
            )
            Button(
                onClick = {
                    scope.launch {
                        requestRunning = true
                        requestResult = Graph.unlockRequestCoordinator.request(
                            packageName = blockedPackage,
                            justification = justification,
                        )
                        requestRunning = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = justification.isNotBlank() && !requestRunning,
            ) {
                if (requestRunning) {
                    CircularProgressIndicator()
                } else {
                    Text("Demander une exception")
                }
            }
            requestResult?.let { result -> RequestResult(result) }
            TextButton(onClick = onDone) { Text("Fermer") }
        }
    }
}

@Composable
private fun RequestResult(result: UnlockRequestResult) {
    Text(
        when (result.status) {
            UnlockRequestStatus.ALLOWED ->
                "Autorise ${result.durationMinutes} min : ${result.message}"
            UnlockRequestStatus.DENIED -> "Refuse : ${result.message}"
            UnlockRequestStatus.ERROR -> result.message
        },
        color = if (result.status == UnlockRequestStatus.ALLOWED) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        },
    )
    if (result.generationDurationMillis != null) {
        Text(
            "Mesures T3 (${result.backend}) : chargement ${result.loadDurationMillis} ms, " +
                "generation ${result.generationDurationMillis} ms, " +
                "pic PSS ${result.peakPssKb?.div(1024)} Mo",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
