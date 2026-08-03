package com.albugimed.blockerspike.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSnapshotJsonTest {

    private val corps = """
        {
          "generated_at": "2026-07-31T13:00:00.000Z",
          "device_id": "poco-x7",
          "items": [
            {
              "step_id": "stp_3", "label": "Insuffisance cardiaque", "kind": "revision",
              "subject": { "node_id": "nod_cardio", "label": "Cardiologie" },
              "chapter": { "node_id": "nod_ic", "label": "Insuffisance cardiaque" },
              "resource": {
                "resource_id": "res_1", "label": "Polycopié", "type": "pdf_drive",
                "open_uri": "https://drive.example/x"
              },
              "signals": {
                "last_activity_at": "2026-07-20", "freshness_days": 11,
                "deadline": { "label": "Partiel", "date": "2026-09-15" }
              }
            },
            {
              "step_id": "stp_1", "label": "Pharmaco", "kind": "reading",
              "subject": { "node_id": "nod_pharma", "label": "Pharmacologie" },
              "chapter": null, "resource": null,
              "signals": { "last_activity_at": null, "freshness_days": null, "deadline": null }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun lOrdreRecuEstConserveTelQuel() {
        val snapshot = QueueSnapshotJson.decode(corps)

        // L'ordre est celui posé à la main dans l'atelier. Le téléphone ne le
        // retrie sous aucun prétexte : ni par échéance, ni par fraîcheur.
        // `stp_3` est en tête bien qu'il porte l'échéance et le plus grand
        // nombre de jours sans activité.
        assertEquals(listOf("stp_3", "stp_1"), snapshot?.items?.map { it.stepId })
    }

    @Test
    fun lesSignauxSontDesFaitsPasDesScores() {
        val premier = QueueSnapshotJson.decode(corps)!!.items[0]
        val second = QueueSnapshotJson.decode(corps)!!.items[1]

        assertEquals(11, premier.signals.freshnessDays)
        assertEquals("2026-09-15", premier.signals.deadlineDate)

        // Absence de trace : `null`, pas zéro. Un écran doit afficher « — ».
        assertNull(second.signals.freshnessDays)
        assertNull(second.signals.lastActivityAt)
        assertNull(second.signals.deadlineDate)
    }

    @Test
    fun uneEtapeSurUneMatiereNaPasDeChapitre() {
        val second = QueueSnapshotJson.decode(corps)!!.items[1]

        assertEquals("nod_pharma", second.subject.nodeId)
        assertNull(second.chapter)
        assertNull(second.resource)
    }

    @Test
    fun lIdentifiantDAppareilSeLitDansLaMemeReponse() {
        assertEquals("poco-x7", QueueSnapshotJson.deviceIdOf(corps))
        assertNull(QueueSnapshotJson.deviceIdOf("""{"items":[]}"""))
    }

    @Test
    fun uneAdresseEcarteeParLeServeurNEstPasReconstruite() {
        val snapshot = QueueSnapshotJson.decode(
            """{"generated_at":"x","items":[{
                 "step_id":"stp_1","label":"L","kind":"reading",
                 "subject":{"node_id":"nod_1","label":"S"},
                 "resource":{"resource_id":"res_1","label":"Livre","type":"book"},
                 "signals":{}
               }]}"""
        )

        assertNull(snapshot!!.items[0].resource?.openUri)
        assertEquals("Livre", snapshot.items[0].resource?.label)
    }

    @Test
    fun uneEtapeIllisibleEstComptee_pasSilencieusementAvalee() {
        val snapshot = QueueSnapshotJson.decode(
            """{"generated_at":"x","items":[
                 {"pas":"une etape"},
                 {"step_id":"stp_1","label":"L","kind":"reading",
                  "subject":{"node_id":"nod_1","label":"S"},"signals":{}}
               ]}"""
        )

        assertEquals(1, snapshot?.items?.size)
        assertEquals(1, snapshot?.skipped)
    }

    @Test
    fun uneReponseIllisibleNEcrasePasLaCopieLocale() {
        // `null` fait que l'appelant ne remplace rien : la file précédente
        // reste consultable hors ligne.
        assertNull(QueueSnapshotJson.decode("pas du json"))
        assertNull(QueueSnapshotJson.decode("""{"generated_at":"x"}"""))
    }

    @Test
    fun laCopieLocaleFaitUnAllerRetourFidele() {
        val original = QueueSnapshotJson.decode(corps)!!
        val relu = QueueSnapshotJson.decode(QueueSnapshotJson.encode(original))

        assertNotNull(relu)
        assertEquals(original.items, relu!!.items)
        assertTrue(relu.items.first().resource?.openUri == "https://drive.example/x")
    }
}
