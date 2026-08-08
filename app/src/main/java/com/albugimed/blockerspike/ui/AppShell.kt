package com.albugimed.blockerspike.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.capture.CaptureScreen
import com.albugimed.blockerspike.home.TodayScreen
import com.albugimed.blockerspike.protection.ProtectionScreen
import com.albugimed.blockerspike.reader.ReaderActivity
import com.albugimed.blockerspike.reader.ReadingPosition
import com.albugimed.blockerspike.study.AgendaScreen
import com.albugimed.blockerspike.study.DeclareActivity
import com.albugimed.blockerspike.study.StudyDependencies
import com.albugimed.blockerspike.study.StudyQueueScreen
import com.albugimed.blockerspike.sync.QueueItem
import com.albugimed.blockerspike.ui.settings.InstrumentsScreen
import com.albugimed.blockerspike.ui.settings.SettingsScreen
import com.albugimed.blockerspike.ui.theme.LocalAlbugimedExtras
import androidx.compose.ui.platform.LocalContext

/**
 * Les endroits ou l'application peut se trouver.
 *
 * Pas de bibliotheque de navigation : le contrat §10 interdit d'ajouter une
 * dependance, et six destinations sans historique profond n'en demandent pas.
 * Un `enum` et un `when` font exactement le travail, et l'onglet survit a la
 * rotation par `rememberSaveable`.
 *
 * **Quatre onglets, six destinations.** L'agenda et la capture ne sont pas des
 * lieux ou l'on va, ce sont des choses que l'on fait : on y arrive depuis
 * l'accueil — « Noter vite » dans l'en-tete pour la capture, la carte des
 * echeances pour l'agenda — et le retour systeme ramene a l'accueil. Leur
 * donner un onglet
 * revenait a payer un cinquieme de la barre pour un ecran ouvert deux fois par
 * semaine, pendant que **Protection**, la seule fonction dont l'effet se
 * manifeste en dehors de l'application, dormait au fond des reglages.
 */
enum class AppDestination(val label: String, val inBar: Boolean) {
    TODAY("Aujourd'hui", true),
    PROTECTION("Protection", true),
    WORK("Travail", true),
    MORE("Plus", true),
    AGENDA("Agenda", false),
    CAPTURE("Capture", false),
}

@Composable
fun AppShell() {
    val context = LocalContext.current
    val repository = remember { StudyDependencies.repository(context.applicationContext) }
    var destination by rememberSaveable { mutableStateOf(AppDestination.TODAY) }
    var showInstruments by rememberSaveable { mutableStateOf(false) }

    val positions by Graph.readingPositions.positions
        .collectAsStateWithLifecycle(initialValue = emptyMap<String, ReadingPosition>())

    // Une destination hors barre n'a pas d'onglet ou revenir : le retour
    // systeme est le seul chemin naturel, et il doit exister.
    BackHandler(enabled = !destination.inBar) {
        destination = AppDestination.TODAY
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            BottomBar(
                current = destination,
                onSelect = {
                    if (it == AppDestination.MORE && destination == AppDestination.MORE) {
                        showInstruments = false
                    }
                    destination = it
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Crossfade(targetState = destination, label = "destination") { target ->
                when (target) {
                    AppDestination.TODAY -> TodayScreen(
                        repository = repository,
                        positions = positions,
                        onOpenQueue = { destination = AppDestination.WORK },
                        onOpenAgenda = { destination = AppDestination.AGENDA },
                        onOpenProtection = { destination = AppDestination.PROTECTION },
                        onOpenCapture = { destination = AppDestination.CAPTURE },
                        onDeclare = { item -> openDeclaration(context, item) },
                        onResume = { item -> openReader(context, item) },
                    )

                    AppDestination.PROTECTION -> ProtectionScreen()

                    AppDestination.WORK -> StudyQueueScreen(
                        repository = repository,
                        onDeclare = { item -> openDeclaration(context, item) },
                        onDeclareFree = { openFreeDeclaration(context) },
                        positions = positions,
                        onResume = { item -> openReader(context, item) },
                    )

                    AppDestination.AGENDA -> AgendaScreen(repository = repository)

                    AppDestination.CAPTURE -> CaptureScreen()

                    AppDestination.MORE -> if (showInstruments) {
                        InstrumentsScreen(onBack = { showInstruments = false })
                    } else {
                        SettingsScreen(onOpenInstruments = { showInstruments = true })
                    }
                }
            }
        }
    }
}

/**
 * La barre du bas, faite a la main.
 *
 * Pas d'icones : aucune du jeu de base ne dit « travail d'etude » sans mentir,
 * et un pictogramme approximatif se lit moins vite qu'un mot juste. Le repere
 * de l'onglet actif est une **pastille pleine derriere le mot** — l'ancien
 * tiret de 3 dp au-dessus du libelle se lisait comme une poussiere sur
 * l'ecran.
 */
@Composable
private fun BottomBar(
    current: AppDestination,
    onSelect: (AppDestination) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(19.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppDestination.entries.filter { it.inBar }.forEach { entry ->
                        BottomBarItem(
                            entry = entry,
                            selected = entry == current,
                            onClick = { onSelect(entry) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBarItem(
    entry: AppDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(52.dp)
            .padding(horizontal = 3.dp)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
                RoundedCornerShape(13.dp),
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            entry.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                LocalAlbugimedExtras.current.textMuted
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Les trois ecrans qui restent des activites a part entiere.
 *
 * Ils ne sont pas des onglets et ne doivent pas le devenir : declarer et
 * lire sont des taches qui s'ouvrent, se terminent et se referment. Les
 * fondre dans la coque rendrait le retour arriere ambigu.
 */
private fun openDeclaration(context: Context, item: QueueItem) {
    context.startActivity(DeclareActivity.intent(context, item.stepId))
}

private fun openFreeDeclaration(context: Context) {
    context.startActivity(DeclareActivity.freeIntent(context))
}

private fun openReader(context: Context, item: QueueItem) {
    val resource = item.resource ?: return
    context.startActivity(
        ReaderActivity.intent(
            context = context,
            resourceId = resource.resourceId,
            resourceLabel = resource.label,
            stepId = item.stepId,
        ),
    )
}
