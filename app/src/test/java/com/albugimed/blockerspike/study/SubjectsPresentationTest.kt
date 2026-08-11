package com.albugimed.blockerspike.study

import com.albugimed.blockerspike.sync.AcademicNodeKind
import com.albugimed.blockerspike.sync.AcademicNodeRef
import com.albugimed.blockerspike.sync.NodeProgress
import com.albugimed.blockerspike.sync.NodeRef
import com.albugimed.blockerspike.sync.QueueItem
import com.albugimed.blockerspike.sync.QueueSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectsPresentationTest {

    @Test
    fun lOrdreDuServeurEstConserveTelQuel() {
        // Le serveur envoie Pharmacologie avant Cardiologie, et Cardiologie a
        // plus de traces. Rien de tout cela ne doit remonter la liste : l'ordre
        // est celui posé à la main dans l'atelier (P4-02, règles 3 et 4).
        val state = StudyQueueState(
            nodes = listOf(
                subject("nod_pharma", "Pharmacologie"),
                subject("nod_cardio", "Cardiologie", NodeProgress(revisionCount = 4)),
                chapter("nod_ic", "Insuffisance cardiaque", "nod_cardio"),
                chapter("nod_ecg", "ECG", "nod_cardio"),
            ),
        )

        val views = buildSubjectViews(state)

        assertEquals(listOf("nod_pharma", "nod_cardio"), views.map { it.nodeId })
        assertEquals(listOf("nod_ic", "nod_ecg"), views[1].chapters.map { it.nodeId })
        assertTrue(views[0].chapters.isEmpty())
    }

    @Test
    fun laProgressionArriveFaiteEtNEstPasRecalculee() {
        val state = StudyQueueState(
            nodes = listOf(
                subject("nod_cardio", "Cardiologie", NodeProgress(revisionCount = 4)),
                chapter(
                    "nod_ic",
                    "Insuffisance cardiaque",
                    "nod_cardio",
                    NodeProgress(courseStudied = true, revisionCount = 2, freshnessDays = 3),
                ),
            ),
        )

        val subject = buildSubjectViews(state).single()

        assertEquals(4, subject.progress.revisionCount)
        assertEquals(2, subject.chapters.single().progress.revisionCount)
        assertEquals(3, subject.chapters.single().progress.freshnessDays)
    }

    @Test
    fun lAppartenanceAuParcoursVientDesEtapes() {
        val state = StudyQueueState(
            items = listOf(
                item("stp_1", subject = "nod_cardio", chapter = "nod_ic"),
                item("stp_2", subject = "nod_cardio", chapter = "nod_ecg"),
                // Une étape posée sur la matière elle-même : pas de chapitre.
                item("stp_3", subject = "nod_cardio", chapter = null),
            ),
            nodes = listOf(
                subject("nod_cardio", "Cardiologie"),
                chapter("nod_ic", "Insuffisance cardiaque", "nod_cardio"),
                chapter("nod_ecg", "ECG", "nod_cardio"),
                chapter("nod_valves", "Valvulopathies", "nod_cardio"),
            ),
        )

        val subject = buildSubjectViews(state).single()

        assertEquals(3, subject.inPathCount)
        assertTrue(subject.chapters[0].inPath)
        assertTrue(subject.chapters[1].inPath)
        assertFalse(subject.chapters[2].inPath)
    }

    @Test
    fun unChapitreOrphelinNApparaitSousAucuneMatiere() {
        val state = StudyQueueState(
            nodes = listOf(
                subject("nod_cardio", "Cardiologie"),
                chapter("nod_ic", "Insuffisance cardiaque", "nod_cardio"),
                AcademicNodeRef("nod_seul", "Sans parent", AcademicNodeKind.CHAPTER, null),
            ),
        )

        val subject = buildSubjectViews(state).single()

        assertEquals(listOf("nod_ic"), subject.chapters.map { it.nodeId })
    }

    @Test
    fun onNEcritQueCeQuiExiste() {
        // Mot pour mot la règle de l'atelier web : l'absence de trace n'est pas
        // un compte à zéro, et ne s'écrit donc pas « 0 révision ».
        assertEquals("aucune trace", progressHint(NodeProgress.NONE))
        assertEquals(
            "cours · 2 rév. · 1 entr. · 5 err.",
            progressHint(
                NodeProgress(
                    courseStudied = true,
                    revisionCount = 2,
                    trainingCount = 1,
                    errorCount = 5,
                ),
            ),
        )
        // La fraîcheur seule ne fait pas une trace de travail : elle a sa propre
        // colonne et n'entre pas dans la phrase.
        assertEquals("aucune trace", progressHint(NodeProgress(freshnessDays = 3)))
    }

    @Test
    fun laFraicheurEstUnNombreDeJoursPasUneAppreciation() {
        assertEquals("0 j", freshnessLabel(0))
        assertEquals("12 j", freshnessLabel(12))
        // `null` remonte tel quel : c'est l'écran qui rend « — ».
        assertEquals(null, freshnessLabel(null))
    }

    @Test
    fun leDecompteDesChapitresSaccordeEtSeTaitAZero() {
        assertEquals("aucun chapitre", subjectCountsLabel(view(chapters = 0, inPath = 0)))
        assertEquals("1 chapitre", subjectCountsLabel(view(chapters = 1, inPath = 0)))
        assertEquals("18 chapitres", subjectCountsLabel(view(chapters = 18, inPath = 0)))
        assertEquals(
            "18 chapitres · 3 dans le parcours",
            subjectCountsLabel(view(chapters = 18, inPath = 3)),
        )
    }

    private fun subject(
        nodeId: String,
        label: String,
        progress: NodeProgress = NodeProgress.NONE,
    ) = AcademicNodeRef(nodeId, label, AcademicNodeKind.SUBJECT, null, progress)

    private fun chapter(
        nodeId: String,
        label: String,
        parentId: String,
        progress: NodeProgress = NodeProgress.NONE,
    ) = AcademicNodeRef(nodeId, label, AcademicNodeKind.CHAPTER, parentId, progress)

    private fun item(stepId: String, subject: String, chapter: String?) = QueueItem(
        stepId = stepId,
        label = stepId,
        kind = "revision",
        subject = NodeRef(subject, subject),
        chapter = chapter?.let { NodeRef(it, it) },
        resource = null,
        signals = QueueSignals(null, null, null, null),
    )

    private fun view(chapters: Int, inPath: Int) = SubjectView(
        nodeId = "nod_cardio",
        label = "Cardiologie",
        progress = NodeProgress.NONE,
        chapters = List(chapters) {
            ChapterView("nod_$it", "Chapitre $it", NodeProgress.NONE, inPath = false)
        },
        inPathCount = inPath,
    )
}
