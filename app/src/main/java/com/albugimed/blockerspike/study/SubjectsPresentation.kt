package com.albugimed.blockerspike.study

import com.albugimed.blockerspike.sync.AcademicNodeKind
import com.albugimed.blockerspike.sync.AcademicNodeRef
import com.albugimed.blockerspike.sync.NodeProgress

/**
 * Ce que l'ecran Matieres lit, calcule sans Android et donc testable seul.
 *
 * Tout vient du referentiel deja transporte par `/queue` : le telephone le
 * recevait et le jetait. Rien n'est demande au serveur en plus, rien n'est
 * recalcule ici — les cinq dimensions arrivent faites, et ce fichier ne fait
 * que les mettre en mots.
 *
 * **Aucun tri.** L'ordre des matieres et des chapitres est celui du serveur,
 * c'est-a-dire celui pose a la main dans l'atelier. Reordonner ici, meme
 * « pour mieux voir », ferait dire a l'application ce qu'elle n'a pas a dire
 * (P4-02, regles 3 et 4).
 */
data class SubjectView(
    val nodeId: String,
    val label: String,
    val progress: NodeProgress,
    val chapters: List<ChapterView>,
    /** Nombre d'etapes de cette matiere presentes dans le parcours. */
    val inPathCount: Int,
)

data class ChapterView(
    val nodeId: String,
    val label: String,
    val progress: NodeProgress,
    val inPath: Boolean,
)

fun buildSubjectViews(state: StudyQueueState): List<SubjectView> {
    val pathNodeIds = state.items
        .mapTo(mutableSetOf()) { item -> item.chapter?.nodeId ?: item.subject.nodeId }
    val stepsBySubject = state.items.groupingBy { it.subject.nodeId }.eachCount()

    // Un seul passage sur les chapitres : la liste peut compter plusieurs
    // centaines d'entrees, et un `filter` par matiere la relirait autant de
    // fois qu'il y a de matieres.
    val chaptersBySubject = LinkedHashMap<String, MutableList<AcademicNodeRef>>()
    for (node in state.nodes) {
        if (node.kind != AcademicNodeKind.CHAPTER) continue
        val parentId = node.parentId ?: continue
        chaptersBySubject.getOrPut(parentId) { mutableListOf() }.add(node)
    }

    return state.nodes
        .filter { it.kind == AcademicNodeKind.SUBJECT }
        .map { subject ->
            SubjectView(
                nodeId = subject.nodeId,
                label = subject.label,
                progress = subject.progress,
                chapters = chaptersBySubject[subject.nodeId].orEmpty().map { chapter ->
                    ChapterView(
                        nodeId = chapter.nodeId,
                        label = chapter.label,
                        progress = chapter.progress,
                        inPath = chapter.nodeId in pathNodeIds,
                    )
                },
                inPathCount = stepsBySubject[subject.nodeId] ?: 0,
            )
        }
}

/**
 * Ce qu'on lit d'un noeud en une ligne etroite.
 *
 * Mot pour mot la regle de l'atelier web (`chapterHint`) : **on n'ecrit que ce
 * qui existe**. « 0 revision » est un chiffre faux — l'absence de trace n'est
 * pas un compte a zero. Les deux surfaces doivent dire la meme chose du meme
 * chapitre, sinon la synchronisation ne veut plus rien dire.
 */
fun progressHint(progress: NodeProgress): String {
    val parts = buildList {
        if (progress.courseStudied) add("cours")
        progress.revisionCount?.let { add("$it rév.") }
        progress.trainingCount?.let { add("$it entr.") }
        progress.errorCount?.let { add("$it err.") }
    }
    return if (parts.isEmpty()) "aucune trace" else parts.joinToString(" · ")
}

/**
 * La fraicheur, dans l'unite de l'atelier web : un nombre de jours, pas une
 * appreciation. « 12 j » est un fait ; « il y a longtemps » serait un jugement.
 *
 * `null` remonte tel quel pour que l'appelant rende « — ».
 */
fun freshnessLabel(days: Int?): String? = days?.let { "$it j" }

/** « 18 chapitres · 3 dans le parcours ». La seconde moitie disparait a zero. */
fun subjectCountsLabel(subject: SubjectView): String {
    val chapters = when (subject.chapters.size) {
        0 -> "aucun chapitre"
        1 -> "1 chapitre"
        else -> "${subject.chapters.size} chapitres"
    }
    return if (subject.inPathCount > 0) {
        "$chapters · ${subject.inPathCount} dans le parcours"
    } else {
        chapters
    }
}
