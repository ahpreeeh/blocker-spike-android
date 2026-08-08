package com.albugimed.blockerspike.reader

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Le rendu, par `PdfRenderer` **de la plateforme**.
 *
 * Aucune bibliothèque ajoutée, et ce n'est pas de l'ascétisme : le §10 du
 * contrat impose de revérifier les quatre garanties réseau à chaque
 * dépendance, et les visionneuses PDF tierces sont exactement le genre de
 * composant qui télécharge des polices ou remonte de la télémétrie. La
 * plateforme sait déjà rendre une page ; c'est tout ce dont on a besoin.
 *
 * Ce que cette classe ne fait pas : annotation, recherche, table des
 * matières. Hors périmètre V2.2, et `PdfRenderer` ne les offre pas.
 */
class PdfDocument private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    val label: String,
) : Closeable {

    val pageCount: Int get() = renderer.pageCount

    /**
     * Rend une page **affichée** (à partir de 1) à la largeur demandée.
     *
     * `PdfRenderer` compte à partir de 0 ; la conversion est faite ici, une
     * fois, plutôt que dispersée dans l'interface où un décalage d'une page
     * finirait par passer inaperçu.
     */
    fun render(displayedPage: Int, widthPixels: Int): Bitmap? {
        val index = displayedPage - 1
        if (index !in 0 until renderer.pageCount) return null
        if (widthPixels <= 0) return null

        return runCatching {
            renderer.openPage(index).use { page ->
                val height = (widthPixels.toLong() * page.height / page.width)
                    .toInt()
                    .coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(widthPixels, height, Bitmap.Config.ARGB_8888)
                // `PdfRenderer` dessine en transparent ce qui n'est pas
                // encré. Sans ce fond, une page s'afficherait en noir sur
                // fond sombre — illisible, et pris pour un fichier abîmé.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }.getOrNull()
    }

    /**
     * Le rapport largeur / hauteur d'une page, **sans la rendre**.
     *
     * Ouvrir une page n'alloue pas d'image : c'est assez pour réserver la
     * bonne hauteur dans la liste avant que le rendu n'arrive. Une page qui
     * se déclare de taille nulle est traitée comme inconnue plutôt que de
     * produire une division par zéro à la mise en page.
     */
    fun aspectRatio(displayedPage: Int): Float? {
        val index = displayedPage - 1
        if (index !in 0 until renderer.pageCount) return null

        return runCatching {
            renderer.openPage(index).use { page ->
                if (page.width <= 0 || page.height <= 0) {
                    null
                } else {
                    page.width.toFloat() / page.height.toFloat()
                }
            }
        }.getOrNull()
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    companion object {
        /**
         * Ouvre un document local. Renvoie l'échec **nommé** plutôt que de
         * lever : un fichier déplacé ou supprimé est un cas ordinaire, pas
         * une anomalie, et l'écran doit pouvoir le dire simplement.
         */
        fun open(context: Context, uri: Uri): PdfOpenResult {
            val descriptor = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (_: FileNotFoundException) {
                return PdfOpenResult.Missing
            } catch (_: SecurityException) {
                // La permission persistante a été révoquée — réinstallation,
                // nettoyage système, ou fichier déplacé hors du dossier
                // autorisé. Rattacher à nouveau suffit à réparer.
                return PdfOpenResult.PermissionLost
            } catch (_: IOException) {
                return PdfOpenResult.Unreadable
            } ?: return PdfOpenResult.Missing

            return try {
                val renderer = PdfRenderer(descriptor)
                PdfOpenResult.Opened(
                    PdfDocument(descriptor, renderer, displayNameOf(context.contentResolver, uri)),
                )
            } catch (_: IOException) {
                // Fichier tronqué, chiffré, ou qui n'est pas un PDF.
                runCatching { descriptor.close() }
                PdfOpenResult.Unreadable
            } catch (_: SecurityException) {
                runCatching { descriptor.close() }
                PdfOpenResult.PermissionLost
            }
        }

        /** Le nom affiché par le sélecteur, ou un repli neutre. */
        fun displayNameOf(resolver: ContentResolver, uri: Uri): String {
            val cursor: Cursor? = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            }.getOrNull()

            cursor?.use {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && it.moveToFirst()) {
                    it.getString(index)?.takeIf(String::isNotBlank)?.let { name -> return name }
                }
            }
            return "Document"
        }
    }
}

sealed interface PdfOpenResult {
    data class Opened(val document: PdfDocument) : PdfOpenResult

    /** Le fichier n'est plus là. La ressource, elle, reste dans la file. */
    data object Missing : PdfOpenResult

    /** Android ne nous laisse plus le lire. Il suffit de le rattacher. */
    data object PermissionLost : PdfOpenResult

    /** Le fichier est là mais n'est pas un PDF lisible. */
    data object Unreadable : PdfOpenResult
}

fun PdfOpenResult.messageOrNull(): String? = when (this) {
    is PdfOpenResult.Opened -> null
    PdfOpenResult.Missing ->
        "Le fichier n'est plus à cet emplacement. La ressource reste dans ta file — rattache-le pour reprendre."
    PdfOpenResult.PermissionLost ->
        "Android ne nous laisse plus ouvrir ce fichier. Rattache-le : la page mémorisée est conservée."
    PdfOpenResult.Unreadable ->
        "Ce fichier ne s'ouvre pas comme un PDF."
}
