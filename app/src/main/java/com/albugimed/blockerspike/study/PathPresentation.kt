package com.albugimed.blockerspike.study

import com.albugimed.blockerspike.sync.QueueItem

/**
 * Ce que les deux vues du parcours lisent, calcule sans Android.
 *
 * Le parcours **est** la file : les memes etapes, le meme ordre, la meme
 * position posee a la main dans l'atelier. Il n'y a pas de seconde liste, et
 * c'est deliberement ainsi — deux listes a tenir a jour finiraient par
 * diverger, et il faudrait alors dire laquelle est le vrai programme.
 *
 * Ce qui change par rapport a l'ancienne page « A faire », c'est ce qu'on en
 * montre : les etapes terminees ne disparaissent plus, elles restent a leur
 * place, grisees. Voir ce qu'on a franchi est la moitie de ce qu'un parcours
 * sert a montrer, et une liste qui se vide en avancant efface cette moitie-la.
 */
data class PathRow(
    val item: QueueItem,
    /** L'utilisateur l'a cochee dans l'atelier. Jamais deduit du travail declare. */
    val done: Boolean,
    /** Rang affiche, terminees comprises : 1, 2, 3… dans l'ordre de l'atelier. */
    val rank: Int,
)

data class PathView(
    val rows: List<PathRow>,
    val doneCount: Int,
) {
    val remaining: List<PathRow> get() = rows.filter { !it.done }
}

fun buildPathView(state: StudyQueueState): PathView {
    val rows = state.items.mapIndexed { index, item ->
        PathRow(item = item, done = item.completedAt != null, rank = index + 1)
    }
    return PathView(rows = rows, doneCount = rows.count { it.done })
}

/**
 * La vue courte : les prochaines etapes, et rien d'autre.
 *
 * Elle saute les terminees — sur un accueil, la question est « et maintenant »,
 * pas « qu'ai-je fait ». La page dediee, elle, montre tout.
 *
 * **Aucun tri.** On prend les [count] premieres dans l'ordre du parcours. Les
 * remonter par echeance ou par fraicheur ferait choisir l'application a la
 * place de l'utilisateur, ce que le cadrage §1.1 lui interdit.
 */
fun nextRows(view: PathView, count: Int): List<PathRow> =
    view.remaining.take(count)

/**
 * « 3 sur 18 » — ce qui est franchi sur ce qui est pose.
 *
 * Deux nombres et pas un pourcentage : 3 sur 18 se verifie a l'oeil sur la
 * liste juste en dessous, la ou « 17 % » demande de faire confiance a un calcul
 * qu'on ne voit pas. Le cadrage §1.2 interdit de resumer une progression a un
 * nombre unique, et c'est exactement ce cas.
 */
fun pathCountsLabel(view: PathView): String = when {
    view.rows.isEmpty() -> "Aucune étape dans le parcours"
    view.doneCount == 0 -> "${view.rows.size} étapes, aucune terminée"
    else -> "${view.doneCount} sur ${view.rows.size} terminées"
}
