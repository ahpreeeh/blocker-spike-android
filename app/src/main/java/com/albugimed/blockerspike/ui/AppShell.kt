package com.albugimed.blockerspike.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.albugimed.blockerspike.BuildConfig
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.capture.CaptureSheet
import com.albugimed.blockerspike.capture.NotesScreen
import com.albugimed.blockerspike.home.TodayScreen
import com.albugimed.blockerspike.protection.ProtectionScreen
import com.albugimed.blockerspike.reader.ReaderActivity
import com.albugimed.blockerspike.reader.ReadingPosition
import com.albugimed.blockerspike.reader.ReadingsScreen
import com.albugimed.blockerspike.study.AgendaScreen
import com.albugimed.blockerspike.study.DeclareActivity
import com.albugimed.blockerspike.study.StudyDependencies
import com.albugimed.blockerspike.study.StudyQueueScreen
import com.albugimed.blockerspike.sync.QueueItem
import com.albugimed.blockerspike.ui.settings.InstrumentsScreen
import com.albugimed.blockerspike.ui.settings.SettingsScreen
import com.albugimed.blockerspike.ui.theme.ActionShape
import com.albugimed.blockerspike.ui.theme.Citron
import com.albugimed.blockerspike.ui.theme.Protection
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * Les endroits ou l'application peut se trouver.
 *
 * Pas de bibliotheque de navigation : le contrat §10 interdit d'ajouter une
 * dependance, et huit destinations sans historique profond n'en demandent pas.
 * Un `enum` et un `when` font exactement le travail, et la destination survit
 * a la rotation par `rememberSaveable`.
 *
 * **Le tiroir remplace la barre du bas**, et ce n'est pas un gout : la barre
 * tenait quatre onglets, l'application a sept pages. Les trois qui ne
 * rentraient pas etaient rangees derriere « Plus », c'est-a-dire derriere un
 * mot qui ne dit rien — pour atteindre ses lectures il fallait deviner
 * qu'elles etaient dans « Plus ». Le tiroir les montre toutes, d'un coup, avec
 * leur nom et leur icone, et rend le hub inutile.
 *
 * `INSTRUMENTS` n'y figure pas : c'est une page de diagnostic, on l'ouvre
 * depuis les reglages quand quelque chose ne va pas, jamais dans le cours
 * normal d'une journee.
 */
enum class AppDestination(
    val label: String,
    val icon: AppIcon,
    val inDrawer: Boolean = true,
) {
    TODAY("Aujourd'hui", AppIcon.GRID),
    QUEUE("À faire", AppIcon.CHECKLIST),
    AGENDA("Agenda", AppIcon.CALENDAR),
    READINGS("Lectures", AppIcon.BOOK),
    NOTES("Notes", AppIcon.FILE),
    BLOCKING("Blocage", AppIcon.SHIELD),
    MORE("Réglages", AppIcon.SLIDERS),
    INSTRUMENTS("Instruments", AppIcon.MORE, inDrawer = false),
}

@Composable
fun AppShell() {
    val context = LocalContext.current
    val repository = remember { StudyDependencies.repository(context.applicationContext) }
    var destination by rememberSaveable { mutableStateOf(AppDestination.TODAY) }
    // Capturer n'est pas un lieu ou l'on va : c'est un geste qui se pose
    // par-dessus l'endroit ou l'on etait, et qui rend la main au meme endroit.
    var capturing by rememberSaveable { mutableStateOf(false) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val positions by Graph.readingPositions.positions
        .collectAsStateWithLifecycle(initialValue = emptyMap<String, ReadingPosition>())

    // Le tiroir pose deja son propre retour arriere quand il est ouvert. Sans
    // cette garde, les deux se declencheraient au meme geste et l'on
    // changerait de page en fermant le menu.
    BackHandler(enabled = drawerState.isClosed && destination != AppDestination.TODAY) {
        destination = AppDestination.TODAY
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Le voile de la conception : presque noir, mais verdi, et assez dense
        // pour que le texte dessous cesse de se lire — un voile qu'on lit a
        // travers invite a viser ce qu'il y a derriere.
        scrimColor = Color(0x6B05120B),
        drawerContent = {
            AppDrawer(
                current = destination,
                onSelect = { target ->
                    destination = target
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                AppTopBar(
                    destination = destination,
                    onMenu = { scope.launch { drawerState.open() } },
                    onCapture = { capturing = true },
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                // L'entree de la conception : le contenu monte de huit points en
                // apparaissant. Ce n'est pas une decoration — c'est ce qui dit
                // que la page a change alors que l'en-tete, lui, n'a pas bouge.
                AnimatedContent(
                    targetState = destination,
                    transitionSpec = {
                        (
                            fadeIn(tween(350)) +
                                slideInVertically(tween(350)) { height -> height / 14 }
                            ) togetherWith fadeOut(tween(120))
                    },
                    label = "destination",
                ) { target ->
                    when (target) {
                        AppDestination.TODAY -> TodayScreen(
                            repository = repository,
                            positions = positions,
                            onOpenQueue = { destination = AppDestination.QUEUE },
                            onOpenAgenda = { destination = AppDestination.AGENDA },
                            onOpenBlocking = { destination = AppDestination.BLOCKING },
                            onOpenReadings = { destination = AppDestination.READINGS },
                            onDeclare = { item -> openDeclaration(context, item) },
                            onResume = { item -> openReader(context, item) },
                        )

                        AppDestination.QUEUE -> StudyQueueScreen(
                            repository = repository,
                            onDeclare = { item -> openDeclaration(context, item) },
                            onDeclareFree = { openFreeDeclaration(context) },
                            positions = positions,
                            onResume = { item -> openReader(context, item) },
                        )

                        AppDestination.AGENDA -> AgendaScreen(repository = repository)

                        AppDestination.MORE -> SettingsScreen(
                            onOpen = { destination = it },
                        )

                        AppDestination.BLOCKING -> ProtectionScreen()

                        AppDestination.NOTES -> NotesScreen(onCapture = { capturing = true })

                        AppDestination.READINGS -> ReadingsScreen()

                        AppDestination.INSTRUMENTS -> InstrumentsScreen(
                            onBack = { destination = AppDestination.MORE },
                        )
                    }
                }

                // Hors de l'animation : la feuille appartient a la coque, pas a
                // l'ecran en dessous. Elle ne doit ni disparaitre ni se rejouer
                // quand la destination change sous elle.
                if (capturing) {
                    CaptureSheet(onDismiss = { capturing = false })
                }
            }
        }
    }
}

/**
 * L'en-tete : ouvrir le menu, savoir ou l'on est, noter tout de suite.
 *
 * La conception y mettait aussi une loupe et un avatar. Ni l'une ni l'autre
 * n'existent ici — il n'y a pas de recherche, et il n'y a qu'une personne.
 * Un bouton qui ne fait rien coute plus cher qu'un espace vide.
 *
 * A leur place, le geste qui manquait vraiment : **noter**, joignable depuis
 * les sept pages au lieu de la seule page d'accueil.
 */
@Composable
private fun AppTopBar(
    destination: AppDestination,
    onMenu: () -> Unit,
    onCapture: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconTap(icon = AppIcon.MENU, contentDescription = "Ouvrir le menu", onClick = onMenu)
            Text(
                destination.label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = mutedColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary, ActionShape)
                    .clickable(onClick = onCapture)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Glyph(
                    AppIcon.PLUS,
                    size = 16.dp,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
                Text(
                    "Noter",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/** Une icone seule, avec la surface de touche de 44 dp qu'un doigt demande. */
@Composable
private fun IconTap(
    icon: AppIcon,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onBackground,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(icon, size = 22.dp, tint = tint)
    }
}

/**
 * Le tiroir : la carte complete de l'application, en vert profond.
 *
 * Le vert est plus sombre que tout le reste de l'interface, y compris que la
 * carte de blocage. C'est deliberé : le tiroir passe **par-dessus** la page,
 * et une surface qui recouvre doit se lire comme un autre plan, pas comme un
 * morceau de la page qui aurait glisse.
 */
@Composable
private fun AppDrawer(
    current: AppDestination,
    onSelect: (AppDestination) -> Unit,
) {
    ModalDrawerSheet(
        drawerContainerColor = Protection.Drawer,
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
        modifier = Modifier.width(286.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 18.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Citron.Vif, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "A",
                        style = MaterialTheme.typography.titleMedium,
                        color = Protection.Drawer,
                    )
                }
                Text(
                    "Albugimed",
                    style = MaterialTheme.typography.titleMedium,
                    color = Protection.OnActiveTitle,
                )
            }

            Spacer(Modifier.height(18.dp))

            AppDestination.entries.filter { it.inDrawer }.forEach { entry ->
                DrawerItem(
                    entry = entry,
                    selected = entry == current,
                    onClick = { onSelect(entry) },
                )
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(Citron.Vif, CircleShape),
                )
                // Un fait verifiable, et rien d'autre. La conception affichait
                // ici « Tout est protege » : le tiroir n'a aucun moyen de le
                // savoir, et une affirmation fausse a cet endroit-la serait la
                // pire de l'application.
                Text(
                    "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Protection.OnActiveKicker,
                )
            }
        }
    }
}

@Composable
private fun DrawerItem(
    entry: AppDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val tint = if (selected) Citron.Vif else Protection.OnActiveBody
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(
                if (selected) Protection.DrawerActive else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Glyph(entry.icon, size = 20.dp, tint = tint)
        Text(
            entry.label,
            style = MaterialTheme.typography.titleSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Les trois ecrans qui restent des activites a part entiere.
 *
 * Ils ne sont pas des destinations et ne doivent pas le devenir : declarer et
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
