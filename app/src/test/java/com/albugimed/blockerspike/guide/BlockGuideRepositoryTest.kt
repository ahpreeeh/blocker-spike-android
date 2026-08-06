package com.albugimed.blockerspike.guide

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class BlockGuideRepositoryTest {
    private lateinit var root: File
    private lateinit var guideDirectory: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("block-guide-test").toFile()
        guideDirectory = File(root, BlockGuideRepository.STORE_DIRECTORY)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun validGuideIsCopiedPrivatelyAndSurvivesRestartWithoutSource() = runTest {
        val source = File(root, "source.md").apply { writeText(validGuide("v1")) }
        val repository = repository(clock = 1_234L)

        val result = source.inputStream().use {
            repository.importGuide(source.name, it)
        }
        source.delete()
        val restarted = repository(clock = 9_999L)

        assertTrue(result.accepted)
        assertEquals(GuideAvailability.ACTIVE, restarted.state.value.availability)
        val active = requireNotNull(restarted.currentGuide())
        assertEquals("v1", active.metadata.guideVersion)
        assertEquals(1_234L, active.metadata.importedAtMillis)
        assertEquals(validGuide("v1"), active.content)
        assertEquals(sha256(validGuide("v1").toByteArray()), active.metadata.sha256)
        assertFalse(source.exists())
    }

    @Test
    fun rejectedGuideNeverReplacesValidGuideAndReportSurvivesRestart() = runTest {
        val repository = repository(clock = 10L)
        repository.importGuide("good.md", bytes(validGuide("safe")))
        val before = requireNotNull(repository.currentGuide())

        val rejected = repository.importGuide(
            "broken.md",
            bytes("---\nupdated_at: 2026-08-06\n---\n# perdu"),
        )
        val restarted = repository(clock = 50L)

        assertFalse(rejected.accepted)
        assertEquals(before, restarted.currentGuide())
        assertEquals("broken.md", restarted.state.value.lastReport?.sourceName)
        assertEquals(
            GuideIssueCode.GUIDE_VERSION_MISSING,
            restarted.state.value.lastReport?.issues?.single()?.code,
        )
    }

    @Test
    fun rejectionPersistsEvenWhenThereHasNeverBeenAnActiveGuide() = runTest {
        val repository = repository(clock = 42L)

        repository.importGuide("not-utf8.md", ByteArrayInputStream(byteArrayOf(0x80.toByte())))
        val restarted = repository()

        assertEquals(GuideAvailability.ABSENT, restarted.state.value.availability)
        assertNull(restarted.currentGuide())
        assertEquals(
            GuideIssueCode.INVALID_UTF8,
            restarted.state.value.lastReport?.issues?.single()?.code,
        )
    }

    @Test
    fun fourRequiredFailureFamiliesStayDistinctInPersistentReport() = runTest {
        val repository = repository()
        val candidates = listOf(
            byteArrayOf(0x80.toByte()) to GuideIssueCode.INVALID_UTF8,
            ByteArray(MAX_GUIDE_BYTES + 1) to GuideIssueCode.TOO_LARGE,
            "guide_version: v1".toByteArray() to GuideIssueCode.HEADER_MISSING,
            "---\nupdated_at: 2026-08-06\n---".toByteArray() to
                GuideIssueCode.GUIDE_VERSION_MISSING,
        )

        candidates.forEachIndexed { index, (candidate, expected) ->
            repository.importGuide("bad-$index.md", ByteArrayInputStream(candidate))
            assertEquals(expected, repository.state.value.lastReport?.issues?.first()?.code)
            assertEquals(
                expected,
                repository().state.value.lastReport?.issues?.first()?.code,
            )
        }
    }

    @Test
    fun failedAtomicPromotionLeavesPreviousGuideIntactAndNoTemporaryFile() = runTest {
        repository(clock = 1L).importGuide("old.md", bytes(validGuide("old")))
        val failActiveOnly = AtomicPromoter { temporary, target ->
            if (target.fileName.toString() == "active_guide.bin") {
                throw IOException("simulated power loss")
            }
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        val interrupted = BlockGuideRepository(
            BlockGuideFileStore(guideDirectory, failActiveOnly),
            clock = { 2L },
        )

        val result = interrupted.importGuide("new.md", bytes(validGuide("new")))
        val restarted = repository()

        assertFalse(result.accepted)
        assertEquals("old", restarted.currentGuide()?.metadata?.guideVersion)
        assertEquals(GuideIssueCode.STORAGE_ERROR, restarted.state.value.lastReport?.issues?.single()?.code)
        assertTrue(guideDirectory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun restartRecoversAcceptedReportIfPowerStopsAfterActivePromotion() = runTest {
        repository(clock = 1L).importGuide("old.md", bytes(validGuide("old")))
        val failReportOnly = AtomicPromoter { temporary, target ->
            if (target.fileName.toString() == "last_validation.bin") {
                throw IOException("simulated power loss")
            }
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        val interrupted = BlockGuideRepository(
            BlockGuideFileStore(guideDirectory, failReportOnly),
            clock = { 2L },
        )

        assertTrue(interrupted.importGuide("new.md", bytes(validGuide("new"))).accepted)
        val restarted = repository()

        assertEquals("new", restarted.currentGuide()?.metadata?.guideVersion)
        assertTrue(restarted.state.value.lastReport?.accepted == true)
        assertEquals(
            restarted.currentGuide()?.metadata?.sha256,
            restarted.state.value.lastReport?.candidateSha256,
        )
    }

    @Test
    fun corruptedActiveGuideFailsClosedWithoutTouchingBlockPolicyStore() = runTest {
        val policyMarker = File(root, "block_policy.preferences_pb")
            .apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val markerBefore = policyMarker.readBytes()
        repository().importGuide("guide.md", bytes(validGuide("v1")))
        File(guideDirectory, "active_guide.bin").writeBytes(byteArrayOf(9, 8, 7))

        val restarted = repository()

        assertEquals(GuideAvailability.CORRUPTED, restarted.state.value.availability)
        assertNull(restarted.currentGuide())
        assertNotNull(restarted.state.value.storageProblem)
        assertArrayEquals(markerBefore, policyMarker.readBytes())
    }

    @Test
    fun invalidImportDoesNotOverwriteAlreadyCorruptedActiveBytes() = runTest {
        repository().importGuide("guide.md", bytes(validGuide("v1")))
        val activeFile = File(guideDirectory, "active_guide.bin")
        val corrupted = byteArrayOf(1, 3, 3, 7)
        activeFile.writeBytes(corrupted)

        val repository = repository(clock = 90L)
        repository.importGuide("bad.md", bytes("sans front matter"))
        val restarted = repository()

        assertArrayEquals(corrupted, activeFile.readBytes())
        assertEquals(GuideAvailability.CORRUPTED, restarted.state.value.availability)
        assertEquals(GuideIssueCode.HEADER_MISSING, restarted.state.value.lastReport?.issues?.single()?.code)
    }

    @Test
    fun readFailureClosesInputAndPersistsNamedError() = runTest {
        var closed = false
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("source removed")
            override fun close() { closed = true }
        }

        val result = repository().importGuide("gone.md", failing)

        assertFalse(result.accepted)
        assertTrue(closed)
        assertEquals(GuideIssueCode.READ_ERROR, result.report.issues.single().code)
        assertEquals(GuideIssueCode.READ_ERROR, repository().state.value.lastReport?.issues?.single()?.code)
    }

    @Test
    fun emptyBodyAndUnknownHeadersRoundTripThroughPrivateStore() = runTest {
        val raw = "---\nguide_version: empty\nupdated_at: 2026-08-06\nfuture: a:b:c\n---"

        repository().importGuide("empty.md", bytes(raw))
        val active = requireNotNull(repository().currentGuide())

        assertEquals("", active.body)
        assertEquals("a:b:c", active.metadata.headers["future"])
        assertEquals(raw, active.content)
    }

    private fun repository(clock: Long = 123L): BlockGuideRepository = BlockGuideRepository(
        BlockGuideFileStore(guideDirectory),
        clock = { clock },
    )

    private fun bytes(value: String): ByteArrayInputStream = ByteArrayInputStream(value.toByteArray())

    private fun validGuide(version: String): String =
        "---\nguide_version: $version\nupdated_at: 2026-08-06\nunknown: kept\n---\n# Regles\nTout bloquer."
}
