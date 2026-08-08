package com.albugimed.blockerspike.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le zoom se vérifie en le mesurant, pas en le relisant.
 *
 * Un pincement qui dérive d'un demi-écran ou une page qu'on pousse hors du
 * cadre sont des défauts qu'on ne voit qu'à l'usage, et qu'on ne retrouve
 * ensuite jamais volontairement. Ces cas sont donc figés ici.
 */
class ReaderZoomTest {

    private val largeur = 1080f
    private val tolerance = 0.01f

    @Test
    fun `ajustee a la largeur, la page ne peut pas se deplacer`() {
        // Rien à faire glisser : la page occupe exactement l'écran, et
        // pouvoir la pousser de côté ne montrerait que du vide.
        assertEquals(0f, ReaderZoom.maxOffsetX(1f, largeur), tolerance)
        assertEquals(0f, ReaderZoom.clampOffsetX(240f, 1f, largeur), tolerance)
    }

    @Test
    fun `le panoramique s arrete au bord de la page`() {
        assertEquals(540f, ReaderZoom.maxOffsetX(2f, largeur), tolerance)
        assertEquals(540f, ReaderZoom.clampOffsetX(4_000f, 2f, largeur), tolerance)
        assertEquals(-540f, ReaderZoom.clampOffsetX(-4_000f, 2f, largeur), tolerance)
    }

    @Test
    fun `l agrandissement reste entre ses bornes`() {
        val trop = ReaderZoom.zoomAround(ZoomState(4f, 0f), 20f, largeur / 2f, largeur)
        assertEquals(ReaderZoom.MAX_SCALE, trop.scale, tolerance)

        val pasAssez = ReaderZoom.zoomAround(ZoomState(2f, 0f), 0.1f, largeur / 2f, largeur)
        assertEquals(ReaderZoom.MIN_SCALE, pasAssez.scale, tolerance)

        // Le cran du double tap doit rester atteignable si quelqu'un abaisse
        // le plafond un jour.
        assertTrue(ReaderZoom.DOUBLE_TAP_SCALE <= ReaderZoom.MAX_SCALE)
        assertTrue(ReaderZoom.DOUBLE_TAP_SCALE >= ReaderZoom.MIN_SCALE)
    }

    @Test
    fun `le point pince reste sous les doigts`() {
        // Pincer sur le bord gauche : après agrandissement, ce bord doit
        // toujours être le bord gauche. Sans cette correction, le zoom
        // recentre sur le milieu et on perd le passage qu'on visait.
        val gauche = ReaderZoom.zoomAround(ZoomState(1f, 0f), 2f, focalX = 0f, viewportWidth = largeur)
        assertEquals(540f, gauche.offsetX, tolerance)

        val droite = ReaderZoom.zoomAround(ZoomState(1f, 0f), 2f, focalX = largeur, viewportWidth = largeur)
        assertEquals(-540f, droite.offsetX, tolerance)
    }

    @Test
    fun `pincer au centre ne decale pas la page`() {
        val centre = ReaderZoom.zoomAround(ZoomState(1f, 0f), 3f, largeur / 2f, largeur)
        assertEquals(0f, centre.offsetX, tolerance)
    }

    @Test
    fun `revenir a la largeur recentre la page`() {
        // Aller-retour complet : sortir du zoom ne doit pas laisser la page
        // décalée, sinon elle reviendrait tronquée d'un côté.
        val agrandie = ReaderZoom.zoomAround(ZoomState(1f, 0f), 2.5f, focalX = 0f, viewportWidth = largeur)
        val revenue = ReaderZoom.zoomAround(agrandie, 1f, focalX = 0f, viewportWidth = largeur)
        assertEquals(0f, revenue.offsetX, tolerance)
    }

    @Test
    fun `le defilement suit le point pince verticalement`() {
        // Doubler l'échelle autour d'un point à 600 px du haut demande de
        // descendre de 300 px de mise en page pour que ce point ne bouge pas.
        assertEquals(300f, ReaderZoom.scrollDeltaForZoom(1f, 2f, 600f), tolerance)
        assertEquals(-300f, ReaderZoom.scrollDeltaForZoom(2f, 1f, 600f), tolerance)
        assertEquals(0f, ReaderZoom.scrollDeltaForZoom(2f, 2f, 600f), tolerance)
    }
}
