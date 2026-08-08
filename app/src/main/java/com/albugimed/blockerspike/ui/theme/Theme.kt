package com.albugimed.blockerspike.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val LightScheme = lightColorScheme(
    primary = ForetJour.Accent,
    // Le citron clair, et non le blanc : c'est la pilule de la conception, et
    // elle tient 6,9:1 sur le vert profond — mieux que le blanc n'y tiendrait
    // sur n'importe quel vert assez clair pour paraitre amical.
    onPrimary = Citron.Clair,
    primaryContainer = ForetJour.AccentMuted,
    onPrimaryContainer = ForetJour.TextPrimary,
    secondary = ForetJour.Success,
    onSecondary = Color.White,
    secondaryContainer = ForetJour.SuccessMuted,
    onSecondaryContainer = ForetJour.TextPrimary,
    tertiary = ForetJour.Secondary,
    onTertiary = Color.White,
    tertiaryContainer = ForetJour.SecondaryMuted,
    onTertiaryContainer = ForetJour.TextPrimary,
    background = ForetJour.Page,
    onBackground = ForetJour.TextPrimary,
    surface = ForetJour.Card,
    onSurface = ForetJour.TextPrimary,
    surfaceVariant = ForetJour.Pill,
    onSurfaceVariant = ForetJour.TextSecondary,
    surfaceContainerLowest = ForetJour.Card,
    surfaceContainerLow = ForetJour.Card,
    surfaceContainer = ForetJour.Card,
    surfaceContainerHigh = ForetJour.ActiveNav,
    surfaceContainerHighest = ForetJour.Pill,
    outline = ForetJour.BorderActive,
    outlineVariant = ForetJour.Border,
    error = ForetJour.Danger,
    onError = Color.White,
    errorContainer = ForetJour.DangerMuted,
    onErrorContainer = ForetJour.TextPrimary,
)

private val DarkScheme = darkColorScheme(
    primary = ForetNuit.Accent,
    // L'accent nocturne est un citron clair : le texte qui se pose dessus doit
    // etre sombre, pas la couleur de page comme dans l'ancien theme indigo.
    onPrimary = Color(0xFF14231A),
    primaryContainer = ForetNuit.AccentMuted,
    onPrimaryContainer = ForetNuit.TextPrimary,
    secondary = ForetNuit.Success,
    onSecondary = ForetNuit.Page,
    secondaryContainer = ForetNuit.SuccessMuted,
    onSecondaryContainer = ForetNuit.TextPrimary,
    tertiary = ForetNuit.Secondary,
    onTertiary = ForetNuit.Page,
    tertiaryContainer = ForetNuit.SecondaryMuted,
    onTertiaryContainer = ForetNuit.TextPrimary,
    background = ForetNuit.Page,
    onBackground = ForetNuit.TextPrimary,
    surface = ForetNuit.Card,
    onSurface = ForetNuit.TextPrimary,
    surfaceVariant = ForetNuit.Pill,
    onSurfaceVariant = ForetNuit.TextSecondary,
    surfaceContainerLowest = ForetNuit.Page,
    surfaceContainerLow = ForetNuit.Card,
    surfaceContainer = ForetNuit.Card,
    surfaceContainerHigh = ForetNuit.ActiveNav,
    surfaceContainerHighest = ForetNuit.Pill,
    outline = ForetNuit.BorderActive,
    outlineVariant = ForetNuit.Border,
    error = ForetNuit.Danger,
    onError = ForetNuit.Page,
    errorContainer = ForetNuit.DangerMuted,
    onErrorContainer = ForetNuit.TextPrimary,
)

/**
 * Ce que Material 3 n'a pas d'emplacement pour, et dont l'application a
 * pourtant besoin partout : le gris des faits neutres, la couleur d'une
 * matiere, et les teintes de la carte de protection.
 */
@Immutable
data class AlbugimedExtras(
    val textMuted: Color,
    val subjectHues: List<Color>,
    val coverHues: List<Color>,
    val protectionActive: Color,
    val protectionPaused: Color,
    val protectionRing: Color,
    val onProtectionTitle: Color,
    val onProtectionBody: Color,
    val onProtectionKicker: Color,
    val onProtectionLink: Color,
    val onProtectionOutline: Color,
    val onPausedBody: Color,
    val onPausedKicker: Color,
    val exitBackground: Color,
    val exitForeground: Color,
    val exitBorder: Color,
) {
    /**
     * Couleur stable d'une matiere, derivee de son libelle.
     *
     * Volontairement une fonction du **nom** et non de la position : la
     * couleur ne doit jamais bouger parce qu'une autre matiere a ete
     * ajoutee, et elle ne doit jamais encoder un rang.
     */
    fun subjectHue(label: String): Color {
        if (subjectHues.isEmpty()) return textMuted
        val index = (label.hashCode().toLong() and 0xFFFFFFFFL) % subjectHues.size
        return subjectHues[index.toInt()]
    }

    /**
     * Couleur de couverture d'un document, derivee du meme libelle et par le
     * meme calcul que [subjectHue] : deux documents de la meme matiere
     * partagent leur couverture, et c'est le but — la couverture *dit* la
     * matiere, elle n'est pas une decoration tiree au sort.
     */
    fun coverHue(label: String): Color {
        if (coverHues.isEmpty()) return textMuted
        val index = (label.hashCode().toLong() and 0xFFFFFFFFL) % coverHues.size
        return coverHues[index.toInt()]
    }
}

private val LightExtras = AlbugimedExtras(
    textMuted = ForetJour.TextMuted,
    subjectHues = SubjectHuesLight,
    coverHues = CoverHues,
    protectionActive = Protection.ActiveJour,
    protectionPaused = Protection.PausedJour,
    protectionRing = Protection.PausedRing,
    onProtectionTitle = Protection.OnActiveTitle,
    onProtectionBody = Protection.OnActiveBody,
    onProtectionKicker = Protection.OnActiveKicker,
    onProtectionLink = Protection.OnActiveLink,
    onProtectionOutline = Protection.OnActiveOutline,
    onPausedBody = Protection.OnPausedBody,
    onPausedKicker = Protection.OnPausedKicker,
    exitBackground = Protection.ExitBackgroundJour,
    exitForeground = Protection.ExitForegroundJour,
    exitBorder = Protection.ExitBorderJour,
)

private val DarkExtras = AlbugimedExtras(
    textMuted = ForetNuit.TextMuted,
    subjectHues = SubjectHuesDark,
    coverHues = CoverHues,
    protectionActive = Protection.ActiveNuit,
    protectionPaused = Protection.PausedNuit,
    protectionRing = Protection.PausedRing,
    onProtectionTitle = Protection.OnActiveTitle,
    onProtectionBody = Protection.OnActiveBody,
    onProtectionKicker = Protection.OnActiveKicker,
    onProtectionLink = Protection.OnActiveLink,
    onProtectionOutline = Protection.OnActiveOutline,
    onPausedBody = Protection.OnPausedBody,
    onPausedKicker = Protection.OnPausedKicker,
    exitBackground = Protection.ExitBackgroundNuit,
    exitForeground = Protection.ExitForegroundNuit,
    exitBorder = Protection.ExitBorderNuit,
)

val LocalAlbugimedExtras = staticCompositionLocalOf { LightExtras }

/**
 * Rayons repris de la conception : **24 dp** pour les cartes, 12 dp pour les
 * boutons.
 *
 * Le saut de 16 a 24 n'est pas cosmetique. A 16 dp, une carte reste un
 * rectangle a qui on a lime les angles ; a 24 elle devient une forme, et c'est
 * cette rondeur-la — repetee sur chaque bloc, du haut de l'ecran au bas — qui
 * porte l'essentiel de l'impression de douceur de la maquette.
 */
private val AlbugimedShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Forme des boutons pleine largeur, la plus frequente de l'application. */
val ActionShape: Shape = RoundedCornerShape(12.dp)

/**
 * Le theme de l'application. Toutes les `setContent` passent par ici — un
 * `MaterialTheme {}` nu quelque part et l'ecran repart en violet Material
 * par defaut, ce qui etait exactement l'etat precedent.
 */
@Composable
fun AlbugimedTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalAlbugimedExtras provides if (darkTheme) DarkExtras else LightExtras,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = AlbugimedTypography,
            shapes = AlbugimedShapes,
            content = content,
        )
    }
}
