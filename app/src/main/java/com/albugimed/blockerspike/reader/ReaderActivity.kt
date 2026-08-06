package com.albugimed.blockerspike.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.study.DeclareActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        setContent {
            MaterialTheme {
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
                    onClose = ::finish,
                )
            }
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

@Composable
private fun ReaderScreen(
    resourceId: String,
    resourceLabel: String,
    positions: ReadingPositionRepository,
    onDeclare: (Int, Int) -> Unit,
    canDeclare: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var position by remember { mutableStateOf<ReadingPosition?>(null) }
    var document by remember { mutableStateOf<PdfDocument?>(null) }
    var openMessage by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(true) }

    val widthPixels = with(LocalDensity.current) {
        (LocalConfiguration.current.screenWidthDp.dp - 32.dp).roundToPx()
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

        scope.launch {
            loading = true
            document?.close()
            when (val opened = withContext(Dispatchers.IO) { PdfDocument.open(context, uri) }) {
                is PdfOpenResult.Opened -> {
                    document = opened.document
                    openMessage = null
                    positions.attach(
                        resourceId = resourceId,
                        documentUri = uri.toString(),
                        documentLabel = opened.document.label,
                        pageCount = opened.document.pageCount,
                        nowMillis = System.currentTimeMillis(),
                    )
                    position = positions.position(resourceId)
                    page = position?.page ?: 1
                }

                else -> {
                    document = null
                    openMessage = opened.messageOrNull()
                }
            }
            loading = false
        }
    }

    LaunchedEffect(resourceId) {
        val stored = positions.position(resourceId)
        position = stored
        if (stored == null) {
            loading = false
            return@LaunchedEffect
        }

        when (
            val opened = withContext(Dispatchers.IO) {
                PdfDocument.open(context, Uri.parse(stored.documentUri))
            }
        ) {
            is PdfOpenResult.Opened -> {
                document = opened.document
                // Le document a pu être remplacé par une version plus courte.
                page = stored.page.coerceIn(1, opened.document.pageCount.coerceAtLeast(1))
            }

            else -> openMessage = opened.messageOrNull()
        }
        loading = false
    }

    // Le signet est posé à la sortie de l'écran, pas à chaque page tournée :
    // écrire à chaque geste userait le magasin sans rien garantir de plus.
    DisposableEffect(document, page) {
        onDispose {
            val current = document ?: return@onDispose
            val known = position ?: return@onDispose
            Graph.applicationScope.launch {
                positions.remember(
                    known.copy(
                        page = page,
                        pageCount = current.pageCount,
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { document?.close() }
    }

    val bitmap = remember(document, page, widthPixels) {
        document?.render(page, widthPixels)
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(resourceLabel, style = MaterialTheme.typography.titleLarge)
            position?.documentLabel?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            when {
                loading -> CircularProgressIndicator()

                document == null -> {
                    // Le message d'échec dit ce qui s'est passé ET ce que la
                    // ressource devient : elle reste dans la file.
                    Text(
                        openMessage
                            ?: "Aucun document rattaché. Choisis le PDF local correspondant à cette ressource.",
                    )
                    Button(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                        Text(if (openMessage == null) "Rattacher un PDF" else "Rattacher à nouveau")
                    }
                    Text(
                        "Un PDF stocké sur Drive doit d'abord être rendu disponible hors ligne : " +
                            "l'application ne le télécharge pas à ta place.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                else -> {
                    val total = document?.pageCount ?: 0
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Page $page sur $total",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } ?: Text("Cette page ne s'affiche pas.")

                    Text("Page $page / $total", style = MaterialTheme.typography.bodyMedium)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { page = (page - 1).coerceAtLeast(1) },
                            enabled = page > 1,
                        ) { Text("Précédente") }
                        OutlinedButton(
                            onClick = { page = (page + 1).coerceAtMost(total) },
                            enabled = page < total,
                        ) { Text("Suivante") }
                    }

                    val suggestion = position?.copy(page = page)?.let(::suggestedPageRange)
                    if (canDeclare && suggestion != null) {
                        Button(
                            onClick = { onDeclare(suggestion.first, suggestion.last) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Déclarer : pages ${suggestion.first} → ${suggestion.last}")
                        }
                        Text(
                            "La plage est proposée, pas enregistrée : rien ne part tant que tu " +
                                "n'as pas validé la déclaration.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else if (canDeclare) {
                        Text(
                            "Tout ce qui est lu jusqu'ici est déjà déclaré.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    TextButton(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                        Text("Changer de document")
                    }
                }
            }

            TextButton(onClick = onClose) { Text("Fermer") }
        }
    }
}
