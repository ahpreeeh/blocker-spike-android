package com.albugimed.blockerspike.study

import com.albugimed.blockerspike.sync.PathCommand
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
    /** L'utilisateur l'a cochee. Jamais deduit du travail declare. */
    val done: Boolean,
    /** Rang affiche, terminees comprises : 1, 2, 3… dans l'ordre du parcours. */
    val rank: Int,
    /**
     * Ce que la ligne montre vient d'un geste pas encore confirme par le
     * serveur. L'ecran l'affiche quand meme — le geste est pris, il est en
     * magasin, il partira — mais il le dit.
     */
    val pending: Boolean = false,
)

data class PathView(
    val rows: List<PathRow>,
    val doneCount: Int,
    /**
     * Versements en attente. Ils ne peuvent pas etre appliques ici : les
     * identifiants d'etape sont frappes par le serveur, et inventer des lignes
     * en attendant ferait afficher un parcours que personne n'a.
     */
    val pendingPours: Int = 0,
) {
    val remaining: List<PathRow> get() = rows.filter { !it.done }
}

/**
 * Applique par-dessus la file les gestes qui n'ont pas encore recu leur verdict.
 *
 * Sans cela, cocher une case laisserait l'ecran inchange jusqu'a la prochaine
 * synchronisation reussie — et hors ligne, indefiniment. La file d'attente des
 * gestes tient donc ce role : elle est deja la memoire de ce que l'utilisateur
 * a demande, il suffit de la lire.
 */
fun buildPathView(state: StudyQueueState): PathView {
    val ticks = HashMap<String, Boolean>()
    var reorder: List<String>? = null
    var pours = 0

    // Dans l'ordre de la file : le dernier geste ecrit sur le precedent.
    for (command in state.pendingPathCommands) {
        when (command) {
            is PathCommand.CompleteStep -> ticks[command.stepId] = command.completed
            is PathCommand.ReorderPath -> reorder = command.stepIds
            is PathCommand.PourSubject -> pours += 1
        }
    }

    val ordered = reorder?.let { applyPendingOrder(state.items, it) } ?: state.items

    val rows = ordered.mapIndexed { index, item ->
        val tick = ticks[item.stepId]
        PathRow(
            item = item,
            done = tick ?: (item.completedAt != null),
            rank = index + 1,
            pending = tick != null || (reorder != null && item.stepId in reorder),
        )
    }
    return PathView(
        rows = rows,
        doneCount = rows.count { it.done },
        pendingPours = pours,
    )
}

/**
 * Le meme calcul que `applyExplicitOrder` cote serveur, et il doit le rester :
 * les etapes nommees dans l'ordre donne, puis celles que l'ordre ne connait pas,
 * derriere, a leur place relative. Une etape ajoutee depuis l'atelier pendant
 * qu'on reordonnait hors ligne ne disparait donc pas — elle passe en queue.
 */
private fun applyPendingOrder(items: List<QueueItem>, orderedIds: List<String>): List<QueueItem> {
    val byId = items.associateBy { it.stepId }
    val named = LinkedHashSet(orderedIds).mapNotNull(byId::get)
    val namedIds = named.mapTo(HashSet()) { it.stepId }
    return named + items.filterNot { it.stepId in namedIds }
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
fun pathCountsLabel(view: PathView): String {
    val total = view.rows.size
    return when {
        total == 0 -> "Aucune étape dans le parcours"
        view.doneCount == 0 && total == 1 -> "1 étape, aucune terminée"
        view.doneCount == 0 -> "$total étapes, aucune terminée"
        view.doneCount == 1 -> "1 sur $total terminée"
        else -> "${view.doneCount} sur $total terminées"
    }
}
