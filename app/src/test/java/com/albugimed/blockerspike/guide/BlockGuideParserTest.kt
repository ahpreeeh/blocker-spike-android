package com.albugimed.blockerspike.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.time.LocalDate

class BlockGuideParserTest {
    @Test
    fun acceptsFlatHeaderQuotesCommentsFirstColonAndUnknownKeys() {
        val raw = """
            ---
            # commentaire
            guide_version: "2026-08-06.1" # version visible

            updated_at: '2026-08-06'
            future_key: urn:albugimed:value:1
            hash: abc#def
            ---

            # Regles
            Tout le corps reste libre: oui.
        """.trimIndent()

        val parsed = valid(raw)

        assertEquals("2026-08-06.1", parsed.guideVersion)
        assertEquals(LocalDate.of(2026, 8, 6), parsed.updatedAt)
        assertEquals("urn:albugimed:value:1", parsed.headers["future_key"])
        assertEquals("abc#def", parsed.headers["hash"])
        assertTrue(parsed.body.contains("Tout le corps reste libre: oui."))
        assertEquals(raw, parsed.content)
    }

    @Test
    fun acceptsEmptyBodyAndCrLf() {
        val raw = "---\r\nguide_version: v1\r\nupdated_at: 2024-02-29\r\n---"
        val parsed = valid(raw)

        assertEquals("", parsed.body)
        assertEquals(LocalDate.of(2024, 2, 29), parsed.updatedAt)
    }

    @Test
    fun keepsTheMarkdownBodyLineEndingsUntouched() {
        val raw = "---\r\nguide_version: v1\r\nupdated_at: 2026-08-06\r\n---\r\nligne 1\r\nligne 2"

        assertEquals("ligne 1\r\nligne 2", valid(raw).body)
    }

    @Test
    fun acceptsExactly256KibMeasuredAsUtf8Bytes() {
        val prefix = "---\nguide_version: v1\nupdated_at: 2026-08-06\n---\n"
        val bytes = (prefix + "x".repeat(MAX_GUIDE_BYTES - prefix.length))
            .toByteArray(StandardCharsets.UTF_8)

        assertEquals(MAX_GUIDE_BYTES, bytes.size)
        assertTrue(BlockGuideParser.parse(bytes) is GuideParseResult.Valid)
    }

    @Test
    fun rejectsByteOverLimitBeforeTextParsing() {
        val bytes = ByteArray(MAX_GUIDE_BYTES + 1) { 'x'.code.toByte() }

        assertIssue(bytes, GuideIssueCode.TOO_LARGE)
    }

    @Test
    fun rejectsMalformedUtf8Distinctly() {
        assertIssue(byteArrayOf(0xC3.toByte(), 0x28), GuideIssueCode.INVALID_UTF8)
    }

    @Test
    fun requiresOpeningDelimiterOnTheVeryFirstLine() {
        assertIssue(
            "\n---\nguide_version: v1\nupdated_at: 2026-08-06\n---".toByteArray(),
            GuideIssueCode.HEADER_MISSING,
            expectedLine = 1,
        )
    }

    @Test
    fun rejectsUnterminatedHeader() {
        assertIssue(
            "---\nguide_version: v1\nupdated_at: 2026-08-06".toByteArray(),
            GuideIssueCode.HEADER_UNTERMINATED,
            expectedLine = 1,
        )
    }

    @Test
    fun rejectsMalformedHeaderLineWithItsLineNumber() {
        assertIssue(
            "---\nguide_version: v1\nnot a pair\nupdated_at: 2026-08-06\n---".toByteArray(),
            GuideIssueCode.HEADER_LINE_INVALID,
            expectedLine = 3,
        )
    }

    @Test
    fun rejectsDuplicateKeyAtSecondOccurrence() {
        assertIssue(
            "---\nguide_version: v1\nguide_version: v2\nupdated_at: 2026-08-06\n---".toByteArray(),
            GuideIssueCode.DUPLICATE_KEY,
            expectedLine = 3,
        )
    }

    @Test
    fun rejectsLiteralAndFoldedMultilineMarkers() {
        listOf("|", "|-", "|+", ">", ">-", ">+").forEach { marker ->
            assertIssue(
                "---\nguide_version: v1\nupdated_at: 2026-08-06\nextra: $marker\n---".toByteArray(),
                GuideIssueCode.MULTILINE_VALUE,
                expectedLine = 4,
            )
        }
    }

    @Test
    fun rejectsNewlineEscapesInsideQuotedScalars() {
        listOf("\\n", "\\r").forEach { escapedLineBreak ->
            assertIssue(
                (
                    "---\nguide_version: \"v1${escapedLineBreak}v2\"\n" +
                        "updated_at: 2026-08-06\n---"
                    ).toByteArray(),
                GuideIssueCode.MULTILINE_VALUE,
                expectedLine = 2,
            )
        }
    }

    @Test
    fun rejectsIndentedContinuationOrNestedMapping() {
        assertIssue(
            "---\nguide_version: v1\nupdated_at: 2026-08-06\nextra:\n  nested: value\n---".toByteArray(),
            GuideIssueCode.MULTILINE_VALUE,
            expectedLine = 5,
        )
    }

    @Test
    fun rejectsUnclosedQuotedValue() {
        assertIssue(
            "---\nguide_version: \"v1\nupdated_at: 2026-08-06\n---".toByteArray(),
            GuideIssueCode.QUOTED_VALUE_INVALID,
            expectedLine = 2,
        )
    }

    @Test
    fun quotedScalarMustEndExactlyAtItsClosingQuote() {
        assertIssue(
            "---\nguide_version: \"v1\" suffix\nupdated_at: 2026-08-06\n---".toByteArray(),
            GuideIssueCode.QUOTED_VALUE_INVALID,
            expectedLine = 2,
        )
    }

    @Test
    fun reportsBothMissingRequiredKeys() {
        val invalid = BlockGuideParser.parse("---\nfuture: yes\n---".toByteArray())
            as GuideParseResult.Invalid

        assertEquals(
            listOf(GuideIssueCode.GUIDE_VERSION_MISSING, GuideIssueCode.UPDATED_AT_MISSING),
            invalid.issues.map { it.code },
        )
    }

    @Test
    fun rejectsBlankAndOverlongGuideVersionsDistinctly() {
        assertIssue(
            "---\nguide_version: \"\"\nupdated_at: 2026-08-06\n---".toByteArray(),
            GuideIssueCode.GUIDE_VERSION_EMPTY,
            expectedLine = 2,
        )
        assertIssue(
            "---\nguide_version: ${"v".repeat(33)}\nupdated_at: 2026-08-06\n---".toByteArray(),
            GuideIssueCode.GUIDE_VERSION_TOO_LONG,
            expectedLine = 2,
        )
    }

    @Test
    fun rejectsImpossibleOrNonCanonicalDates() {
        listOf("2026-02-29", "2026-2-01", "06-08-2026", "2026-08-06T00:00:00Z")
            .forEach { date ->
                assertIssue(
                    "---\nguide_version: v1\nupdated_at: $date\n---".toByteArray(),
                    GuideIssueCode.UPDATED_AT_INVALID,
                    expectedLine = 3,
                )
            }
    }

    private fun valid(raw: String): ParsedBlockGuide =
        (BlockGuideParser.parse(raw.toByteArray(StandardCharsets.UTF_8)) as GuideParseResult.Valid).guide

    private fun assertIssue(
        bytes: ByteArray,
        expected: GuideIssueCode,
        expectedLine: Int? = null,
    ) {
        val result = BlockGuideParser.parse(bytes) as GuideParseResult.Invalid
        assertEquals(expected, result.issues.first().code)
        assertEquals(expectedLine, result.issues.first().line)
    }
}
