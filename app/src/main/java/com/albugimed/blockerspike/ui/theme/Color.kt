package com.albugimed.blockerspike.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * La palette, reglee pour un telephone tenu a bout de bras.
 *
 * Elle vient de la conception rendue : une **foret verte sur papier clair**,
 * ponctuee d'un citron vif. La famille sauge et olive qu'elle remplace disait
 * la meme chose plus timidement — meme temperature, moins d'aplomb.
 *
 * Ce qui n'a pas change, c'est la methode : **chaque valeur est mesuree avant
 * d'etre ecrite**, jamais choisie a l'oeil. Les seuils tenus ici :
 *
 * - **texte : 4,5:1** — la regle WCAG, sans exception, y compris sur le vert
 *   profond de la carte de blocage ;
 * - **contours de controle : 3:1** — bouton, anneau d'alerte, pastille ;
 * - **liseres de carte : remplissage ≥ 1,15 et lisere ≥ 2,0**, et non 3:1 :
 *   un lisere ne peut pas atteindre 3:1 contre la page *et* contre la carte
 *   quand les deux sont proches.
 *
 * **Deux valeurs de la conception ont ete refusees, et il faut savoir
 * lesquelles.** La page `#F7F9F6` et la carte blanche ne se separent que de
 * **1,06** — sous ce seuil il n'y a plus de cartes, juste une colonne, et
 * c'etait exactement le defaut signale sur la version precedente. La page est
 * donc verdie d'un cran ([Page] = `#E8EEE4`, ecart **1,18**). De meme, le
 * lisere `#D9E2DA` de la conception ne vaut que **1,33** contre le blanc : il
 * est remplace par [Border], mesure a **2,68**. L'oeil du navigateur pardonne
 * ce que le soleil de midi ne pardonne pas.
 *
 * Regle du cadrage §1.1, inchangee : **une couleur identifie une matiere,
 * elle ne hierarchise jamais**. Le rouge de `Danger` sert aux pannes et aux
 * refus — jamais a dire « en retard ».
 */
internal object ForetJour {
    val Page = Color(0xFFE8EEE4)
    val Card = Color(0xFFFFFFFF)
    val ActiveNav = Color(0xFFDCE8D6)
    val Pill = Color(0xFFE4EBDE)
    val Border = Color(0xFF8FA394)
    val BorderActive = Color(0xFF6F8570)
    // Le vert des pilules de la conception. Le citron se pose dessus a 6,9:1.
    val Accent = Color(0xFF205C40)
    val AccentMuted = Color(0xFFDCE8D6)
    val Success = Color(0xFF1F6B4A)
    val SuccessMuted = Color(0xFFDDEBDF)
    val Danger = Color(0xFF9B3B22)
    // Assez pale pour que le rouge de `Danger` garde 4,5:1 par-dessus : c'est
    // le fond des messages de panne, celui qu'on lit le plus mal et le plus vite.
    val DangerMuted = Color(0xFFFDF0E7)
    val Secondary = Color(0xFF7A5210)
    val SecondaryMuted = Color(0xFFF6EBD6)
    val TextPrimary = Color(0xFF13231C)
    val TextSecondary = Color(0xFF374C41)
    val TextMuted = Color(0xFF50625A)
}

/**
 * La nuit de la conception, prise telle quelle : `#122019` pour la page,
 * `#203028` pour les cartes. Seuls les liseres sont eclaircis — `#3B4D41` ne
 * separe la carte du fond qu'a **1,53**, sous le seuil de 2,0.
 */
internal object ForetNuit {
    val Page = Color(0xFF122019)
    val Card = Color(0xFF203028)
    val ActiveNav = Color(0xFF2B3F31)
    val Pill = Color(0xFF26362D)
    val Border = Color(0xFF4C6154)
    val BorderActive = Color(0xFF6B8574)
    val Accent = Color(0xFFCEF16E)
    val AccentMuted = Color(0xFF2B3F31)
    val Success = Color(0xFF7BD3A2)
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
 * Le vert du tiroir et de la carte de blocage, qui n'obeissent a aucun
 * emplacement Material.
 *
 * C'est le seul endroit de l'application ou une couleur dit un **etat** et non
 * une matiere, et c'est assume : le vert et le brun ne classent rien, ils
 * disent si l'appareil refuse ou s'il a cesse de refuser. Le brun n'est pas
 * une couleur d'alerte gratuite — la suspension **est** un etat anormal, et
 * l'anneau ambre existe pour qu'on ne puisse pas l'oublier en passant.
 *
 * Les deux verts viennent de la conception : `#19392B` pour le tiroir,
 * `#194D36` pour la carte. Le tiroir est le plus sombre des deux — il passe
 * par-dessus tout le reste, et doit se lire comme un plan different.
 */
internal object Protection {
    val ActiveJour = Color(0xFF194D36)
    val ActiveNuit = Color(0xFF0F3627)
    val PausedJour = Color(0xFF5E4536)
    val PausedNuit = Color(0xFF46322A)
    val PausedRing = Color(0xFFF2B56C)

    /** Le vert du tiroir, identique de jour comme de nuit : c'est un plan, pas une surface. */
    val Drawer = Color(0xFF19392B)
    val DrawerActive = Color(0xFF24513C)

    val OnActiveTitle = Color(0xFFF7F9EC)
    val OnActiveBody = Color(0xFFD7E4D5)
    val OnActiveKicker = Color(0xFFCEF16E)
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

/** Le citron, en trois intensites, comme dans la conception. */
internal object Citron {
    val Vif = Color(0xFFCEF16E)
    val Clair = Color(0xFFE6F9AC)
}

/**
 * Les six teintes de matiere, dans l'ordre.
 *
 * Le choix est **stable et derive du libelle**, jamais du rang : deux
 * lancements de l'application donnent la meme couleur a la meme matiere, et
 * ajouter une matiere ne redistribue pas les autres.
 *
 * Les trois pastilles de la conception (`#A994CF`, `#D88758`, `#63A7BD`) ne
 * sont pas reprises telles quelles : a ce niveau de clarte elles tombent a
 * **2,6:1** sur une carte blanche, sous le seuil des 3:1 des elements qui
 * portent un sens. Ce sont leurs versions assombries qui sont ici.
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

/**
 * Les fonds de couverture d'un document, repris de la conception.
 *
 * Ils portent du texte blanc et rien d'autre — ils **identifient** la matiere
 * du document, exactement comme la pastille, et ne disent jamais un rang. Le
 * meme libelle donne toujours la meme couverture.
 */
internal val CoverHues = listOf(
    Color(0xFF3F7157),
    Color(0xFFAF6845),
    Color(0xFF487C94),
    Color(0xFF5E5487),
    Color(0xFF8A5B6E),
    Color(0xFF4A6C92),
)
