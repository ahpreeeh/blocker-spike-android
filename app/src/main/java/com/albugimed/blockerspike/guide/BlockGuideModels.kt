package com.albugimed.blockerspike.guide

import java.time.LocalDate

const val MAX_GUIDE_BYTES: Int = 256 * 1024

enum class GuideIssueCode {
    READ_ERROR,
    TOO_LARGE,
    INVALID_UTF8,
    HEADER_MISSING,
    HEADER_UNTERMINATED,
    HEADER_LINE_INVALID,
    DUPLICATE_KEY,
    MULTILINE_VALUE,
    QUOTED_VALUE_INVALID,
    GUIDE_VERSION_MISSING,
    GUIDE_VERSION_EMPTY,
    GUIDE_VERSION_TOO_LONG,
    UPDATED_AT_MISSING,
    UPDATED_AT_INVALID,
    STORAGE_ERROR,
}

data class GuideValidationIssue(
    val code: GuideIssueCode,
    val message: String,
    val line: Int? = null,
)

data class GuideValidationReport(
    val sequence: Long,
    val validatedAtMillis: Long,
    val accepted: Boolean,
    val sourceName: String?,
    val candidateSha256: String?,
    val issues: List<GuideValidationIssue>,
) {
    init {
        require(sequence >= 0L)
        require(accepted == issues.isEmpty()) {
            "Un rapport accepte ne peut pas porter d'erreur, et inversement."
        }
    }
}

data class GuideMetadata(
    val sequence: Long,
    val guideVersion: String,
    val updatedAt: LocalDate,
    val importedAtMillis: Long,
    val sizeBytes: Int,
    val sha256: String,
    val sourceName: String?,
    /** Toutes les cles, y compris celles qu'une version actuelle ne comprend pas. */
    val headers: Map<String, String>,
)

/**
 * Vue immuable destinee aux futurs consommateurs. Ce lot ne lui branche
 * volontairement aucun comportement de blocage.
 */
data class ActiveBlockGuide(
    val metadata: GuideMetadata,
    val content: String,
    val body: String,
)

enum class GuideAvailability {
    ABSENT,
    ACTIVE,
    CORRUPTED,
}

data class BlockGuideState(
    val availability: GuideAvailability = GuideAvailability.ABSENT,
    val active: ActiveBlockGuide? = null,
    val lastReport: GuideValidationReport? = null,
    val storageProblem: String? = null,
) {
    init {
        require((availability == GuideAvailability.ACTIVE) == (active != null))
    }
}

data class ParsedBlockGuide(
    val content: String,
    val body: String,
    val headers: Map<String, String>,
    val guideVersion: String,
    val updatedAt: LocalDate,
)

sealed interface GuideParseResult {
    data class Valid(val guide: ParsedBlockGuide) : GuideParseResult
    data class Invalid(val issues: List<GuideValidationIssue>) : GuideParseResult
}

data class GuideImportResult(
    val accepted: Boolean,
    val report: GuideValidationReport,
)
