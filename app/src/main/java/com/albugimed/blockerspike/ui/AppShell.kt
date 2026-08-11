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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.albugimed.blockerspike.study.SubjectsScreen
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
 * dependance, et neuf destinations sans historique profond n'en demandent pas.
 * Un `enum` et un `when` font exactement le travail, et la destination survit
 * a la rotation par `rememberSaveable`.
 *
 * **Le tiroir remplace la barre du bas**, et ce n'est pas un gout : la barre
 * tenait quatre onglets, l'application en a huit a montrer. Celles qui ne
 * rentraient pas etaient rangees derriere « Plus », c'est-a-dire derriere un
 * mot qui ne dit rien — pour atteindre ses lectures il fallait deviner
 * qu'elles etaient dans « Plus ». Le tiroir les montre toutes, d'un coup, avec
 * leur nom et leur icone, et rend le hub inutile.
 *
 * Chaque destination declare la [NavFamily] a laquelle elle appartient. Une
 * destination sans famille ne figure pas dans le corps du tiroir : soit elle
 * le precede (`TODAY`), soit elle ne s'y ouvre pas du tout (`INSTRUMENTS`).
 */
enum class AppDestination(
    val label: String,
    val icon: AppIcon,
    val family: NavFamily? = null,
) {
    /** La porte : elle n'appartient a aucune famille et les precede. */
    TODAY("Aujourd'hui", AppIcon.GRID),

    // « Parcours » et non « A faire » : la liste ne se vide plus en avancant,
    // elle garde les etapes terminees grisees a leur place. « A faire » nommait
    // une corbeille qui se vide ; le mot ne decrivait plus la page.
    QUEUE("Parcours", AppIcon.CHECKLIST, NavFamily.ETUDES),
    SUBJECTS("Matières", AppIcon.FOLDER, NavFamily.ETUDES),
    AGENDA("Agenda", AppIcon.CALENDAR, NavFamily.ORGANISATION),
    NOTES("Notes", AppIcon.FILE, NavFamily.NOTES),
    READINGS("Lectures", AppIcon.BOOK, NavFamily.EXTRASCOLAIRE),
    BLOCKING("Blocage", AppIcon.SHIELD, NavFamily.REGLAGES),
    MORE("Appareil", AppIcon.SLIDERS, NavFamily.REGLAGES),

    /**
     * Page de diagnostic, sans famille : on l'ouvre depuis les reglages quand
     * quelque chose ne va pas, jamais dans le cours normal d'une journee.
     */
    INSTRUMENTS("Instruments", AppIcon.MORE),
}

/**
 * Les cinq regroupements du brief, section 5 — les memes que dans l'atelier
 * web, dans le meme ordre et sous les memes mots.
 *
 * **Les cinq sont la des maintenant, y compris les vides.** C'est la contrainte
 * posee par le brief : l'ossature doit tenir avec des sections partiellement
 * remplies « sans en faire une promesse trompeuse, et surtout sans avoir a
 * etre redessinee quand elles se rempliront ». Un tiroir a trois familles
 * aujourd'hui et cinq demain, ce serait exactement la refonte a eviter.
 *
 * Un groupe vide montre donc son titre et une ligne grise qui dit ce qui y
 * viendra : rien ne se clique, aucune date n'est promise, et la place est
 * prise.
 *
 * [ETUDES] s'est remplie la premiere, et c'est la demonstration que l'ossature
 * tenait : la famille etait deja la, il n'y a eu qu'a poser Matieres dedans.
 * Sa phrase d'attente a disparu avec elle — une famille qui a des entrees ne
 * doit pas continuer a annoncer ce qui viendra, elle est arrivee.
 */
enum class NavFamily(val title: String, val empty: String? = null) {
    ORGANISATION("Organisation"),
    ETUDES("Études"),
    NOTES("Notes"),
    EXTRASCOLAIRE("Extrascolaire"),
    REGLAGES("Réglages"),
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

                        AppDestination.SUBJECTS -> SubjectsScreen(repository = repository)

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
                LogoMark()
                Text(
                    "Albugimed",
                    style = MaterialTheme.typography.titleMedium,
                    color = Protection.OnActiveTitle,
                )
            }

            Spacer(Modifier.height(18.dp))

            /*
             * La carte defile.
             *
             * Sept destinations tenaient a plat ; les memes rangees en cinq
             * familles ajoutent cinq titres et leurs respirations, et sur un
             * ecran court la derniere famille — les reglages — passait sous le
             * bord. Un menu dont la fin est invisible ne montre plus tout, ce
             * qui etait justement la raison de remplacer la barre du bas.
             */
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                // La porte, avant les familles et detachee d'elles.
                DrawerItem(
                    entry = AppDestination.TODAY,
                    selected = current == AppDestination.TODAY,
                    onClick = { onSelect(AppDestination.TODAY) },
                )

                NavFamily.entries.forEach { family ->
                    val entries = AppDestination.entries.filter { it.family == family }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        family.title.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Protection.OnActiveOutline,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )

                    if (entries.isEmpty()) {
                        // Sans destination, la famille garde sa place et ne se
                        // clique pas. Pas de « a venir », pas de date : la
                        // phrase dit ou la chose se trouve, et l'ossature
                        // n'aura pas a bouger le jour ou l'ecran existera.
                        Text(
                            family.empty.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Protection.OnActiveOutline,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    } else {
                        entries.forEach { entry ->
                            DrawerItem(
                                entry = entry,
                                selected = entry == current,
                                onClick = { onSelect(entry) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
            }

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

/**
 * La marque : trois barres inegales, comme trois piles de travail.
 *
 * C'est le meme dessin que dans la colonne de l'atelier web et que dans
 * l'icone de lancement — trois rapports 8/14/10, alignes par le bas. Le carre
 * citron marque d'un « A » qu'elle remplace etait une lettre posee dans une
 * boite : lisible, mais sans rapport avec le reste de l'identite.
 *
 * Le citron ne sert qu'a la marque et a l'element ouvert. Il ne dit jamais un
 * etat d'avancement — sinon il classerait (cadrage §1.1).
 */
@Composable
private fun LogoMark(tint: Color = Citron.Vif) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(11, 19, 14).forEach { barHeight ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(barHeight.dp)
                    .background(tint, RoundedCornerShape(2.dp)),
            )
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
