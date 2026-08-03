package com.albugimed.blockerspike.study

import com.albugimed.blockerspike.sync.ActivityUnit
import com.albugimed.blockerspike.sync.Difficulty
import com.albugimed.blockerspike.sync.NodeRef
import com.albugimed.blockerspike.sync.QueueItem
import com.albugimed.blockerspike.sync.QueueSignals
import com.albugimed.blockerspike.sync.StudyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class ActivityDeclarationTest {
    private val occurredAt = OffsetDateTime.parse("2026-07-31T15:42:00+02:00")
    private val item = QueueItem(
        stepId = "stp_01JZQK3M8F2W",
        label = "Insuffisance cardiaque — relecture",
        kind = "revision",
        subject = NodeRef("nod_subject", "Cardiologie"),
        chapter = NodeRef("nod_chapter", "Insuffisance cardiaque"),
        resource = null,
        signals = QueueSignals(null, null, null, null),
    )

    @Test
    fun `builds the activity payload from the declaration form`() {
        val result = buildActivityDeclaration(
            item = item,
            form = validForm(
                pagesFrom = " 47 ",
                pagesTo = "62",
                note = "  revoir les critères de Framingham  ",
            ),
            occurredAt = occurredAt,
        ).requireValid()

        assertEquals("nod_chapter", result.nodeId)
        assertEquals("stp_01JZQK3M8F2W", result.stepId)
        assertEquals(35, result.durationMinutes)
        assertEquals(ActivityUnit.Pages(from = 47, to = 62), result.unit)
        assertEquals(Difficulty.HARD, result.difficulty)
        assertEquals("revoir les critères de Framingham", result.note)
        assertEquals(occurredAt, result.occurredAt)
    }

    @Test
    fun `falls back to the subject node when the queue item has no chapter`() {
        val subjectOnly = item.copy(chapter = null)

        val result = buildActivityDeclaration(
            item = subjectOnly,
            form = validForm(unitType = WorkUnitType.CHAPTER),
            occurredAt = occurredAt,
        ).requireValid()

        assertEquals("nod_subject", result.nodeId)
        assertEquals(ActivityUnit.Chapter, result.unit)
        assertNull(result.note)
    }

    @Test
    fun `builds every unit shape expected by the phone contract`() {
        val cases = listOf(
            validForm(unitType = WorkUnitType.CHAPTER) to ActivityUnit.Chapter,
            validForm(
                unitType = WorkUnitType.ANNALE,
                annaleLabel = " ECN 2019 — dossier 3 ",
            ) to ActivityUnit.Annale("ECN 2019 — dossier 3"),
            validForm(unitType = WorkUnitType.CARDS, cardsCount = "80") to
                ActivityUnit.Cards(80),
            validForm(unitType = WorkUnitType.FREE, freeLabel = " schémas ") to
                ActivityUnit.Free("schémas"),
        )

        cases.forEach { (form, expectedUnit) ->
            val declaration = buildActivityDeclaration(item, form, occurredAt).requireValid()
            assertEquals(expectedUnit, declaration.unit)
        }
    }

    @Test
    fun `reports all invalid required values without building a payload`() {
        val result = buildActivityDeclaration(
            item = item,
            form = DeclareFormState(
                durationMinutes = "0",
                unitType = WorkUnitType.PAGES,
                pagesFrom = "62",
                pagesTo = "47",
                difficulty = null,
                note = "x".repeat(MAX_NOTE_LENGTH + 1),
            ),
            occurredAt = occurredAt,
        )

        assertTrue(result is DeclarationBuildResult.Invalid)
        val errors = (result as DeclarationBuildResult.Invalid).errors
        assertEquals(4, errors.size)
        assertTrue(errors.any { it.contains("durée positive") })
        assertTrue(errors.any { it.contains("page de fin") })
        assertTrue(errors.any { it.contains("difficulté") })
        assertTrue(errors.any { it.contains("500") })
    }

    @Test
    fun `accepts exactly five hundred note characters`() {
        val note = "x".repeat(MAX_NOTE_LENGTH)

        val result = buildActivityDeclaration(
            item,
            validForm(note = note),
            occurredAt,
        ).requireValid()

        assertEquals(MAX_NOTE_LENGTH, result.note?.length)
    }

    @Test
    fun `maps the declaration to the real sync event without losing the offset`() {
        val declaration = buildActivityDeclaration(
            item,
            validForm(),
            occurredAt.withNano(987_000_000),
        ).requireValid()

        val event = declaration.toStudyEvent("evt_01K1HZZZZZZZZZZZZZZZZZZZZZ")

        assertEquals(StudyEventType.ACTIVITY_RECORDED, event.type)
        assertEquals("2026-07-31T15:42:00+02:00", event.occurredAt)
        assertEquals("nod_chapter", event.nodeId)
        assertEquals("stp_01JZQK3M8F2W", event.stepId)
        assertEquals(ActivityUnit.Pages(1, 2), event.unit)
        assertEquals(Difficulty.HARD, event.difficulty)
    }

    private fun validForm(
        unitType: WorkUnitType = WorkUnitType.PAGES,
        pagesFrom: String = "1",
        pagesTo: String = "2",
        annaleLabel: String = "",
        cardsCount: String = "",
        freeLabel: String = "",
        note: String = "",
    ) = DeclareFormState(
        durationMinutes = "35",
        unitType = unitType,
        pagesFrom = pagesFrom,
        pagesTo = pagesTo,
        annaleLabel = annaleLabel,
        cardsCount = cardsCount,
        freeLabel = freeLabel,
        difficulty = Difficulty.HARD,
        note = note,
    )

    private fun DeclarationBuildResult.requireValid(): ActivityDeclaration {
        assertTrue(this is DeclarationBuildResult.Valid)
        return (this as DeclarationBuildResult.Valid).declaration
    }
}
