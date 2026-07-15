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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.policy.PolicyState
import kotlinx.coroutines.launch

/**
 * Écran neutre affiché après interception (protocole §3) : explique quelle
 * application a été bloquée et, pour le spike, n'offre que des autorisations
 * manuelles de test. Le parcours de demande à l'IA viendra plus tard.
 */
class BlockGateActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val blockedPackage = intent.getStringExtra(EXTRA_PACKAGE) ?: "(package inconnu)"
        setContent {
            MaterialTheme {
                GateScreen(blockedPackage = blockedPackage, onDone = { finish() })
            }
        }
    }

    companion object {
        private const val EXTRA_PACKAGE = "blocked_package"

        fun intent(context: Context, blockedPackage: String): Intent =
            Intent(context, BlockGateActivity::class.java).putExtra(EXTRA_PACKAGE, blockedPackage)
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
