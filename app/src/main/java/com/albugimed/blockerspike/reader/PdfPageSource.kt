package com.albugimed.blockerspike.reader

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Les pages rendues, en nombre borné.
 *
 * Le défilement continu change la question posée au rendu : ce n'est plus
 * « dessine la page 47 » mais « dessine ce qui est à l'écran, pendant qu'on
 * fait défiler ». Deux contraintes en découlent, et cette classe n'existe que
 * pour elles.
 *
 * 1. **Un seul rendu à la fois.** `PdfRenderer` n'autorise qu'une page
 *    ouverte : deux entrées de liste qui se dessinent en parallèle lèvent
 *    `IllegalStateException`. D'où le verrou, qui sérialise les rendus au
 *    lieu de faire tomber le lecteur.
 * 2. **Un cache qui a une fin.** Un collège de 400 pages en ARGB_8888 pèse
 *    plusieurs gigaoctets ; garder les images rendues sans limite tient
 *    quelques dizaines de pages avant l'`OutOfMemoryError`. `LruCache` borne
 *    en octets — pas en nombre de pages, qui ne dit rien du coût réel.
 *
 * Aucune dépendance ajoutée : `LruCache` est dans le SDK.
 */
class PdfPageSource(
    private val document: PdfDocument,
    budgetBytes: Int = defaultBudgetBytes(),
) : Closeable {

    val pageCount: Int get() = document.pageCount
    val label: String get() = document.label

    private data class PageKey(val page: Int, val widthPixels: Int)

    private val bitmaps = object : LruCache<PageKey, Bitmap>(budgetBytes) {
        override fun sizeOf(key: PageKey, value: Bitmap): Int = value.byteCount
    }

    private val renderLock = Mutex()

    /**
     * La page demandée, rendue à la largeur demandée, hors du fil principal.
     *
     * La largeur fait partie de la clé : agrandir déclenche un rendu plus fin
     * de la même page, et les deux versions coexistent le temps que le cache
     * arbitre. Renvoie `null` quand la page n'existe pas ou que le fichier
     * s'est abîmé — l'écran affiche alors un emplacement vide plutôt que de
     * s'interrompre.
     */
    suspend fun page(displayedPage: Int, widthPixels: Int): Bitmap? {
        if (widthPixels <= 0) return null
        val key = PageKey(displayedPage, widthPixels)
        bitmaps.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            renderLock.withLock {
                // Une autre entrée visible a pu rendre la même page pendant
                // l'attente du verrou : on ne la dessine pas deux fois.
                bitmaps.get(key)
                    ?: document.render(displayedPage, widthPixels)?.also { bitmaps.put(key, it) }
            }
        }
    }

    /**
     * Le rapport largeur / hauteur d'une page, sans la dessiner.
     *
     * Sert à donner sa hauteur à une entrée de liste **avant** son image :
     * sans cela, toutes les pages mesureraient zéro tant qu'elles ne sont pas
     * rendues, la barre de défilement mentirait et « aller à la page 200 »
     * n'atterrirait nulle part.
     */
    suspend fun aspectRatio(displayedPage: Int): Float? = withContext(Dispatchers.IO) {
        renderLock.withLock { document.aspectRatio(displayedPage) }
    }

    override fun close() {
        bitmaps.evictAll()
        document.close()
    }

    companion object {
        /**
         * Une fraction du tas, jamais une valeur fixe : le même document doit
         * rester lisible sur un appareil modeste. Les bornes évitent les deux
         * extrêmes — un cache si petit qu'il redessine à chaque geste, un
         * cache si grand qu'il pousse le reste de l'application dehors.
         */
        fun defaultBudgetBytes(): Int = (Runtime.getRuntime().maxMemory() / 4L)
            .coerceIn(24L * 1024 * 1024, 96L * 1024 * 1024)
            .toInt()
    }
}
