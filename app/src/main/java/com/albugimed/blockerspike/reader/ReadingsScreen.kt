package com.albugimed.blockerspike.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.ui.AppIcon
import com.albugimed.blockerspike.ui.Chevron
import com.albugimed.blockerspike.ui.EmptyState
import com.albugimed.blockerspike.ui.Glyph
import com.albugimed.blockerspike.ui.Kicker
import com.albugimed.blockerspike.ui.Notice
import com.albugimed.blockerspike.ui.NoticeTone
import com.albugimed.blockerspike.ui.PrimaryAction
import com.albugimed.blockerspike.ui.ScreenHeader
import com.albugimed.blockerspike.ui.SecondaryAction
import com.albugimed.blockerspike.ui.mutedColor
import com.albugimed.blockerspike.ui.theme.LocalAlbugimedExtras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * La bibliotheque — V2.3.
 *
 * Le lecteur existait sans porte d'entree : on ne pouvait l'ouvrir que depuis
 * une etape de la file **qui avait deja un document rattache**. Une file vide
 * le rendait introuvable, au point de paraitre absent de l'application. Cet
 * ecran est cette porte, et il ajoute ce qu'une porte suppose : on entre avec
 * ses propres livres, et on les range.
 *
 * Trois choses s'y font, et rien d'autre :
 *
 * 1. **Ajouter un livre** — le PDF se choisit dans les fichiers du telephone,
 *    le titre arrive pre-rempli depuis le nom du fichier et reste modifiable.
 * 2. **Ranger** — des dossiers imbriques sans profondeur maximale. On n'en
 *    voit qu'un niveau a la fois ; c'est cela qui garde l'ecran leger, pas une
 *    limite arbitraire.
 * 3. **Voir ou l'on en est** — le pourcentage sur les dossiers et les livres,
 *    la page sur le fichier qu'on lit.
 *
 * Aucune declaration n'est proposee ici : on vient pour lire, pas pour
 * comptabiliser. Le lecteur ouvert depuis cette page n'affiche donc pas le
 * bouton « Declarer », qui n'aurait aucune etape a viser.
 *
 * Rien de ce qui est range ici ne part sur le reseau — invariant de
 * l'amendement V2.2, inchange.
 */
@Composable
fun ReadingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val positions by Graph.readingPositions.positions
        .collectAsStateWithLifecycle(initialValue = emptyMap<String, ReadingPosition>())
    val library by Graph.library.library
        .collectAsStateWithLifecycle(initialValue = Library())

    var folderId by rememberSaveable { mutableStateOf<String?>(null) }
    var openedBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingFolder by rememberSaveable { mutableStateOf(false) }
    var pickIntent by remember { mutableStateOf<PickIntent?>(null) }
    var pendingAdd by remember { mutableStateOf<List<PickedPdf>?>(null) }
    var menuTarget by remember { mutableStateOf<LibraryTarget?>(null) }
    var renaming by remember { mutableStateOf<LibraryTarget?>(null) }
    var moving by remember { mutableStateOf<LibraryTarget?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    // Les documents rattaches depuis une etape de « A faire » n'ont pas de
    // titre ni de dossier : sans cette adoption ils resteraient invisibles dans
    // une bibliotheque qui pretend montrer les lectures.
    LaunchedEffect(positions) {
        if (positions.isNotEmpty()) Graph.library.adoptOrphans(positions)
    }

    // Un dossier disparu ne laisse pas l'ecran dans le vide : on retombe a la
    // racine par le calcul, sans ecrire dans l'etat pendant la composition.
    val current = library.folder(folderId)
    val here = current?.id
    val path = library.path(here)
    val subFolders = library.childFolders(here)
    val books = library.booksIn(here)
    val progression = folderProgress(library, here, positions)

    BackHandler(enabled = here != null) { folderId = current?.parentId }

    /**
     * `OpenMultipleDocuments` et non `OpenDocument` : un college scanne arrive
     * en tomes, et les choisir un par un obligerait a rouvrir le selecteur
     * autant de fois. Le contrat reste le meme — seul ce mode donne une
     * permission qu'on peut rendre persistante, sans quoi « Reprendre » ne
     * tiendrait pas au prochain demarrage.
     */
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        val intent = pickIntent
        pickIntent = null
        if (uris.isEmpty() || intent == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            problem = null
            val choisis = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    inspect(context, uri)
                }
            }
            busy = false
            if (choisis.isEmpty()) {
                problem = "Aucun de ces fichiers ne s'ouvre comme un PDF."
                return@launch
            }
            when (intent) {
                PickIntent.NewBook -> pendingAdd = choisis
                is PickIntent.AddTo ->
                    choisis.forEach { Graph.library.addFile(intent.bookId, attachFile(it)) }
            }
        }
    }

    fun pick(intent: PickIntent) {
        pickIntent = intent
        picker.launch(arrayOf("application/pdf"))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Breadcrumb(
                        path = path,
                        onRoot = { folderId = null },
                        onPick = { folderId = it },
                    )
                    ScreenHeader(
                        title = current?.name ?: "Lectures",
                        subtitle = shelfSubtitle(progression, library.books.size, here != null),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryAction(
                            text = "Ajouter un livre",
                            onClick = { pick(PickIntent.NewBook) },
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryAction(
                            text = "Nouveau dossier",
                            onClick = { creatingFolder = true },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    problem?.let { Notice(it, tone = NoticeTone.PROBLEM) }
                }
            }

            if (subFolders.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Kicker("Dossiers") }
                items(
                    subFolders,
                    key = { "folder_${it.id}" },
                    span = { GridItemSpan(maxLineSpan) },
                ) { dossier ->
                    FolderRow(
                        folder = dossier,
                        count = bookCount(library, dossier.id),
                        progress = folderProgress(library, dossier.id, positions),
                        onOpen = { folderId = dossier.id },
                        onMenu = { menuTarget = LibraryTarget.Folder(dossier) },
                    )
                }
            }

            if (books.isNotEmpty()) {
                if (subFolders.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Kicker("Livres") }
                }
                items(books, key = { "book_${it.id}" }) { livre ->
                    BookTile(
                        book = livre,
                        positions = positions,
                        onOpen = {
                            val seul = livre.files.singleOrNull()
                            if (seul != null) {
                                openReader(context, livre, seul)
                            } else {
                                openedBookId = livre.id
                            }
                        },
                        onMenu = { menuTarget = LibraryTarget.Book(livre) },
                    )
                }
            }

            if (subFolders.isEmpty() && books.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        if (here == null) {
                            "Rien ici pour l'instant. « Ajouter un livre » ouvre tes fichiers " +
                                "et range le PDF ; « Nouveau dossier » crée un rangement."
                        } else {
                            "Ce dossier est vide. Ajoute-lui un livre, ou déplace-en un " +
                                "depuis son menu « ⋯ »."
                        },
                    )
                }
            }
        }

        // Ouvrir un PDF de trois cents pages prend une seconde ou deux. Sans ce
        // voile, le selecteur se referme sur un ecran qui ne bouge pas et l'on
        // recommence, ce qui rattache deux fois le meme fichier.
        if (busy) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x6B05120B)),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }
    }

    if (creatingFolder) {
        NameDialog(
            title = "Nouveau dossier",
            initial = "",
            confirmLabel = "Créer",
            supporting = "Il sera créé dans « ${current?.name ?: "Lectures"} ».",
            onConfirm = { nom ->
                scope.launch { Graph.library.createFolder(nom, here) }
                creatingFolder = false
            },
            onDismiss = { creatingFolder = false },
        )
    }

    pendingAdd?.let { choisis ->
        AddBookDialog(
            files = choisis,
            destination = current?.name ?: "Lectures",
            onConfirm = { titre ->
                scope.launch {
                    val fichiers = choisis.map { attachFile(it) }
                    Graph.library.createBook(titre, here, fichiers)
                }
                pendingAdd = null
            },
            onDismiss = { pendingAdd = null },
        )
    }

    menuTarget?.let { cible ->
        val ouvrirFichiers: (() -> Unit)? = if (cible is LibraryTarget.Book) {
            {
                openedBookId = cible.book.id
                menuTarget = null
            }
        } else {
            null
        }
        TargetMenuDialog(
            target = cible,
            onRename = {
                renaming = cible
                menuTarget = null
            },
            onMove = {
                moving = cible
                menuTarget = null
            },
            onDelete = {
                scope.launch {
                    when (cible) {
                        is LibraryTarget.Folder -> Graph.library.deleteFolder(cible.folder.id)
                        is LibraryTarget.Book ->
                            Graph.library.deleteBook(cible.book.id, Graph.readingPositions)
                    }
                }
                menuTarget = null
            },
            onFiles = ouvrirFichiers,
            onDismiss = { menuTarget = null },
        )
    }

    renaming?.let { cible ->
        NameDialog(
            title = if (cible is LibraryTarget.Folder) "Renommer le dossier" else "Renommer le livre",
            initial = cible.label,
            confirmLabel = "Renommer",
            onConfirm = { nom ->
                scope.launch {
                    when (cible) {
                        is LibraryTarget.Folder -> Graph.library.renameFolder(cible.folder.id, nom)
                        is LibraryTarget.Book -> Graph.library.renameBook(cible.book.id, nom)
                    }
                }
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    moving?.let { cible ->
        MoveDialog(
            library = library,
            target = cible,
            onPick = { destination ->
                scope.launch {
                    when (cible) {
                        is LibraryTarget.Folder ->
                            Graph.library.moveFolder(cible.folder.id, destination)

                        is LibraryTarget.Book -> Graph.library.moveBook(cible.book.id, destination)
                    }
                }
                moving = null
            },
            onDismiss = { moving = null },
        )
    }

    // Relu dans la bibliotheque a chaque image plutot que garde en etat : un
    // fichier ajoute ou retire doit apparaitre dans la liste ouverte, sans la
    // refermer.
    openedBookId?.let { id -> library.books.firstOrNull { it.id == id } }?.let { livre ->
        BookSheet(
            book = livre,
            positions = positions,
            onOpenFile = { fichier -> openReader(context, livre, fichier) },
            onAddFile = { pick(PickIntent.AddTo(livre.id)) },
            onRenameFile = { fichier, nom ->
                scope.launch { Graph.library.renameFile(livre.id, fichier.resourceId, nom) }
            },
            onRemoveFile = { fichier ->
                scope.launch {
                    Graph.library.removeFile(
                        livre.id,
                        fichier.resourceId,
                        Graph.readingPositions,
                    )
                }
            },
            onDismiss = { openedBookId = null },
        )
    }
}

/** Un PDF choisi, deja ouvert une fois pour savoir combien il compte de pages. */
data class PickedPdf(val uri: String, val name: String, val pageCount: Int)

/** Pourquoi le selecteur a ete ouvert : le resultat ne se range pas au meme endroit. */
private sealed interface PickIntent {
    data object NewBook : PickIntent

    data class AddTo(val bookId: String) : PickIntent
}

/**
 * Ouvre le document une fois, le temps d'en lire le nom et le nombre de pages.
 *
 * Sans ce compte, la progression d'un livre serait inconnue jusqu'a sa
 * premiere ouverture, et un livre ajoute afficherait « — » la ou l'on attend
 * « 0 % ». Le document est referme aussitot : c'est un descripteur de fichier,
 * pas un cache.
 */
private fun inspect(context: Context, uri: Uri): PickedPdf? =
    when (val ouvert = PdfDocument.open(context, uri)) {
        is PdfOpenResult.Opened -> ouvert.document.use { document ->
            PickedPdf(uri = uri.toString(), name = document.label, pageCount = document.pageCount)
        }

        else -> null
    }

/**
 * Donne au fichier sa cle de position et l'enregistre.
 *
 * Le prefixe `lib_` distingue un fichier de la bibliotheque d'une ressource du
 * referentiel : les deux vivent dans le meme magasin de positions, et rien
 * d'autre ne les separe.
 */
private suspend fun attachFile(picked: PickedPdf): LibraryBookFile {
    val resourceId = "lib_${UUID.randomUUID()}"
    Graph.readingPositions.attach(
        resourceId = resourceId,
        documentUri = picked.uri,
        documentLabel = picked.name,
        pageCount = picked.pageCount,
        nowMillis = System.currentTimeMillis(),
    )
    return LibraryBookFile(resourceId = resourceId)
}

/**
 * Ouvre le lecteur sans etape visee : `stepId = null` masque « Declarer ».
 *
 * Le titre du livre passe devant le nom du fichier — le lecteur affiche deja
 * celui-ci en sous-titre. Un livre en tomes ajoute le nom du tome, sans quoi
 * trois onglets identiques s'ouvriraient sous le meme intitule.
 */
private fun openReader(context: Context, book: LibraryBook, file: LibraryBookFile) {
    val titre = if (book.files.size > 1 && file.label.isNotBlank()) {
        "${book.title} — ${file.label}"
    } else {
        book.title
    }
    context.startActivity(
        ReaderActivity.intent(
            context = context,
            resourceId = file.resourceId,
            resourceLabel = titre,
            stepId = null,
        ),
    )
}

/** « 42 % lus sur 12 livres », ou la phrase d'accueil quand il n'y a rien. */
private fun shelfSubtitle(progress: ReadingProgress, totalBooks: Int, inFolder: Boolean): String? {
    val part = progress.percent
    return when {
        part != null -> "$part % lus"
        totalBooks > 0 -> "Aucune page lue pour l'instant"
        inFolder -> null
        else -> "Tes livres, rangés comme tu veux."
    }
}

/**
 * Le fil d'Ariane. Absent a la racine : « Lectures › » tout seul n'indique rien
 * que le titre ne dise deja.
 */
@Composable
private fun Breadcrumb(
    path: List<LibraryFolder>,
    onRoot: () -> Unit,
    onPick: (String) -> Unit,
) {
    if (path.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crumb(text = "Lectures", onClick = onRoot)
        path.forEachIndexed { index, dossier ->
            Chevron()
            // Le dernier segment est l'endroit ou l'on est : cliquable, il
            // rechargerait la meme page en donnant l'impression d'un bouton
            // casse.
            Crumb(
                text = dossier.name,
                onClick = if (index < path.lastIndex) {
                    { onPick(dossier.id) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun Crumb(text: String, onClick: (() -> Unit)?) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (onClick == null) MaterialTheme.colorScheme.onBackground else mutedColor,
        maxLines = 1,
        modifier = Modifier
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .padding(vertical = 6.dp, horizontal = 2.dp),
    )
}

/**
 * Un dossier, sur toute la largeur.
 *
 * Une ligne et non une tuile carree : un dossier porte un nom qui peut etre
 * long, un nombre de livres et un pourcentage, et les trois tiennent sur une
 * ligne alors qu'ils se chevauchent dans une vignette.
 */
@Composable
private fun FolderRow(
    folder: LibraryFolder,
    count: Int,
    progress: ReadingProgress,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                MaterialTheme.shapes.large,
            )
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Glyph(AppIcon.FOLDER, size = 22.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                folder.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(if (count <= 1) "$count livre" else "$count livres")
                    progress.percent?.let { append(" — $it % lus") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = mutedColor,
            )
            if (count > 0) ProgressBar(progress.fraction())
        }
        MenuDot(onClick = onMenu)
    }
}

/**
 * Un livre : sa couverture, son titre, ce qu'il en reste.
 *
 * Le grand aplat porte le nom de l'application en tout petit, comme la
 * conception : c'est ce qui fait lire le rectangle comme la couverture d'un
 * document plutot que comme un bloc de couleur. La couleur vient du titre —
 * elle identifie, elle ne classe rien (cadrage §1.1) ; le classement, lui, est
 * maintenant explicite et vit dans les dossiers.
 *
 * La pastille du coin nomme la page **du fichier en cours**, jamais celle du
 * livre entier : trois tomes n'ont qu'une page ouverte a la fois.
 */
@Composable
private fun BookTile(
    book: LibraryBook,
    positions: Map<String, ReadingPosition>,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
) {
    val label = book.title.ifBlank { "Document sans nom" }
    val progress = remember(book, positions) { bookProgress(book, positions) }
    val courant = remember(book, positions) { currentFileOf(book, positions) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(156.dp)
                .background(
                    LocalAlbugimedExtras.current.coverHue(label),
                    RoundedCornerShape(18.dp),
                )
                .padding(14.dp),
        ) {
            Text(
                "ALBUGIMED",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.align(Alignment.TopStart),
            )
            Glyph(
                AppIcon.BOOK,
                size = 30.dp,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.Center),
            )
            courant?.let { position ->
                Text(
                    pageChip(position),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
            MenuDot(
                onClick = onMenu,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }

        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        ProgressBar(progress.fraction())

        Text(
            buildString {
                append(progress.percent?.let { "$it % lus" } ?: "Pas encore ouvert")
                if (book.files.size > 1) append(" · ${book.files.size} fichiers")
                if (book.files.isEmpty()) append(" · aucun fichier")
            },
            style = MaterialTheme.typography.labelSmall,
            color = mutedColor,
        )
    }
}

/** « p. 67 / 320 » — la page, sur le fichier qu'on lit. */
private fun pageChip(position: ReadingPosition): String =
    if (position.pageCount > 0) {
        "p. ${position.page} / ${position.pageCount}"
    } else {
        "p. ${position.page}"
    }

/** Les trois points, avec la surface de touche qu'un doigt demande. */
@Composable
private fun MenuDot(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = mutedColor,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(AppIcon.MORE, size = 18.dp, tint = tint)
    }
}
