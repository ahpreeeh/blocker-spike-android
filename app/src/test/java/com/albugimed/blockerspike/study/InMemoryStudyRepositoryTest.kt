package com.albugimed.blockerspike.study

import com.albugimed.blockerspike.sync.ActivityUnit
import com.albugimed.blockerspike.sync.ActivityKind
import com.albugimed.blockerspike.sync.Difficulty
import com.albugimed.blockerspike.sync.EnrolOutcome
import com.albugimed.blockerspike.sync.NodeRef
import com.albugimed.blockerspike.sync.PathCommand
import com.albugimed.blockerspike.sync.QueueItem
import com.albugimed.blockerspike.sync.QueueSignals
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime

class InMemoryStudyRepositoryTest {
    private val first = queueItem("stp_first", "Première")
    private val second = queueItem("stp_second", "Deuxième")

    @Test
    fun `keeps the server queue order unchanged`() {
        val repository = InMemoryStudyRepository(
            StudyQueueState(items = listOf(second, first)),
        )

        assertEquals(listOf("stp_second", "stp_first"), repository.queueState.value.items.map {
            it.stepId
        })
    }

    @Test
    fun `local save completes before pending count is exposed`() = runTest {
        val repository = InMemoryStudyRepository(StudyQueueState(items = listOf(first)))
        val declaration = ActivityDeclaration(
            nodeId = first.subject.nodeId,
            stepId = first.stepId,
            occurredAt = OffsetDateTime.parse("2026-07-31T15:42:00+02:00"),
            durationMinutes = 30,
            unit = ActivityUnit.Chapter,
            difficulty = Difficulty.OK,
            activityKind = ActivityKind.FIRST_STUDY,
            note = null,
        )

        repository.saveActivityLocally(declaration)

        assertEquals(listOf(declaration), repository.savedDeclarations)
        assertEquals(1, repository.queueState.value.pendingCount)
    }

    @Test
    fun `opening and retrying never remove a pending local declaration in the fake`() = runTest {
        val repository = InMemoryStudyRepository(
            StudyQueueState(items = listOf(first), pendingCount = 2),
        )

        repository.onQueueOpened()
        repository.retryPending()

        assertEquals(2, repository.queueState.value.pendingCount)
    }

    @Test
    fun `grouped subject addition keeps one distinct command per subject`() = runTest {
        val repository = InMemoryStudyRepository()

        repository.addSubjectsToPath(listOf("nod_cardio", "nod_pharma", "nod_cardio"))

        val commands = repository.queueState.value.pendingPathCommands
            .filterIsInstance<PathCommand.PourSubject>()
        assertEquals(listOf("nod_cardio", "nod_pharma"), commands.map { it.nodeId })
        assertEquals(2, commands.map { it.commandId }.toSet().size)
    }

    @Test
    fun `status labels expose pending and rejected counts`() {
        assertEquals("1 en attente d'envoi", pendingLabel(1))
        assertEquals("1 rejeté", rejectedLabel(1))
        assertEquals("3 rejetés", rejectedLabel(3))
        assertEquals("Liste à jour du —", cacheFreshnessLabel(null))
        assertEquals(
            "Adresse invalide : utilise une adresse HTTPS.",
            enrolmentMessage(EnrolOutcome.InvalidUrl),
        )
        assertEquals("Jeton refusé par le serveur.", enrolmentMessage(EnrolOutcome.Rejected))
    }

    private fun queueItem(stepId: String, label: String) = QueueItem(
        stepId = stepId,
        label = label,
        kind = "first_study",
        subject = NodeRef("nod_subject", "Cardiologie"),
        chapter = null,
        resource = null,
        signals = QueueSignals(null, null, null, null),
    )
}
