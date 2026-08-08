package com.albugimed.blockerspike.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * La palette, reglee pour un telephone tenu a bout de bras.
 *
 * Elle ne vient plus de l'atelier web : elle vient de la conception rendue
 * pour cet appareil, une famille **sauge et olive** sur papier creme, plus
 * chaude et moins clinique que l'ardoise bleue qu'elle remplace. Ce qui n'a
 * pas change, c'est la methode : **chaque valeur est mesuree avant d'etre
 * ecrite**, jamais choisie a l'oeil.
 *
 * Les seuils tenus ici, et pourquoi ils different :
 *
 * - **texte : 4,5:1** — la regle WCAG, sans exception, y compris sur les
 *   fonds colores de la carte de protection ;
 * - **contours de controle : 3:1** — bouton, anneau d'alerte, pastille de
 *   matiere. Ce sont eux qui disent *ou appuyer* et *dans quel etat on est* ;
 * - **liseres de carte : remplissage ≥ 1,15 et lisere ≥ 2,0** et non 3:1.
 *   Un lisere ne peut pas atteindre 3:1 a la fois contre la page et contre la
 *   carte quand les deux sont proches — il faudrait un trait quasi noir. Le
 *   defaut d'origine n'etait pas une nuance : c'etait **1,07 de remplissage
 *   et 1,13 de lisere**, autrement dit aucune carte visible et tout l'ecran
 *   lu comme une seule colonne.
 *
 * Toute modification ici se reverifie en calculant, pas en regardant. Un
 * ecart de contraste ne se voit pas sur l'ecran de celui qui choisit la
 * couleur — il se voit dehors, en plein soleil, six mois plus tard.
 *
 * Regle du cadrage §1.1, inchangee : **une couleur identifie une matiere,
 * elle ne hierarchise jamais**. Le rouge de `Danger` sert aux pannes et aux
 * refus — jamais a dire « en retard ».
 */
internal object SaugeJour {
    val Page = Color(0xFFE3E7D4)
    val Card = Color(0xFFFBFBF4)
    val ActiveNav = Color(0xFFDCE8DC)
    val Pill = Color(0xFFE9ECDC)
    val Border = Color(0xFF96A283)
    val BorderActive = Color(0xFF78876F)
    val Accent = Color(0xFF1F5A41)
    val AccentMuted = Color(0xFFDCE8DC)
    val Success = Color(0xFF216747)
    val SuccessMuted = Color(0xFFDEEBDE)
    val Danger = Color(0xFF9B3B22)
    // Assez pale pour que le rouge de `Danger` garde 4,5:1 par-dessus : c'est
    // le fond des messages de panne, celui qu'on lit le plus mal et le plus vite.
    val DangerMuted = Color(0xFFFDF0E7)
    val Secondary = Color(0xFF7A5210)
    val SecondaryMuted = Color(0xFFF6EBD6)
    val TextPrimary = Color(0xFF15251F)
    val TextSecondary = Color(0xFF3A4E44)
    val TextMuted = Color(0xFF50625A)
}

internal object SaugeNuit {
    val Page = Color(0xFF101812)
    val Card = Color(0xFF212E26)
    val ActiveNav = Color(0xFF2C3A2A)
    val Pill = Color(0xFF28352C)
    val Border = Color(0xFF54655A)
    val BorderActive = Color(0xFF6E8074)
    val Accent = Color(0xFFC8EE6E)
    val AccentMuted = Color(0xFF2C3A2A)
    val Success = Color(0xFF6FD09B)
    val SuccessMuted = Color(0xFF16301F)
    val Danger = Color(0xFFFFB59A)
    val DangerMuted = Color(0xFF3B241C)
    val Secondary = Color(0xFFEBC078)
    val SecondaryMuted = Color(0xFF33291A)
    val TextPrimary = Color(0xFFEDF1E9)
    val TextSecondary = Color(0xFFC3CEC1)
    val TextMuted = Color(0xFFA7B1A8)
}

/**
 * Les couleurs de la carte de protection, qui n'obeissent a aucun emplacement
 * Material.
 *
 * C'est le seul endroit de l'application ou une couleur dit un **etat** et non
 * une matiere, et c'est assume : le vert et le brun ne classent rien, ils
 * disent si l'appareil refuse ou s'il a cesse de refuser. Le brun n'est pas
 * une couleur d'alerte gratuite — la suspension **est** un etat anormal, et
 * l'anneau ambre existe pour qu'on ne puisse pas l'oublier en passant.
 */
internal object Protection {
    val ActiveJour = Color(0xFF19533D)
    val ActiveNuit = Color(0xFF0F4B35)
    val PausedJour = Color(0xFF674B3C)
    val PausedNuit = Color(0xFF4E382C)
    val PausedRing = Color(0xFFF2B56C)

    val OnActiveTitle = Color(0xFFF7F8E6)
    val OnActiveBody = Color(0xFFD7E4D5)
    val OnActiveKicker = Color(0xFFC4D5AC)
    val OnActiveLink = Color(0xFFD3DDD0)
    val OnActiveOutline = Color(0xFF8FA88E)

    val OnPausedBody = Color(0xFFFFF0D7)
    val OnPausedKicker = Color(0xFFFFDDA5)

    val ExitBackgroundJour = Color(0xFFFFF3E9)
    val ExitForegroundJour = Color(0xFF8A351D)
    val ExitBorderJour = Color(0xFFA65132)
    val ExitBackgroundNuit = Color(0xFF42271E)
    val ExitForegroundNuit = Color(0xFFFFD6BD)
    val ExitBorderNuit = Color(0xFFB46B4F)
}

/**
 * Les six teintes de matiere, dans l'ordre.
 *
 * Le choix est **stable et derive du libelle**, jamais du rang : deux
 * lancements de l'application donnent la meme couleur a la meme matiere, et
 * ajouter une matiere ne redistribue pas les autres.
 */
internal val SubjectHuesLight = listOf(
    Color(0xFF4A6C92),
    Color(0xFF2F7D6B),
    Color(0xFF6F4E97),
    Color(0xFFA9713A),
    Color(0xFFA44C63),
    Color(0xFF47708F),
)

internal val SubjectHuesDark = listOf(
    Color(0xFF7C6FE0),
    Color(0xFF2A9E70),
    Color(0xFF38BDF8),
    Color(0xFFF5B46A),
    Color(0xFFF28CA5),
    Color(0xFF8FA8D8),
)
