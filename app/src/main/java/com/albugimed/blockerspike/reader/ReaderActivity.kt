package com.albugimed.blockerspike.reader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.study.DeclareActivity
import com.albugimed.blockerspike.ui.EmptyState
import com.albugimed.blockerspike.ui.Notice
import com.albugimed.blockerspike.ui.NoticeTone
import com.albugimed.blockerspike.ui.PrimaryAction
import com.albugimed.blockerspike.ui.ScreenHeader
import com.albugimed.blockerspike.ui.SecondaryAction
import com.albugimed.blockerspike.ui.mutedColor
import com.albugimed.blockerspike.ui.theme.AlbugimedTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Le lecteur interne — V2.2.
 *
 * Il existe pour une raison unique et vérifiable : **aucun lecteur externe
 * n'accepte d'ouvrir un document à une page donnée.** C'est la clause
 * d'exception du cadrage §3.2, et c'est le seul moyen de passer de
 * « Dernier travail : pages 47 → 62 » à « Reprendre ».
 *
 * Ce qu'il ne fait pas, et ne doit pas faire : envoyer la page au serveur.
 * La position vit ici. Le journal reste un enregistrement de déclarations.
 *
 * La lecture elle-même se conduit comme celle d'une visionneuse ordinaire —
 * défilement continu, pincement, saut de page. Ce n'est pas un raffinement :
 * un lecteur qui impose deux boutons « Précédente / Suivante » pour parcourir
 * trois cents pages ne sert pas de lecteur, et la ressource repart alors dans
 * une application tierce, où la page mémorisée se perd.
 */
class ReaderActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resourceId = intent.getStringExtra(EXTRA_RESOURCE_ID)
        val resourceLabel = intent.getStringExtra(EXTRA_RESOURCE_LABEL).orEmpty()
        val stepId = intent.getStringExtra(EXTRA_STEP_ID)

        if (resourceId == null) {
            finish()
            return
        }

        // La page doit pouvoir occuper toute la hauteur, barres système
        // comprises : sans bord à bord, masquer les barres laisserait deux
        // bandes vides à la place.
        enableEdgeToEdge()

        setContent {
            AlbugimedTheme {
                ReaderScreen(
                    resourceId = resourceId,
                    resourceLabel = resourceLabel,
                    positions = Graph.readingPositions,
                    onDeclare = { from, to ->
                        if (stepId != null) {
                            startActivity(
                                DeclareActivity.readerIntent(this, stepId, resourceId, from, to),
                            )
                        }
                    },
                    canDeclare = stepId != null,
                    onSystemBarsVisible = ::setSystemBarsVisible,
                    onClose = ::finish,
                )
            }
        }
    }

    /**
     * Le mode immersif, par `WindowInsetsControllerCompat`.
     *
     * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` est le point important : les
     * barres reviennent d'un balayage depuis le bord. Sans lui, un lecteur
     * plein écran est un endroit dont on ne sait plus sortir — exactement ce
     * que l'application ne doit jamais produire, elle qui bloque déjà des
     * applications par ailleurs.
     */
    private fun setSystemBarsVisible(visible: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        private const val EXTRA_RESOURCE_ID = "reader_resource_id"
        private const val EXTRA_RESOURCE_LABEL = "reader_resource_label"
        private const val EXTRA_STEP_ID = "reader_step_id"

        fun intent(
            context: Context,
            resourceId: String,
            resourceLabel: String,
            stepId: String?,
        ): Intent = Intent(context, ReaderActivity::class.java)
            .putExtra(EXTRA_RESOURCE_ID, resourceId)
            .putExtra(EXTRA_RESOURCE_LABEL, resourceLabel)
            .putExtra(EXTRA_STEP_ID, stepId)
    }
}

/** Rapport d'une page A4 portrait, le temps de connaître le vrai format. */
private const val DEFAULT_PAGE_RATIO = 0.707f

/** Au-delà, l'image rendue à la largeur de l'écran devient visiblement molle. */
private const val SHARP_ZOOM_THRESHOLD = 1.5f

/**
 * Plafond de la largeur de rendu en zoom. Une page plus large coûterait plus
 * que le cache entier sur un appareil modeste, et serait donc évincée aussitôt
 * rendue : chaque geste redessinerait tout.
 */
private const val MAX_SHARP_WIDTH_PX = 1800

/** Le temps d'immobilité au bout duquel la pastille de page s'efface. */
private const val BADGE_LINGER_MILLIS = 1_500L

@Composable
private fun ReaderScreen(
    resourceId: String,
    resourceLabel: String,
    positions: ReadingPositionRepository,
    onDeclare: (Int, Int) -> Unit,
    canDeclare: Boolean,
    onSystemBarsVisible: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var position by remember { mutableStateOf<ReadingPosition?>(null) }
    var source by remember { mutableStateOf<PdfPageSource?>(null) }
    var pageRatio by remember { mutableFloatStateOf(DEFAULT_PAGE_RATIO) }
    var openMessage by remember { mutableStateOf<String?>(null) }
    var startPage by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(true) }

    val listState = rememberLazyListState()

    /**
     * Ouvre un document et prépare la source de pages.
     *
     * `attach` sépare les deux entrées : au démarrage on relit un
     * rattachement existant, après le sélecteur on en enregistre un nouveau —
     * et c'est seulement là que le magasin décide si les pages déjà déclarées
     * survivent (même fichier) ou repartent de zéro (fichier différent).
     */
    suspend fun load(uri: Uri, attach: Boolean) {
        loading = true
        source?.close()
        source = null

        val opened = withContext(Dispatchers.IO) { PdfDocument.open(context, uri) }
        if (opened !is PdfOpenResult.Opened) {
            openMessage = opened.messageOrNull()
            loading = false
            return
        }

        val pages = PdfPageSource(opened.document)
        if (attach) {
            positions.attach(
                resourceId = resourceId,
                documentUri = uri.toString(),
                documentLabel = pages.label,
                pageCount = pages.pageCount,
                nowMillis = System.currentTimeMillis(),
            )
        }

        val stored = positions.position(resourceId)
        position = stored
        pageRatio = pages.aspectRatio(1) ?: DEFAULT_PAGE_RATIO
        // Le document a pu être remplacé par une version plus courte.
        startPage = (stored?.page ?: 1).coerceIn(1, maxOf(pages.pageCount, 1))
        openMessage = null
        // Publié en dernier : c'est l'apparition de la source qui déclenche le
        // retour au signet, et elle doit trouver `startPage` déjà juste.
        source = pages
        loading = false
    }

    /**
     * `ACTION_OPEN_DOCUMENT` et non `GET_CONTENT` : seul le premier donne
     * une permission qu'on peut rendre persistante. Sans cela le document
     * ne serait plus lisible au prochain démarrage, et « Reprendre » ne
     * tiendrait pas sa promesse.
     */
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        scope.launch { load(uri, attach = true) }
    }

    LaunchedEffect(resourceId) {
        val stored = positions.position(resourceId)
        position = stored
        if (stored == null) {
            loading = false
            return@LaunchedEffect
        }
        load(Uri.parse(stored.documentUri), attach = false)
    }

    // Retour au signet : une fois le document ouvert, et une seule.
    LaunchedEffect(source) {
        val pages = source ?: return@LaunchedEffect
        listState.scrollToItem((startPage - 1).coerceIn(0, maxOf(pages.pageCount - 1, 0)))
    }

    val pageCount = source?.pageCount ?: 0
    val currentPage by remember { derivedStateOf { currentPageOf(listState) } }

    // Le signet est posé à la sortie de l'écran, pas à chaque page tournée :
    // écrire à chaque geste userait le magasin sans rien garantir de plus.
    // D'où `DisposableEffect(Unit)` et des valeurs relues au dernier moment —
    // lier l'effet à la page le relançait précisément à chaque page tournée,
    // ce qu'il prétendait éviter, et le défilement continu en produit une par
    // seconde.
    val pageAtExit = rememberUpdatedState(currentPage)
    val countAtExit = rememberUpdatedState(pageCount)
    val positionAtExit = rememberUpdatedState(position)
    DisposableEffect(Unit) {
        onDispose {
            val known = positionAtExit.value ?: return@onDispose
            val total = countAtExit.value
            if (total <= 0) return@onDispose
            Graph.applicationScope.launch {
                positions.remember(
                    known.copy(
                        page = pageAtExit.value.coerceIn(1, total),
                        pageCount = total,
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { source?.close() }
    }

    val reading = source
    // Hors lecture, les barres système reviennent : un état d'erreur en plein
    // écran donnerait l'impression d'une application figée.
    LaunchedEffect(reading == null) {
        if (reading == null) onSystemBarsVisible(true)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            reading == null -> NoDocumentState(
                resourceLabel = resourceLabel,
                openMessage = openMessage,
                onAttach = { picker.launch(arrayOf("application/pdf")) },
                onClose = onClose,
            )

            else -> PdfReader(
                pages = reading,
                fallbackRatio = pageRatio,
                listState = listState,
                currentPage = currentPage,
                title = resourceLabel.ifBlank { "Lecture" },
                subtitle = position?.documentLabel?.takeIf { it.isNotBlank() },
                suggestion = position?.copy(page = currentPage)?.let(::suggestedPageRange),
                canDeclare = canDeclare,
                onDeclare = onDeclare,
                onChangeDocument = { picker.launch(arrayOf("application/pdf")) },
                onSystemBarsVisible = onSystemBarsVisible,
                onClose = onClose,
            )
        }
    }
}

/**
 * L'écran de lecture proprement dit.
 *
 * Tout ce qui n'est pas la page est posé **par-dessus** et s'efface : la
 * surface utile d'un lecteur est la page, et chaque explication empilée
 * dessous en retirait un tiers.
 */
@Composable
private fun PdfReader(
    pages: PdfPageSource,
    fallbackRatio: Float,
    listState: LazyListState,
    currentPage: Int,
    title: String,
    subtitle: String?,
    suggestion: IntRange?,
    canDeclare: Boolean,
    onDeclare: (Int, Int) -> Unit,
    onChangeDocument: () -> Unit,
    onSystemBarsVisible: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var jumpVisible by rememberSaveable { mutableStateOf(false) }
    var badgeVisible by remember { mutableStateOf(true) }

    // Lues dans le bloc `graphicsLayer` et dans les gestes, jamais pendant la
    // composition : un pincement produit des dizaines de valeurs par seconde,
    // et les relire ici recomposerait la liste entière à chaque doigt qui
    // bouge. Le calque, lui, se contente de redessiner.
    var scale by remember { mutableFloatStateOf(ReaderZoom.MIN_SCALE) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    /** Repasser en rendu fin est un changement d'état, pas un continuum. */
    val sharpRender by remember { derivedStateOf { scale > SHARP_ZOOM_THRESHOLD } }

    LaunchedEffect(chromeVisible) { onSystemBarsVisible(chromeVisible) }

    // La pastille accompagne le geste puis s'efface. Elle est relancée par le
    // changement de page comme par la reprise du défilement : rester affichée
    // en permanence ferait d'un repère une décoration.
    LaunchedEffect(currentPage, listState.isScrollInProgress) {
        badgeVisible = true
        if (!listState.isScrollInProgress) {
            delay(BADGE_LINGER_MILLIS)
            badgeVisible = false
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportWidth = constraints.maxWidth
        val renderWidth = if (sharpRender) {
            minOf(viewportWidth * 2, MAX_SHARP_WIDTH_PX)
        } else {
            viewportWidth
        }

        fun applyZoom(centroid: Offset, pan: Offset, factor: Float) {
            val from = scale
            val zoomed = ReaderZoom.zoomAround(
                current = ZoomState(from, offsetX),
                targetScale = from * factor,
                focalX = centroid.x,
                viewportWidth = viewportWidth.toFloat(),
            )
            // Le déplacement vertical n'a pas d'offset propre : il devient du
            // défilement, converti en pixels de mise en page.
            listState.dispatchRawDelta(
                ReaderZoom.scrollDeltaForZoom(from, zoomed.scale, centroid.y) - pan.y / zoomed.scale
            )
            scale = zoomed.scale
            offsetX = ReaderZoom.clampOffsetX(
                zoomed.offsetX + pan.x,
                zoomed.scale,
                viewportWidth.toFloat(),
            )
        }

        fun applyPan(pan: Offset) {
            listState.dispatchRawDelta(-pan.y / scale)
            offsetX = ReaderZoom.clampOffsetX(offsetX + pan.x, scale, viewportWidth.toFloat())
        }

        /** Double tap : ajusté à la largeur ⟷ agrandi, autour du point touché. */
        fun toggleZoom(at: Offset) {
            scope.launch {
                val from = scale
                val to = if (from > ReaderZoom.MIN_SCALE * 1.05f) {
                    ReaderZoom.MIN_SCALE
                } else {
                    ReaderZoom.DOUBLE_TAP_SCALE
                }
                var previous = from
                animate(from, to, animationSpec = tween(durationMillis = 180)) { value, _ ->
                    val step = ReaderZoom.zoomAround(
                        current = ZoomState(previous, offsetX),
                        targetScale = value,
                        focalX = at.x,
                        viewportWidth = viewportWidth.toFloat(),
                    )
                    listState.dispatchRawDelta(
                        ReaderZoom.scrollDeltaForZoom(previous, step.scale, at.y)
                    )
                    scale = step.scale
                    offsetX = step.offsetX
                    previous = step.scale
                }
            }
        }

        // Le fond n'est pas une couleur plate : il s'assombrit sur les deux
        // bords. C'est ce qui fait que la page se lit comme une feuille posee
        // sur une table, et non comme un rectangle blanc colle a l'ecran.
        val desk = Brush.horizontalGradient(
            0f to MaterialTheme.colorScheme.background,
            0.16f to MaterialTheme.colorScheme.surface,
            0.84f to MaterialTheme.colorScheme.surface,
            1f to MaterialTheme.colorScheme.background,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(desk)
                .pointerInput(viewportWidth) {
                    detectTapGestures(
                        onTap = { chromeVisible = !chromeVisible },
                        onDoubleTap = { at -> toggleZoom(at) },
                    )
                }
                .pointerInput(viewportWidth) {
                    readingGestures(
                        scale = { scale },
                        onZoom = { centroid, pan, factor -> applyZoom(centroid, pan, factor) },
                        onPan = { pan -> applyPan(pan) },
                    )
                },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        // Milieu horizontal, bord haut : la verticale est
                        // tenue par le défilement, pas par le calque.
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    },
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(count = pages.pageCount, key = { it }) { index ->
                    PdfPageView(
                        page = index + 1,
                        total = pages.pageCount,
                        pages = pages,
                        renderWidthPx = renderWidth,
                        fallbackRatio = fallbackRatio,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            ReaderTopBar(
                title = title,
                subtitle = subtitle,
                onJump = { jumpVisible = true },
                onClose = onClose,
            )
        }

        // Pastille et barre basse dans la même colonne : la pastille descend
        // d'elle-même quand la barre s'efface, sans marge devinée à la main.
        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(visible = badgeVisible, enter = fadeIn(), exit = fadeOut()) {
                PageBadge(
                    text = "$currentPage / ${pages.pageCount}",
                    onClick = { jumpVisible = true },
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
            ) {
                ReaderBottomBar(
                    suggestion = suggestion,
                    canDeclare = canDeclare,
                    onDeclare = onDeclare,
                    onChangeDocument = onChangeDocument,
                )
            }
        }
    }

    if (jumpVisible) {
        JumpToPageDialog(
            pageCount = pages.pageCount,
            currentPage = currentPage,
            onDismiss = { jumpVisible = false },
            onGo = { target ->
                jumpVisible = false
                scope.launch { listState.scrollToItem(target - 1) }
            },
        )
    }
}

/**
 * Le pincement, sans voler son défilement à la liste.
 *
 * `detectTransformGestures` ne tient pas ici : la `LazyColumn` est un enfant,
 * elle consomme les glissements avant que le parent ne les voie, et le
 * détecteur s'annule dès la première consommation — le pincement resterait
 * sans effet. On lit donc les évènements à la passe `Initial`, où le parent
 * passe en premier, et on ne prend la main que dans les deux cas que la liste
 * ne sait pas traiter : deux doigts, ou un doigt sur une page déjà agrandie.
 * Le reste du temps rien n'est consommé, et le défilement garde son inertie.
 */
private suspend fun PointerInputScope.readingGestures(
    scale: () -> Float,
    onZoom: (centroid: Offset, pan: Offset, factor: Float) -> Unit,
    onPan: (Offset) -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var travelled = 0f
        var taken = false

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.count { it.pressed }
            if (pressed == 0) break

            if (pressed >= 2) {
                taken = true
                val factor = event.calculateZoom()
                val pan = event.calculatePan()
                if (factor != 1f || pan != Offset.Zero) {
                    onZoom(event.calculateCentroid(useCurrent = true), pan, factor)
                }
                event.changes.forEach { it.consume() }
                continue
            }

            // Page ajustée à la largeur et geste pas encore repris : la liste
            // fait mieux que nous, on la laisse faire.
            if (!taken && scale() <= ReaderZoom.MIN_SCALE) continue

            val pan = event.calculatePan()
            travelled += pan.getDistance()
            // Le seuil de déplacement protège l'appui simple : sans lui, les
            // deux ou trois pixels de tremblement d'un doigt seraient
            // consommés et l'appui n'atteindrait jamais les commandes.
            if (!taken && travelled < slop) continue

            taken = true
            if (pan != Offset.Zero) onPan(pan)
            event.changes.forEach { it.consume() }
        }
    }
}

/**
 * Une page de la liste.
 *
 * L'emplacement prend sa hauteur **avant** son image, à partir du format de
 * page connu : une liste dont les entrées mesurent zéro tant qu'elles ne sont
 * pas rendues saute sous le doigt et rend le saut de page inutilisable.
 */
@Composable
private fun PdfPageView(
    page: Int,
    total: Int,
    pages: PdfPageSource,
    renderWidthPx: Int,
    fallbackRatio: Float,
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var ratio by remember { mutableFloatStateOf(fallbackRatio) }

    LaunchedEffect(page, renderWidthPx) {
        if (renderWidthPx <= 0) return@LaunchedEffect
        // L'ancienne image reste affichée pendant le nouveau rendu : effacer
        // d'abord ferait clignoter la page en blanc à chaque zoom.
        val rendered = pages.page(page, renderWidthPx) ?: return@LaunchedEffect
        ratio = rendered.width.toFloat() / rendered.height.toFloat()
        bitmap = rendered
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            // Le blanc du papier, posé avant l'image : c'est la couleur que
            // `PdfDocument` donne au fond, l'arrivée du rendu ne clignote pas.
            .background(Color.White),
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Page $page sur $total",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    subtitle: String?,
    onJump: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    // Le titre du document est le seul endroit du lecteur qui
                    // porte la serif : c'est un nom d'ouvrage, pas un libelle.
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Du texte, pas de pictogramme : le jeu d'icônes étendu n'est pas
            // dans le projet, et « Aller à » se lit mieux qu'une loupe.
            TextButton(onClick = onJump) { Text("Aller à") }
            TextButton(onClick = onClose) { Text("Fermer") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ReaderBottomBar(
    suggestion: IntRange?,
    canDeclare: Boolean,
    onDeclare: (Int, Int) -> Unit,
    onChangeDocument: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (canDeclare && suggestion != null) {
                PrimaryAction(
                    text = "Déclarer : pages ${suggestion.first} → ${suggestion.last}",
                    onClick = { onDeclare(suggestion.first, suggestion.last) },
                )
                // L'avertissement vit ici, avec le bouton qu'il qualifie, et
                // non sous la page où il occupait la place de la lecture.
                Text(
                    "La plage est proposée, pas enregistrée : rien ne part tant que tu " +
                        "n'as pas validé la déclaration.",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                )
            } else if (canDeclare) {
                Text(
                    "Tout ce qui est lu jusqu'ici est déjà déclaré.",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                )
            }
            SecondaryAction(text = "Changer de document", onClick = onChangeDocument)
        }
    }
}

/**
 * « 47 / 312 ». Touchable, parce qu'un repère de page est aussi une cible.
 *
 * En chiffres à chasse fixe : un compteur qui change de largeur à chaque page
 * tournée fait bouger la pastille sous le pouce.
 */
@Composable
private fun PageBadge(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun JumpToPageDialog(
    pageCount: Int,
    currentPage: Int,
    onDismiss: () -> Unit,
    onGo: (Int) -> Unit,
) {
    var entry by remember { mutableStateOf(currentPage.toString()) }
    val target = entry.toIntOrNull()
    val valid = target != null && target in 1..pageCount

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aller à la page") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = entry,
                    // Filtré à la saisie : un champ numérique qui accepte des
                    // lettres n'échoue qu'à la validation, trop tard.
                    onValueChange = { entry = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("Numéro de page") },
                )
                Text(
                    "Ce document compte $pageCount pages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { target?.let(onGo) }, enabled = valid) { Text("Y aller") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

/**
 * L'état sans document. C'est ici que vivent les explications : elles ont un
 * sens tant qu'aucune page n'est ouverte, et n'en ont plus une fois qu'on lit.
 */
@Composable
private fun NoDocumentState(
    resourceLabel: String,
    openMessage: String?,
    onAttach: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(
            title = resourceLabel.ifBlank { "Lecture" },
            subtitle = "Le lecteur retient la page ; rien n'en sort tant que tu ne déclares pas.",
        )

        // Le message d'échec dit ce qui s'est passé ET ce que la ressource
        // devient : elle reste dans la file.
        if (openMessage == null) {
            EmptyState("Aucun document rattaché. Choisis le PDF local correspondant à cette ressource.")
        } else {
            Notice(openMessage, tone = NoticeTone.PROBLEM)
        }

        PrimaryAction(
            text = if (openMessage == null) "Rattacher un PDF" else "Rattacher à nouveau",
            onClick = onAttach,
        )
        Text(
            "Un PDF stocké sur Drive doit d'abord être rendu disponible hors ligne : " +
                "l'application ne le télécharge pas à ta place.",
            style = MaterialTheme.typography.bodySmall,
            color = mutedColor,
        )
        SecondaryAction(text = "Fermer", onClick = onClose)
    }
}

/**
 * La page « en cours » est celle qui occupe le centre de l'écran, et non la
 * première visible : en défilement continu, la fin d'une page et le début de
 * la suivante coexistent, et c'est celle qu'on lit qui doit être annoncée —
 * puis proposée à la déclaration.
 */
private fun currentPageOf(state: LazyListState): Int {
    val info = state.layoutInfo
    val centre = (info.viewportStartOffset + info.viewportEndOffset) / 2
    val nearest = info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - centre) }
    return (nearest?.index ?: state.firstVisibleItemIndex) + 1
}
