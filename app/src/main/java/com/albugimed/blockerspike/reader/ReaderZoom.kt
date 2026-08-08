package com.albugimed.blockerspike.reader

/**
 * L'arithmétique du zoom, sortie de l'interface pour être vérifiable.
 *
 * Un pincement qui dérive d'un demi-écran ne se voit pas en relisant du code
 * de composition : il se voit en le mesurant. Ces fonctions sont donc du
 * Kotlin ordinaire, sans Compose ni Android, et couvertes par
 * `ReaderZoomTest`.
 *
 * Convention retenue, la même que celle du calque de rendu : la page est
 * agrandie autour de son **milieu horizontal** et de son **bord haut**. Le
 * déplacement vertical n'a donc pas d'offset propre — il est délégué au
 * défilement de la liste, qui sait déjà où il en est.
 */
internal data class ZoomState(
    /** Facteur d'agrandissement appliqué, jamais hors de [MIN_SCALE]..[MAX_SCALE]. */
    val scale: Float,
    /** Décalage horizontal en pixels écran, positif vers la droite. */
    val offsetX: Float,
)

internal object ReaderZoom {

    /** Ajusté à la largeur. En dessous, la page laisserait des bandes vides. */
    const val MIN_SCALE = 1f

    /** Au-delà, le rendu est trop grossier pour qu'agrandir apporte encore. */
    const val MAX_SCALE = 5f

    /** Le cran du double tap : assez pour lire une note de bas de page. */
    const val DOUBLE_TAP_SCALE = 2.5f

    /**
     * Le débordement disponible de chaque côté.
     *
     * À l'échelle 1 il vaut zéro : la page occupe exactement la largeur, et
     * rien ne justifie de pouvoir la pousser hors de l'écran.
     */
    fun maxOffsetX(scale: Float, viewportWidth: Float): Float =
        (viewportWidth * (scale - 1f) / 2f).coerceAtLeast(0f)

    /** Empêche le panoramique de faire sortir la page du cadre. */
    fun clampOffsetX(offsetX: Float, scale: Float, viewportWidth: Float): Float {
        val limit = maxOffsetX(scale, viewportWidth)
        return offsetX.coerceIn(-limit, limit)
    }

    /**
     * Agrandit en gardant **le point pincé sous les doigts**.
     *
     * Sans cette correction, zoomer recentre la page sur son milieu : on perd
     * le paragraphe qu'on visait, et il faut le retrouver au panoramique. La
     * formule inverse simplement la position écran du point avant et après
     * changement d'échelle.
     */
    fun zoomAround(
        current: ZoomState,
        targetScale: Float,
        focalX: Float,
        viewportWidth: Float,
    ): ZoomState {
        val next = targetScale.coerceIn(MIN_SCALE, MAX_SCALE)
        if (current.scale <= 0f) return ZoomState(next, 0f)
        val half = viewportWidth / 2f
        val offset = focalX - half - (focalX - half - current.offsetX) * (next / current.scale)
        return ZoomState(next, clampOffsetX(offset, next, viewportWidth))
    }

    /**
     * De combien la liste doit défiler pour que le point pincé ne bouge pas
     * verticalement, exprimé en pixels de **mise en page** (ceux de la liste,
     * avant agrandissement). Positif : vers le bas du document.
     */
    fun scrollDeltaForZoom(fromScale: Float, toScale: Float, focalY: Float): Float {
        if (fromScale <= 0f || toScale <= 0f) return 0f
        return focalY * (1f / fromScale - 1f / toScale)
    }
}
