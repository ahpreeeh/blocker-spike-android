package com.albugimed.blockerspike.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.albugimed.blockerspike.R

/**
 * Trois familles, embarquees.
 *
 * La version precedente s'en passait, et l'argument tenait tant que la
 * conception se contentait d'un serif systeme. Il ne tient plus : l'identite
 * rendue repose sur un **grotesque tres serre en graisse 800** que ni Noto
 * Serif ni Roboto ne savent imiter. Sans la police, il ne restait que
 * l'intention.
 *
 * Les fichiers vivent dans `res/font/` et ne sont **pas** une dependance
 * Gradle : le contrat §10 interdit d'ajouter des modules, pas d'ajouter des
 * ressources. C'est aussi ce qui ecarte `ui-text-google-fonts`, qui aurait
 * telecharge les memes octets au prix d'une dependance et d'un appel reseau au
 * premier affichage.
 *
 * - **Plus Jakarta Sans** (variable, 176 Ko) — les titres. Interlettrage
 *   negatif, graisse 800 : c'est lui, le geste.
 * - **Manrope** (variable, 165 Ko) — le corps et les libelles.
 * - **DM Mono** (statique, 400 et 500, 100 Ko) — les surtitres en capitales.
 *
 * Les deux premieres sont des **polices variables** : Google Fonts ne publie
 * plus d'instances statiques pour elles. Compose passe l'axe `wght` au moteur
 * de rendu a partir du [FontWeight] demande — d'ou un seul fichier par famille
 * la ou il en aurait fallu quatre. C'est supporte depuis l'API 26 ; le minSdk
 * est 36.
 *
 * Total ~440 Ko sur un APK de 72 Mo, soit six dixiemes de pourcent.
 *
 * L'echelle tient toujours en trois niveaux — titre d'ecran, titre de bloc,
 * corps — et `labelSmall` porte les surtitres et les faits neutres.
 */
private val Display = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold),
    Font(R.font.plus_jakarta_sans, FontWeight.ExtraBold),
)

private val Body = FontFamily(
    Font(R.font.manrope, FontWeight.Normal),
    Font(R.font.manrope, FontWeight.Medium),
    Font(R.font.manrope, FontWeight.SemiBold),
    Font(R.font.manrope, FontWeight.Bold),
    Font(R.font.manrope, FontWeight.ExtraBold),
)

private val Kicker = FontFamily(
    Font(R.font.dm_mono_regular, FontWeight.Normal),
    Font(R.font.dm_mono_medium, FontWeight.Medium),
)

internal val AlbugimedTypography = Typography(
    /**
     * Le titre d'ecran. 34 sp en 800, resserre de 1,7 sp.
     *
     * L'interlettrage negatif n'est pas une coquetterie : a cette graisse, les
     * lettres se touchent presque, et c'est cette densite qui fait lire le
     * titre comme un bloc plutot que comme une suite de mots.
     */
    headlineMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        letterSpacing = (-1.7).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 25.sp,
        lineHeight = 30.sp,
        letterSpacing = (-1.0).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.7).sp,
    ),
    /** Le titre d'une carte : la meme famille, sinon le geste s'arrete en haut de l'ecran. */
    titleMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
    ),
    /** Le texte d'un bouton : graisse 800, comme les pilules de la conception. */
    labelLarge = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
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
        letterSpacing = 1.0.sp,
    ),
)
