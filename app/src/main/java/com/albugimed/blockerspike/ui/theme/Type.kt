package com.albugimed.blockerspike.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Trois familles, aucune embarquee.
 *
 * La conception rendue demandait Fraunces pour les titres, Manrope pour le
 * corps et DM Mono pour les surtitres. Aucune n'est embarquee ici, et c'est un
 * choix, pas un renoncement :
 *
 * - recuperer Fraunces suppose de telecharger un fichier, et l'API Google
 *   Fonts d'Android exige la dependance `ui-text-google-fonts` que le
 *   **contrat §10 interdit** ;
 * - a 12–14 sp sur un ecran a 520 dpi, Manrope est indiscernable de la police
 *   systeme. Payer 300 Ko pour une difference invisible n'est pas un arbitrage,
 *   c'est une distraction.
 *
 * Ce qui reste, et qui porte reellement l'identite, c'est **le geste** : un
 * serif pour les titres ([FontFamily.Serif], Noto Serif sur Android),
 * l'interlettrage resserre qui donne aux gros titres leur densite, et un
 * chasse-fixe pour les surtitres. Zero octet, zero dependance.
 *
 * L'echelle tient en trois niveaux — titre d'ecran, titre de bloc, corps — et
 * `labelSmall` sert aux surtitres et aux faits neutres (« 13 j », « J-45 »).
 * L'ancien ecran empilait quinze tailles indistinctes.
 */
private val Display = FontFamily.Serif
private val Kicker = FontFamily.Monospace

internal val AlbugimedTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-1.2).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.7).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.4).sp,
    ),
    /** Le titre d'une carte : serif lui aussi, sinon le geste s'arrete au haut de l'ecran. */
    titleMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.1.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    /**
     * Le surtitre : chasse-fixe, capitales, espacees.
     *
     * 12 sp et non 11 : c'est le plancher lisible d'Android, et ce style porte
     * des mots qu'on lit une fraction de seconde en tenant le telephone d'une
     * main.
     */
    labelSmall = TextStyle(
        fontFamily = Kicker,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.1.sp,
    ),
)
