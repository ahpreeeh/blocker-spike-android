package com.albugimed.blockerspike.study

import com.albugimed.blockerspike.sync.NodeRef
import com.albugimed.blockerspike.sync.QueueItem
import com.albugimed.blockerspike.sync.QueueSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathPresentationTest {

    @Test
    fun lOrdreDuParcoursEstCeluiDuServeurTerminesCompris() {
        // Les deux terminées sont au milieu. Elles y restent : remonter les
        // restantes en tête ferait un tri, et l'application n'en fait aucun
        // (cadrage §1.1).
        val view = buildPathView(
            state(
                item("s1"),
                item("s2", completedAt = "2026-08-01T10:00:00Z"),
                item("s3", completedAt = "2026-08-02T10:00:00Z"),
                item("s4"),
            ),
        )

        assertEquals(listOf("s1", "s2", "s3", "s4"), view.rows.map { it.item.stepId })
        assertEquals(listOf(1, 2, 3, 4), view.rows.map { it.rank })
        assertEquals(listOf(false, true, true, false), view.rows.map { it.done })
        assertEquals(2, view.doneCount)
    }

    @Test
    fun uneEtapeTermineeNEstJamaisDeduiteDuTravailDeclare() {
        // Trois révisions et une trace fraîche : rien de tout cela ne coche.
        // Seule la date posée par l'utilisateur dans l'atelier grise une étape.
        val travaillee = item("s1").copy(
            signals = QueueSignals("2026-08-10T09:00:00Z", 1, null, null),
        )

        val view = buildPathView(state(travaillee))

        assertFalse(view.rows.single().done)
        assertEquals(0, view.doneCount)
    }

    @Test
    fun laVueCourteSauteLesTermineesEtSArreteAuNombreDemande() {
        val view = buildPathView(
            state(
                item("s1", completedAt = "2026-08-01T10:00:00Z"),
                item("s2"),
                item("s3"),
                item("s4"),
            ),
        )

        assertEquals(listOf("s2", "s3"), nextRows(view, 2).map { it.item.stepId })
        // Le rang affiché reste celui du parcours entier : « 2 » et non « 1 ».
        assertEquals(listOf(2, 3), nextRows(view, 2).map { it.rank })
    }

    @Test
    fun laVueCourteNeSePlaintPasQuandLeParcoursEstPlusCourt() {
        val view = buildPathView(state(item("s1")))

        assertEquals(1, nextRows(view, 4).size)
        assertTrue(nextRows(buildPathView(state()), 4).isEmpty())
    }

    @Test
    fun leCompteSeDitEnDeuxNombresJamaisEnPourcentage() {
        assertEquals("Aucune étape dans le parcours", pathCountsLabel(buildPathView(state())))

        val aucuneFaite = buildPathView(state(item("s1"), item("s2")))
        assertEquals("2 étapes, aucune terminée", pathCountsLabel(aucuneFaite))

        val enCours = buildPathView(
            state(item("s1", completedAt = "2026-08-01T10:00:00Z"), item("s2"), item("s3")),
        )
        assertEquals("1 sur 3 terminées", pathCountsLabel(enCours))
    }

    @Test
    fun toutTermineResteVisibleEtLaVueCourteSeTait() {
        val view = buildPathView(
            state(
                item("s1", completedAt = "2026-08-01T10:00:00Z"),
                item("s2", completedAt = "2026-08-02T10:00:00Z"),
            ),
        )

        // La page garde les deux lignes — voir ce qu'on a franchi est la moitié
        // de ce qu'un parcours sert à montrer.
        assertEquals(2, view.rows.size)
        assertTrue(view.remaining.isEmpty())
        assertTrue(nextRows(view, 3).isEmpty())
        assertEquals("2 sur 2 terminées", pathCountsLabel(view))
    }

    private fun state(vararg items: QueueItem) = StudyQueueState(items = items.toList())

    private fun item(stepId: String, completedAt: String? = null) = QueueItem(
        stepId = stepId,
        label = stepId,
        kind = "revision",
        subject = NodeRef("nod_cardio", "Cardiologie"),
        chapter = null,
        resource = null,
        signals = QueueSignals(null, null, null, null),
        completedAt = completedAt,
    )
}
