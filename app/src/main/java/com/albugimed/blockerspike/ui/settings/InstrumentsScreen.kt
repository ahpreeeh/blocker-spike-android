package com.albugimed.blockerspike.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.albugimed.blockerspike.BuildConfig
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.inference.ModelLocator
import com.albugimed.blockerspike.log.InterceptionLog
import com.albugimed.blockerspike.policy.PolicyState
import com.albugimed.blockerspike.ui.Fact
import com.albugimed.blockerspike.ui.Notice
import com.albugimed.blockerspike.ui.NoticeTone
import com.albugimed.blockerspike.ui.ScreenHeader
import com.albugimed.blockerspike.ui.Section
import com.albugimed.blockerspike.ui.ToggleRow
import com.albugimed.blockerspike.ui.mutedColor
import kotlinx.coroutines.launch

/**
 * Les instruments de mesure du prototype.
 *
 * Ils etaient melanges aux reglages et donnaient a l'application son air de
 * banc d'essai. Ils restent accessibles — ils servent encore — mais derriere
 * une porte, et l'ecran dit ce qu'ils sont : de l'observation, pas de
 * l'usage quotidien.
 */
@Composable
fun InstrumentsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = Graph.policyRepository
    val policy by repo.policy.collectAsStateWithLifecycle(initialValue = PolicyState())
    val logEntries by InterceptionLog.entries.collectAsStateWithLifecycle()
    val metrics = InterceptionLog.metrics()
    val scope = rememberCoroutineScope()

    var localModelPath by remember {
        mutableStateOf(ModelLocator.findModel(context).getOrNull()?.absolutePath)
    }
    LifecycleResumeEffect(Unit) {
        localModelPath = ModelLocator.findModel(context).getOrNull()?.absolutePath
        onPauseOrDispose { }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text("← Réglages") }
        }
        item {
            ScreenHeader(
                title = "Instruments",
                subtitle = "Ce que le prototype mesure sur lui-même.",
            )
        }

        item {
            Section(title = "Mesures S1", kicker = "Interceptions") {
                Fact("Interceptions", metrics.interceptionCount.takeIf { it > 0 }?.toString())
                Fact(
                    "Retour accueil réussi",
                    metrics.interceptionCount
                        .takeIf { it > 0 }
                        ?.let { "${metrics.successfulHomeActions} / $it" },
                )
                Fact("Latence médiane", metrics.medianLatencyMillis?.let { "$it ms" })
            }
        }

        item {
            val lastEvent = logEntries.firstOrNull { it.tag == InterceptionLog.TAG_EVENT }
            val lastIntercept = logEntries.firstOrNull { it.tag == InterceptionLog.TAG_INTERCEPT }
            Section(title = "Derniers signaux", kicker = "Temps réel") {
                Fact(
                    "Dernier événement",
                    lastEvent?.let { "${formatTime(it.atMillis)} — ${it.message}" },
                )
                Fact(
                    "Dernière interception",
                    lastIntercept?.let { "${formatTime(it.atMillis)} — ${it.message}" },
                )
            }
        }

        if (!BuildConfig.PERMANENT_IDENTITY) {
            item {
                Section(title = "Variante d'interruption", kicker = "T2") {
                    ToggleRow(
                        title = if (policy.variantB) {
                            "T2-B — écran de blocage immédiat"
                        } else {
                            "T2-A — retour accueil + notification"
                        },
                        subtitle = "T2-B exige l'affichage au-dessus des apps ; " +
                            "repli automatique sur la notification sinon.",
                        checked = policy.variantB,
                        onCheckedChange = { scope.launch { repo.setVariantB(it) } },
                    )
                }
            }
        }

        item {
            Section(title = "IA locale", kicker = "T3") {
                if (localModelPath != null) {
                    Fact("Modèle détecté", localModelPath?.substringAfterLast('/'))
                } else {
                    Notice(
                        "Modèle .litertlm absent : toute demande d'exception sera " +
                            "refusée proprement.",
                        tone = NoticeTone.PROBLEM,
                    )
                    Text(
                        "Fichier attendu : ${ModelLocator.adbModelFile(context).path}",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedColor,
                    )
                }
                Text(
                    "Inférence LiteRT-LM isolée dans son propre processus ; " +
                        "validation déterministe avant toute autorisation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                )
            }
        }

        item {
            Section(
                title = "Journal",
                kicker = "50 dernières lignes",
                trailing = {
                    TextButton(onClick = InterceptionLog::clear) { Text("Effacer") }
                },
            ) {
                if (logEntries.isEmpty()) {
                    Text(
                        "Rien à afficher.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = mutedColor,
                    )
                } else {
                    logEntries.take(50).forEach { entry ->
                        Text(
                            "${formatTime(entry.atMillis)}  [${entry.tag}] ${entry.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedColor,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
