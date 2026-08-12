package com.albugimed.blockerspike.study

import com.albugimed.blockerspike.sync.NodeRef
import com.albugimed.blockerspike.sync.PathCommand
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
        // « terminée » s'accorde avec le premier nombre — une étape terminée,
        // pas trois. Ce test attendait le pluriel, et l'écran l'affichait.
        assertEquals("1 sur 3 terminée", pathCountsLabel(enCours))
    }

    @Test
    fun leCompteSaccordeAussiQuandIlNyEnAQuUne() {
        // « 1 étapes, aucune terminée » se lisait sur l'écran d'un parcours qui
        // démarre — le premier chiffre que l'application ait jamais montré.
        assertEquals("1 étape, aucune terminée", pathCountsLabel(buildPathView(state(item("s1")))))
        assertEquals(
            "1 sur 1 terminée",
            pathCountsLabel(buildPathView(state(item("s1", completedAt = "2026-08-01T10:00:00Z")))),
        )
    }

    @Test
    fun uneCocheEnAttenteSeVoitAvantMemeDePartir() {
        // Sans cette couche, cocher hors ligne ne changerait rien à l'écran
        // jusqu'au retour du réseau — c'est-à-dire, parfois, jamais.
        val view = buildPathView(
            state(item("s1"), item("s2")).copy(
                pendingPathCommands = listOf(
                    PathCommand.CompleteStep("cmd_1", "s1", true, MOMENT),
                ),
            ),
        )

        assertEquals(listOf(true, false), view.rows.map { it.done })
        assertEquals(listOf(true, false), view.rows.map { it.pending })
        assertEquals(1, view.doneCount)
    }

    @Test
    fun leDernierGesteEnAttenteEcritSurLePrecedent() {
        val view = buildPathView(
            state(item("s1", completedAt = "2026-08-01T10:00:00Z")).copy(
                pendingPathCommands = listOf(
                    PathCommand.CompleteStep("cmd_1", "s1", true, MOMENT),
                    // Décochée juste après : c'est ce dernier geste qui compte,
                    // et il doit pouvoir défaire la date reçue du serveur.
                    PathCommand.CompleteStep("cmd_2", "s1", false, MOMENT),
                ),
            ),
        )

        assertFalse(view.rows.single().done)
        assertEquals(0, view.doneCount)
    }

    @Test
    fun unOrdreEnAttenteEstDejaCeluiQuOnVoit() {
        val view = buildPathView(
            state(item("s1"), item("s2"), item("s3")).copy(
                pendingPathCommands = listOf(
                    PathCommand.ReorderPath("cmd_1", listOf("s3", "s1")),
                ),
            ),
        )

        // « s2 » n'est pas nommée par l'ordre — même règle que le serveur : elle
        // passe derrière, sans disparaître.
        assertEquals(listOf("s3", "s1", "s2"), view.rows.map { it.item.stepId })
        assertEquals(listOf(1, 2, 3), view.rows.map { it.rank })
        assertEquals(listOf(true, true, false), view.rows.map { it.pending })
    }

    @Test
    fun unVersementEnAttenteSeDitEtNeSeMontrePas() {
        // Les identifiants d'étape sont frappés par l'atelier : inventer des
        // lignes en attendant montrerait un parcours que personne n'a.
        val view = buildPathView(
            state(item("s1")).copy(
                pendingPathCommands = listOf(
                    PathCommand.PourSubject("cmd_1", "nod_cardio", "revision"),
                    PathCommand.PourSubject("cmd_2", "nod_pharma", "reading"),
                ),
            ),
        )

        assertEquals(1, view.rows.size)
        assertEquals(2, view.pendingPours)
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

    private companion object {
        const val MOMENT = "2026-08-12T09:00:00+02:00"
    }
}
