package com.albugimed.blockerspike.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ce que la file de gestes garde, et ce qu'elle laisse tomber.
 *
 * Un geste de parcours n'est pas une trace : cocher puis décocher n'a pas à
 * traverser le réseau deux fois, et deux ordres successifs ne racontent rien —
 * seul le dernier dit quelque chose. Sans ce repli, une matinée passée à
 * réordonner hors ligne partirait en cinquante commandes dont quarante-neuf
 * seraient à défaire.
 */
class PathCommandCollapseTest {

    @Test
    fun cocherPuisDecocherNeLaisseQueLeDernierEtat() {
        val coche = PathCommand.CompleteStep("cmd_1", "stp_1", true, MOMENT)
        val decoche = PathCommand.CompleteStep("cmd_2", "stp_1", false, MOMENT)

        val file = collapsePathCommands(collapsePathCommands(emptyList(), coche), decoche)

        assertEquals(listOf(decoche), file)
    }

    @Test
    fun deuxEtapesDifferentesNeSeMangentPas() {
        val premiere = PathCommand.CompleteStep("cmd_1", "stp_1", true, MOMENT)
        val seconde = PathCommand.CompleteStep("cmd_2", "stp_2", true, MOMENT)

        val file = collapsePathCommands(collapsePathCommands(emptyList(), premiere), seconde)

        assertEquals(listOf(premiere, seconde), file)
    }

    @Test
    fun leDernierOrdreEffaceTousLesPrecedents() {
        // Un ordre est absolu : rejoués dans le désordre, deux ordres
        // remettraient le parcours dans un état que personne n'a demandé.
        val premier = PathCommand.ReorderPath("cmd_1", listOf("stp_1", "stp_2"))
        val second = PathCommand.ReorderPath("cmd_2", listOf("stp_2", "stp_1"))

        val file = collapsePathCommands(collapsePathCommands(emptyList(), premier), second)

        assertEquals(listOf(second), file)
    }

    @Test
    fun verserDeuxFoisLaMemeMatiereNeCompteQuUneFois() {
        val premierVersement = PathCommand.PourSubject("cmd_1", "nod_cardio")
        val encore = PathCommand.PourSubject("cmd_2", "nod_cardio")
        // Une autre matière est un autre versement et doit donc survivre.
        val autreMatiere = PathCommand.PourSubject("cmd_3", "nod_pharma")

        var file = collapsePathCommands(emptyList(), premierVersement)
        file = collapsePathCommands(file, encore)
        file = collapsePathCommands(file, autreMatiere)

        assertEquals(listOf(encore, autreMatiere), file)
    }

    @Test
    fun uneEntreeInconnueGardeSaPlaceLorsDUnAjout() {
        val premier = PathCommand.PourSubject("cmd_1", "nod_cardio")
        val second = PathCommand.PourSubject("cmd_2", "nod_pharma")
        val nouveau = PathCommand.ReorderPath("cmd_3", listOf("stp_2", "stp_1"))
        val futureCommand = """{"command_id":"future_1","type":"future_command"}"""
        val raw = listOf(
            PathCommandJson.encodeToString(premier),
            futureCommand,
            PathCommandJson.encodeToString(second),
        )

        val merged = mergeSerializedPathCommands(raw, listOf(premier, second, nouveau))

        assertEquals(PathCommandJson.encodeToString(premier), merged[0])
        assertEquals(futureCommand, merged[1])
        assertEquals(PathCommandJson.encodeToString(second), merged[2])
        assertEquals(PathCommandJson.encodeToString(nouveau), merged[3])
    }

    @Test
    fun lOrdreDesGestesRestantsEstCeluiDesGestes() {
        // Verser puis réordonner n'est pas réordonner puis verser : le second
        // ordre ne connaît pas les étapes que le versement vient de créer. La
        // file est donc une liste, jamais un ensemble.
        val coche = PathCommand.CompleteStep("cmd_1", "stp_1", true, MOMENT)
        val versement = PathCommand.PourSubject("cmd_2", "nod_cardio")
        val ordre = PathCommand.ReorderPath("cmd_3", listOf("stp_2", "stp_1"))

        var file = collapsePathCommands(emptyList(), coche)
        file = collapsePathCommands(file, versement)
        file = collapsePathCommands(file, ordre)

        assertEquals(listOf("cmd_1", "cmd_2", "cmd_3"), file.map { it.commandId })

        // Recocher la même étape la fait passer en queue : elle est redemandée
        // maintenant, donc après le reste.
        val recoche = PathCommand.CompleteStep("cmd_4", "stp_1", false, MOMENT)
        assertEquals(
            listOf("cmd_2", "cmd_3", "cmd_4"),
            collapsePathCommands(file, recoche).map { it.commandId },
        )
    }

    private companion object {
        const val MOMENT = "2026-08-12T09:00:00+02:00"
    }
}
