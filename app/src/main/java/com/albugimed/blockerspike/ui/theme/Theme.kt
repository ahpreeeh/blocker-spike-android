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
    primary = SaugeJour.Accent,
    onPrimary = Color.White,
    primaryContainer = SaugeJour.AccentMuted,
    onPrimaryContainer = SaugeJour.TextPrimary,
    secondary = SaugeJour.Success,
    onSecondary = Color.White,
    secondaryContainer = SaugeJour.SuccessMuted,
    onSecondaryContainer = SaugeJour.TextPrimary,
    tertiary = SaugeJour.Secondary,
    onTertiary = Color.White,
    tertiaryContainer = SaugeJour.SecondaryMuted,
    onTertiaryContainer = SaugeJour.TextPrimary,
    background = SaugeJour.Page,
    onBackground = SaugeJour.TextPrimary,
    surface = SaugeJour.Card,
    onSurface = SaugeJour.TextPrimary,
    surfaceVariant = SaugeJour.Pill,
    onSurfaceVariant = SaugeJour.TextSecondary,
    surfaceContainerLowest = SaugeJour.Card,
    surfaceContainerLow = SaugeJour.Card,
    surfaceContainer = SaugeJour.Card,
    surfaceContainerHigh = SaugeJour.ActiveNav,
    surfaceContainerHighest = SaugeJour.Pill,
    outline = SaugeJour.BorderActive,
    outlineVariant = SaugeJour.Border,
    error = SaugeJour.Danger,
    onError = Color.White,
    errorContainer = SaugeJour.DangerMuted,
    onErrorContainer = SaugeJour.TextPrimary,
)

private val DarkScheme = darkColorScheme(
    primary = SaugeNuit.Accent,
    // L'accent nocturne est un citron clair : le texte qui se pose dessus doit
    // etre sombre, pas la couleur de page comme dans l'ancien theme indigo.
    onPrimary = Color(0xFF14231A),
    primaryContainer = SaugeNuit.AccentMuted,
    onPrimaryContainer = SaugeNuit.TextPrimary,
    secondary = SaugeNuit.Success,
    onSecondary = SaugeNuit.Page,
    secondaryContainer = SaugeNuit.SuccessMuted,
    onSecondaryContainer = SaugeNuit.TextPrimary,
    tertiary = SaugeNuit.Secondary,
    onTertiary = SaugeNuit.Page,
    tertiaryContainer = SaugeNuit.SecondaryMuted,
    onTertiaryContainer = SaugeNuit.TextPrimary,
    background = SaugeNuit.Page,
    onBackground = SaugeNuit.TextPrimary,
    surface = SaugeNuit.Card,
    onSurface = SaugeNuit.TextPrimary,
    surfaceVariant = SaugeNuit.Pill,
    onSurfaceVariant = SaugeNuit.TextSecondary,
    surfaceContainerLowest = SaugeNuit.Page,
    surfaceContainerLow = SaugeNuit.Card,
    surfaceContainer = SaugeNuit.Card,
    surfaceContainerHigh = SaugeNuit.ActiveNav,
    surfaceContainerHighest = SaugeNuit.Pill,
    outline = SaugeNuit.BorderActive,
    outlineVariant = SaugeNuit.Border,
    error = SaugeNuit.Danger,
    onError = SaugeNuit.Page,
    errorContainer = SaugeNuit.DangerMuted,
    onErrorContainer = SaugeNuit.TextPrimary,
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
}

private val LightExtras = AlbugimedExtras(
    textMuted = SaugeJour.TextMuted,
    subjectHues = SubjectHuesLight,
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
    textMuted = SaugeNuit.TextMuted,
    subjectHues = SubjectHuesDark,
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
 * Rayons repris de la conception : 22 dp pour la carte de protection, 16 dp
 * pour les cartes ordinaires, 11 dp pour les boutons.
 */
private val AlbugimedShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(11.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

/** Forme des boutons pleine largeur, la plus frequente de l'application. */
val ActionShape: Shape = RoundedCornerShape(11.dp)

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
