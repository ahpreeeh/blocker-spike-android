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
                "last_work": {
                  "occurred_at": "2026-07-28T13:00:00Z",
                  "unit": { "type": "pages", "from": 47, "to": 62 },
                  "step_id": "stp_3", "resource_id": "res_1"
                },
                "deadline": { "label": "Partiel", "date": "2026-09-15" }
              }
            },
            {
              "step_id": "stp_1", "label": "Pharmaco", "kind": "reading",
              "subject": { "node_id": "nod_pharma", "label": "Pharmacologie" },
              "chapter": null, "resource": null,
              "signals": { "last_activity_at": null, "freshness_days": null, "deadline": null }
            }
          ],
          "nodes": [
            { "node_id": "nod_cardio", "label": "Cardiologie",
              "kind": "subject", "parent_id": null },
            { "node_id": "nod_ic", "label": "Insuffisance cardiaque",
              "kind": "chapter", "parent_id": "nod_cardio" },
            { "node_id": "nod_pharma", "label": "Pharmacologie",
              "kind": "subject", "parent_id": null }
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
        assertEquals(
            LastWork(
                occurredAt = "2026-07-28T13:00:00Z",
                unit = LastWorkUnit.Pages(from = 47, to = 62),
                stepId = "stp_3",
                resourceId = "res_1",
            ),
            premier.signals.lastWork,
        )

        // Absence de trace : `null`, pas zéro. Un écran doit afficher « — ».
        assertNull(second.signals.freshnessDays)
        assertNull(second.signals.lastActivityAt)
        assertNull(second.signals.deadlineDate)
        // Un ancien cache ne porte pas `last_work` et reste donc lisible.
        assertNull(second.signals.lastWork)
    }

    @Test
    fun uneEtapeSurUneMatiereNaPasDeChapitre() {
        val second = QueueSnapshotJson.decode(corps)!!.items[1]

        assertEquals("nod_pharma", second.subject.nodeId)
        assertNull(second.chapter)
        assertNull(second.resource)
    }

    @Test
    fun uneEtapeFutureSansNatureResteLisibleEtLeCacheNOmetPasDeFausseValeur() {
        listOf("", ",\"kind\":null", ",\"kind\":\"   \"").forEach { kindField ->
            val snapshot = QueueSnapshotJson.decode(
                """{"generated_at":"x","items":[{"step_id":"stp_1","label":"ECG"$kindField,"subject":{"node_id":"nod_c","label":"Cardio"},"signals":{}}]}""",
            )!!

            assertNull(snapshot.items.single().kind)
            val encoded = org.json.JSONObject(QueueSnapshotJson.encode(snapshot))
                .getJSONArray("items")
                .getJSONObject(0)
            assertTrue(!encoded.has("kind"))
        }
    }

    @Test
    fun lesNoeudsConserventLOrdrePlatDuServeur() {
        val nodes = QueueSnapshotJson.decode(corps)!!.nodes

        assertEquals(
            listOf("nod_cardio", "nod_ic", "nod_pharma"),
            nodes.map { it.nodeId },
        )
        assertEquals(AcademicNodeKind.SUBJECT, nodes[0].kind)
        assertEquals(AcademicNodeKind.CHAPTER, nodes[1].kind)
        assertEquals("nod_cardio", nodes[1].parentId)
    }

    @Test
    fun unAncienCacheSansNoeudsResteLisible() {
        val snapshot = QueueSnapshotJson.decode(
            """{"generated_at":"x","items":[]}""",
        )

        assertNotNull(snapshot)
        assertTrue(snapshot!!.nodes.isEmpty())
        assertEquals(0, snapshot.skippedNodes)
    }

    @Test
    fun lesNoeudsIllisiblesSontComptesSansRetrierLesAutres() {
        val snapshot = QueueSnapshotJson.decode(
            """{"generated_at":"x","items":[],"nodes":[
                 {"node_id":"nod_chapter","label":"Chapitre","kind":"chapter",
                  "parent_id":"nod_subject"},
                 "pas un objet",
                 {"node_id":"nod_unknown","label":"Inconnu","kind":"future",
                  "parent_id":null},
                 {"node_id":"nod_orphan","label":"Orphelin","kind":"chapter",
                  "parent_id":"nod_absent"},
                 {"node_id":"nod_subject","label":"Matière","kind":"subject",
                  "parent_id":null}
               ]}""",
        )!!

        // Le chapitre précédait sa matière : la validation en deux passages
        // le conserve à sa place au lieu de retrier le tableau.
        assertEquals(listOf("nod_chapter", "nod_subject"), snapshot.nodes.map { it.nodeId })
        assertEquals(3, snapshot.skippedNodes)

        val reread = QueueSnapshotJson.decode(QueueSnapshotJson.encode(snapshot))!!
        assertEquals(snapshot.nodes, reread.nodes)
        assertEquals(3, reread.skippedNodes)
    }

    @Test
    fun unNoeudSansBlocDeProgressionResteAffichable() {
        // `corps` ne porte pas de `progress` : c'est exactement ce qu'un cache
        // écrit par une version antérieure contient. Les nœuds doivent rester
        // lisibles, une progression manquante n'efface pas un chapitre.
        val nodes = QueueSnapshotJson.decode(corps)!!.nodes

        assertEquals(3, nodes.size)
        nodes.forEach { assertEquals(NodeProgress.NONE, it.progress) }
    }

    @Test
    fun lAbsenceDeTraceSurvitAuCacheSansDevenirZero() {
        val snapshot = QueueSnapshotJson.decode(
            """{"generated_at":"x","items":[],"nodes":[
                 {"node_id":"nod_1","label":"Cardiologie","kind":"subject",
                  "parent_id":null,
                  "progress":{"course_studied":true,"revision_count":2,
                              "training_count":null,"error_count":0,
                              "freshness_days":0}}
               ]}""",
        )!!
        val progress = snapshot.nodes.single().progress

        assertTrue(progress.courseStudied)
        assertEquals(2, progress.revisionCount)
        assertNull(progress.trainingCount)
        // Un compte de 0 n'est pas un fait : le serveur n'en envoie pas, et s'il
        // en arrivait un il vaut « aucune trace », pas « zéro entraînement ».
        assertNull(progress.errorCount)
        // Une fraîcheur de 0 jour, elle, est un fait : « déclaré aujourd'hui ».
        assertEquals(0, progress.freshnessDays)

        // L'aller-retour par le cache ne doit rien inventer : un `null` relu
        // reste `null` et ne retombe pas sur une valeur par défaut.
        val reread = QueueSnapshotJson.decode(QueueSnapshotJson.encode(snapshot))!!
        assertEquals(progress, reread.nodes.single().progress)
    }

    @Test
    fun uneEtapeTermineeDescendAvecSaDateEtSurvitAuCache() {
        val snapshot = QueueSnapshotJson.decode(
            """{"generated_at":"x","nodes":[],"items":[
                 {"step_id":"stp_1","label":"ECG","kind":"revision",
                  "subject":{"node_id":"nod_c","label":"Cardio"},
                  "chapter":null,"resource":null,
                  "signals":{"last_activity_at":null,"freshness_days":null,"deadline":null},
                  "completed_at":"2026-08-05T09:12:00Z"},
                 {"step_id":"stp_2","label":"Pharmaco","kind":"reading",
                  "subject":{"node_id":"nod_p","label":"Pharmaco"},
                  "chapter":null,"resource":null,
                  "signals":{"last_activity_at":null,"freshness_days":null,"deadline":null}}
               ]}""",
        )!!

        // La terminée reste dans la liste, à sa place : c'est le parcours qui
        // la grise, ce n'est pas au contrat de la faire disparaître.
        assertEquals(listOf("stp_1", "stp_2"), snapshot.items.map { it.stepId })
        assertEquals("2026-08-05T09:12:00Z", snapshot.items[0].completedAt)
        // Une étape sans le champ — un cache écrit par une version antérieure —
        // vaut « pas terminée », donc affichée normalement.
        assertNull(snapshot.items[1].completedAt)

        val reread = QueueSnapshotJson.decode(QueueSnapshotJson.encode(snapshot))!!
        assertEquals("2026-08-05T09:12:00Z", reread.items[0].completedAt)
        assertNull(reread.items[1].completedAt)
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
        assertEquals(original, relu!!)
        assertTrue(relu.items.first().resource?.openUri == "https://drive.example/x")
    }

    @Test
    fun toutesLesUnitesConnuesSontDecodees() {
        val cases = listOf(
            """{"type":"pages","from":47,"to":62}""" to LastWorkUnit.Pages(47, 62),
            """{"type":"chapter"}""" to LastWorkUnit.Chapter,
            """{"type":"annale","label":"ECN 2019"}""" to
                LastWorkUnit.Annale("ECN 2019"),
            """{"type":"cards","count":80}""" to LastWorkUnit.Cards(80),
            """{"type":"free","label":"schémas"}""" to
                LastWorkUnit.Free("schémas"),
        )

        cases.forEach { (unitJson, expected) ->
            val snapshot = QueueSnapshotJson.decode(snapshotWithLastWorkUnit(unitJson))
            assertEquals(expected, snapshot?.items?.single()?.signals?.lastWork?.unit)
        }
    }

    @Test
    fun uneUniteFutureResteOpaqueMaisSurvitAuCache() {
        val original = QueueSnapshotJson.decode(
            snapshotWithLastWorkUnit(
                """{"type":"video","seconds":90,"details":{"codec":"av1"}}""",
            ),
        )!!
        val unknown = original.items.single().signals.lastWork?.unit
        assertTrue(unknown is LastWorkUnit.Unknown)

        val encoded = QueueSnapshotJson.encode(original)
        val encodedUnit = org.json.JSONObject(encoded)
            .getJSONArray("items")
            .getJSONObject(0)
            .getJSONObject("signals")
            .getJSONObject("last_work")
            .getJSONObject("unit")
        assertEquals("video", encodedUnit.getString("type"))
        assertEquals(90, encodedUnit.getInt("seconds"))
        assertEquals("av1", encodedUnit.getJSONObject("details").getString("codec"))

        val reread = QueueSnapshotJson.decode(encoded)!!
        assertTrue(reread.items.single().signals.lastWork?.unit is LastWorkUnit.Unknown)
    }

    @Test
    fun unDernierTravailMalformeNInvalidePasLEtape() {
        val snapshot = QueueSnapshotJson.decode(
            """{"generated_at":"x","items":[{
                 "step_id":"stp_1","label":"L","kind":"reading",
                 "subject":{"node_id":"nod_1","label":"S"},
                 "signals":{"last_work":{"occurred_at":"2026-07-28T13:00:00Z"}}
               }]}""",
        )

        assertEquals(1, snapshot?.items?.size)
        assertEquals(0, snapshot?.skipped)
        assertNull(snapshot?.items?.single()?.signals?.lastWork)
    }

    @Test
    fun uneUniteConnueInvalideEstConserveeCommeInconnue() {
        val snapshot = QueueSnapshotJson.decode(
            snapshotWithLastWorkUnit("""{"type":"pages","from":62,"to":47}"""),
        )!!

        assertTrue(
            snapshot.items.single().signals.lastWork?.unit is LastWorkUnit.Unknown,
        )
        val encoded = QueueSnapshotJson.encode(snapshot)
        val encodedUnit = org.json.JSONObject(encoded)
            .getJSONArray("items")
            .getJSONObject(0)
            .getJSONObject("signals")
            .getJSONObject("last_work")
            .getJSONObject("unit")
        assertEquals(62, encodedUnit.getInt("from"))
        assertEquals(47, encodedUnit.getInt("to"))
    }

    private fun snapshotWithLastWorkUnit(unitJson: String): String =
        """{"generated_at":"x","items":[{
             "step_id":"stp_1","label":"L","kind":"reading",
             "subject":{"node_id":"nod_1","label":"S"},
             "signals":{"last_work":{
               "occurred_at":"2026-07-28T13:00:00Z",
               "unit":$unitJson,"step_id":"stp_1","resource_id":"res_1"
             }}
           }]}"""
}
