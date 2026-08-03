package com.albugimed.blockerspike.study

import com.albugimed.blockerspike.sync.ActivityUnit
import com.albugimed.blockerspike.sync.CachedQueue
import com.albugimed.blockerspike.sync.DeadEvent
import com.albugimed.blockerspike.sync.Difficulty
import com.albugimed.blockerspike.sync.EnrolOutcome
import com.albugimed.blockerspike.sync.OutboxState
import com.albugimed.blockerspike.sync.QueueSnapshot
import com.albugimed.blockerspike.sync.StudyEvent
import com.albugimed.blockerspike.sync.StudyEventType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.OffsetDateTime

class SyncStudyRepositoryTest {
    private val declaration = ActivityDeclaration(
        nodeId = "nod_chapter",
        stepId = "stp_step",
        occurredAt = OffsetDateTime.parse("2026-07-31T15:42:00+02:00"),
        durationMinutes = 35,
        unit = ActivityUnit.Pages(47, 62),
        difficulty = Difficulty.HARD,
        note = "à revoir",
    )

    @Test
    fun `enqueues locally before attempting any sync`() = runTest {
        val calls = mutableListOf<String>()
        var enqueued: StudyEvent? = null
        val repository = repository(
            enqueue = { event ->
                calls += "enqueue"
                enqueued = event
                true
            },
            sync = { force -> calls += "sync:$force" },
        )

        repository.saveActivityLocally(declaration)
        repository.syncAfterLocalSave()

        assertEquals(listOf("enqueue", "sync:false"), calls)
        assertEquals("evt_fixed", enqueued?.eventId)
        assertEquals(StudyEventType.ACTIVITY_RECORDED, enqueued?.type)
    }

    @Test
    fun `network failure after enqueue does not turn a local save into an error`() = runTest {
        val repository = repository(
            enqueue = { true },
            sync = { error("offline") },
        )

        repository.saveActivityLocally(declaration)
        runCatching { repository.syncAfterLocalSave() }
    }

    @Test
    fun `local write failure is surfaced and sync is not attempted`() {
        var syncCalls = 0
        val repository = repository(
            enqueue = { false },
            sync = { syncCalls += 1 },
        )

        assertThrows(LocalStudySaveException::class.java) {
            runTest { repository.saveActivityLocally(declaration) }
        }
        assertEquals(0, syncCalls)
    }

    @Test
    fun `queue opening respects backoff while retry is forced`() = runTest {
        val forces = mutableListOf<Boolean>()
        val repository = repository(
            enqueue = { true },
            sync = { forces += it },
        )

        repository.onQueueOpened()
        repository.retryPending()

        assertEquals(listOf(false, true), forces)
    }

    @Test
    fun `delegates enrolment without storing credentials in the screen adapter`() = runTest {
        val inputs = mutableListOf<Pair<String, String>>()
        val repository = SyncStudyRepository(
            cachedQueues = MutableStateFlow(CachedQueue()),
            outboxStates = MutableStateFlow(OutboxState()),
            enqueue = { true },
            requestSync = {},
            enrol = { url, token ->
                inputs += url to token
                EnrolOutcome.Enrolled("device")
            },
        )

        val outcome = repository.enrolDevice("https://atelier.test", "TOKEN")

        assertEquals(listOf("https://atelier.test" to "TOKEN"), inputs)
        assertEquals(EnrolOutcome.Enrolled("device"), outcome)
    }

    @Test
    fun `projects cache pending dead and unreadable states without inventing zero`() = runTest {
        val event = StudyEvent(
            eventId = "evt_dead",
            type = StudyEventType.ACTIVITY_RECORDED,
            occurredAt = "2026-07-31T15:42:00+02:00",
            nodeId = "nod_chapter",
        )
        val cached = MutableStateFlow(
            CachedQueue(
                snapshot = QueueSnapshot(
                    generatedAt = "server",
                    items = emptyList(),
                    skipped = 3,
                ),
                fetchedAtMillis = 1234L,
            ),
        )
        val outbox = MutableStateFlow(
            OutboxState(
                pending = listOf(event),
                dead = listOf(DeadEvent(event, "motif")),
                unreadable = 2,
                storageHealthy = false,
            ),
        )
        val repository = SyncStudyRepository(
            cachedQueues = cached,
            outboxStates = outbox,
            enqueue = { true },
            requestSync = {},
        )

        val state = repository.queueState.first()

        assertEquals(1234L, state.cachedAtMillis)
        assertEquals(3, state.skippedQueueItems)
        assertEquals(1, state.pendingCount)
        assertEquals(1, state.rejectedEvents.size)
        assertEquals(2, state.unreadableCount)
        assertEquals(false, state.outboxStorageHealthy)
    }

    private fun repository(
        enqueue: suspend (StudyEvent) -> Boolean,
        sync: suspend (Boolean) -> Unit,
    ) = SyncStudyRepository(
        cachedQueues = MutableStateFlow(CachedQueue()),
        outboxStates = MutableStateFlow(OutboxState()),
        enqueue = enqueue,
        requestSync = sync,
        nowMillis = { 1L },
        eventIdFactory = { "evt_fixed" },
    )
}
