package com.albugimed.blockerspike.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.albugimed.blockerspike.ui.theme.LocalAlbugimedExtras

/**
 * Le jeu d'icones, dessine trait par trait.
 *
 * Le contrat §10 interdit d'ajouter une dependance Gradle, ce qui ecarte
 * `material-icons-extended` — et c'est tres bien ainsi : les icones Material
 * ne ressemblent pas a celles de la conception, qui sont toutes tracees au
 * **meme trait de 1,8, bouts et angles arrondis**, sans aucune surface pleine.
 * C'est cette unite de trait, plus que le dessin de chaque glyphe, qui fait
 * que l'ensemble se tient.
 *
 * Les traces sont **les chaines SVG de la conception, copiees telles quelles**,
 * lues par [PathParser] — une classe deja presente dans Compose. Rien n'a ete
 * redessine a la main, donc rien ne peut avoir derive en le redessinant.
 *
 * Tout est exprime dans le carre 24x24 d'origine, puis mis a l'echelle : une
 * icone de 20 dp a donc un trait de 1,5 dp, exactement comme le navigateur
 * l'aurait rendue.
 */
enum class AppIcon(internal val data: String, internal val dotted: Boolean = false) {
    /** Aujourd'hui : quatre cases, la vue d'ensemble. */
    GRID("M4 4h6v6h-6z M14 4h6v6h-6z M4 14h6v6h-6z M14 14h6v6h-6z"),

    /** Blocage : le bouclier de la conception. */
    SHIELD("M12 3 20 6v5c0 5-3.4 8.4-8 10-4.6-1.6-8-5-8-10V6l8-3Z"),

    /** Lectures : un livre ouvert contre sa tranche. */
    BOOK("M4 5a2 2 0 0 1 2-2h14v17H6a2 2 0 0 0-2 2V5Z M4 19a2 2 0 0 1 2-2h14"),

    /** A faire : deux lignes cochees, une qui ne l'est pas. */
    CHECKLIST("m4 7 2 2 3-4 M13 8h7 M4 16l2 2 3-4 M13 17h7"),

    /** Agenda : la grille du mois. */
    CALENDAR("M4 7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7Z M4 10h16 M8 3v4 M16 3v4"),

    /** Notes : la feuille au coin plie. */
    FILE("M6 3h8l4 4v14H6V3Z M14 3v5h5"),

    /** Bibliotheque : le dossier a onglet, au meme trait que le reste. */
    FOLDER("M4 8a2 2 0 0 1 2-2h3l2 2.5h7a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8Z"),

    /** Instruments : trois reglages qu'on pousse. */
    SLIDERS("M5 4v16 M12 4v16 M19 4v16 M3 9h4 M10 14h4 M17 7h4"),

    MENU("M4 7h16M4 12h16M4 17h16"),
    PLUS("M12 5v14M5 12h14"),
    ARROW("m9 18 6-6-6-6"),

    /**
     * Monter et descendre : le meme chevron que [ARROW], au quart de tour
     * pres. Rien de nouveau n'a ete dessine — c'est la meme trace tournee, et
     * elle porte donc le meme trait.
     */
    ARROW_UP("m6 15 6-6 6 6"),
    ARROW_DOWN("m6 9 6 6 6-6"),
    CLOSE("m6 6 12 12M18 6 6 18"),
    CHECK("m5 12 4 4 7-8"),
    PAUSE("M9 5v14M15 5v14"),

    /**
     * Plus : trois points.
     *
     * Ce sont des sous-traces de longueur nulle. Avec un bout arrondi, le
     * moteur de rendu en fait des disques — pas besoin d'un chemin plein,
     * donc pas d'exception au trait unique.
     */
    MORE("M5 12h0M12 12h0M19 12h0", dotted = true),
    ;
}

/**
 * Une icone, a la taille et a la couleur demandees.
 *
 * La couleur par defaut est le gris des faits neutres : une icone qui n'est
 * pas designee comme active ne doit pas crier plus fort que le mot a cote
 * d'elle.
 */
@Composable
fun Glyph(
    icon: AppIcon,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = LocalAlbugimedExtras.current.textMuted,
) {
    // Reparser la chaine a chaque recomposition ferait un objet Path par image
    // pendant les animations du tiroir. C'est le genre d'allocation qu'on ne
    // voit pas en developpement et qu'on sent sur un telephone tenu longtemps.
    val path = remember(icon) { PathParser().parsePathString(icon.data).toPath() }
    Canvas(modifier = modifier.size(size)) {
        scale(this.size.width / 24f, this.size.height / 24f, Offset.Zero) {
            drawPath(
                path = path,
                color = tint,
                style = Stroke(
                    width = if (icon.dotted) 2.2f else 1.8f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}
