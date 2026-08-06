package com.albugimed.blockerspike.guide

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

class BlockGuideRepository internal constructor(
    private val store: BlockGuideFileStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(
        BlockGuideFileStore(File(context.filesDir, STORE_DIRECTORY)),
    )

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(readState())
    val state: StateFlow<BlockGuideState> = mutableState.asStateFlow()

    /** Lecture seule : une copie corrompue n'est jamais exposee comme active. */
    fun currentGuide(): ActiveBlockGuide? = mutableState.value.active

    suspend fun importGuide(sourceName: String?, input: InputStream): GuideImportResult =
        mutex.withLock {
            val safeSourceName = sourceName?.take(MAX_SOURCE_NAME_CHARS)
            val sequence = try {
                store.nextSequence()
            } catch (_: Exception) {
                1L
            }
            val atMillis = clock()
            val candidate = readCandidate(input)
            if (candidate is CandidateRead.Failed) {
                return@withLock reject(
                    sequence = sequence,
                    atMillis = atMillis,
                    sourceName = safeSourceName,
                    candidateHash = candidate.sha256,
                    issues = listOf(candidate.issue),
                )
            }

            val bytes = (candidate as CandidateRead.Success).bytes
            val candidateHash = sha256(bytes)
            when (val parsed = BlockGuideParser.parse(bytes)) {
                is GuideParseResult.Invalid -> reject(
                    sequence,
                    atMillis,
                    safeSourceName,
                    candidateHash,
                    parsed.issues,
                )
                is GuideParseResult.Valid -> accept(
                    sequence,
                    atMillis,
                    safeSourceName,
                    candidateHash,
                    bytes,
                    parsed.guide,
                )
            }
        }

    /** Relit le stockage prive, notamment apres un redemarrage de processus. */
    fun reload() {
        mutableState.value = readState()
    }

    private fun accept(
        sequence: Long,
        atMillis: Long,
        sourceName: String?,
        candidateHash: String,
        bytes: ByteArray,
        parsed: ParsedBlockGuide,
    ): GuideImportResult {
        val active = ActiveBlockGuide(
            metadata = GuideMetadata(
                sequence = sequence,
                guideVersion = parsed.guideVersion,
                updatedAt = parsed.updatedAt,
                importedAtMillis = atMillis,
                sizeBytes = bytes.size,
                sha256 = candidateHash,
                sourceName = sourceName,
                headers = parsed.headers,
            ),
            content = parsed.content,
            body = parsed.body,
        )
        val acceptedReport = GuideValidationReport(
            sequence = sequence,
            validatedAtMillis = atMillis,
            accepted = true,
            sourceName = sourceName,
            candidateSha256 = candidateHash,
            issues = emptyList(),
        )

        try {
            // Le rapport ne dit jamais "accepte" avant que la copie privee
            // complete ait ete promue.
            store.promoteActive(active)
        } catch (error: Exception) {
            return reject(
                sequence = sequence,
                atMillis = atMillis,
                sourceName = sourceName,
                candidateHash = candidateHash,
                issues = listOf(
                    GuideValidationIssue(
                        GuideIssueCode.STORAGE_ERROR,
                        "Le guide est valide mais sa copie privee n'a pas pu etre enregistree " +
                            "(${error.javaClass.simpleName}).",
                    ),
                ),
            )
        }

        // Si une coupure arrive ici, le numero de sequence du guide sera
        // superieur a celui du rapport. readState() reconstruit alors ce
        // rapport d'acceptation depuis le guide promu.
        runCatching { store.promoteReport(acceptedReport) }
        mutableState.value = readState()
        return GuideImportResult(accepted = true, report = acceptedReport)
    }

    private fun reject(
        sequence: Long,
        atMillis: Long,
        sourceName: String?,
        candidateHash: String?,
        issues: List<GuideValidationIssue>,
    ): GuideImportResult {
        val report = GuideValidationReport(
            sequence = sequence,
            validatedAtMillis = atMillis,
            accepted = false,
            sourceName = sourceName,
            candidateSha256 = candidateHash,
            issues = issues,
        )
        val persisted = runCatching { store.promoteReport(report) }.isSuccess
        mutableState.value = if (persisted) {
            readState()
        } else {
            readState().copy(
                lastReport = report,
                storageProblem = "Le rapport de validation n'a pas pu etre enregistre.",
            )
        }
        return GuideImportResult(accepted = false, report = report)
    }

    private fun readState(): BlockGuideState {
        val disk = store.read()
        val active = (disk.active as? ActiveFileRead.Valid)?.guide
        val persistedReport = (disk.report as? ReportFileRead.Valid)?.report
        val report = when {
            active != null && (persistedReport == null || active.metadata.sequence > persistedReport.sequence) -> {
                GuideValidationReport(
                    sequence = active.metadata.sequence,
                    validatedAtMillis = active.metadata.importedAtMillis,
                    accepted = true,
                    sourceName = active.metadata.sourceName,
                    candidateSha256 = active.metadata.sha256,
                    issues = emptyList(),
                )
            }
            else -> persistedReport
        }

        val problems = buildList {
            if (disk.active is ActiveFileRead.Corrupted) add(disk.active.reason)
            if (disk.report is ReportFileRead.Corrupted) add(disk.report.reason)
        }
        return when (disk.active) {
            ActiveFileRead.Absent -> BlockGuideState(
                availability = GuideAvailability.ABSENT,
                lastReport = report,
                storageProblem = problems.joinToString(" ; ").ifBlank { null },
            )
            is ActiveFileRead.Corrupted -> BlockGuideState(
                availability = GuideAvailability.CORRUPTED,
                lastReport = report,
                storageProblem = problems.joinToString(" ; "),
            )
            is ActiveFileRead.Valid -> BlockGuideState(
                availability = GuideAvailability.ACTIVE,
                active = disk.active.guide,
                lastReport = report,
                storageProblem = problems.joinToString(" ; ").ifBlank { null },
            )
        }
    }

    private fun readCandidate(input: InputStream): CandidateRead {
        return try {
            input.use { source ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > MAX_GUIDE_BYTES) {
                        return CandidateRead.Failed(
                            GuideValidationIssue(
                                GuideIssueCode.TOO_LARGE,
                                "Le fichier depasse la limite de 256 Kio.",
                            ),
                        )
                    }
                    output.write(buffer, 0, read)
                }
                CandidateRead.Success(output.toByteArray())
            }
        } catch (error: Exception) {
            CandidateRead.Failed(
                GuideValidationIssue(
                    GuideIssueCode.READ_ERROR,
                    "Le fichier n'a pas pu etre lu (${error.javaClass.simpleName}).",
                ),
            )
        }
    }

    private sealed interface CandidateRead {
        data class Success(val bytes: ByteArray) : CandidateRead
        data class Failed(
            val issue: GuideValidationIssue,
            val sha256: String? = null,
        ) : CandidateRead
    }

    companion object {
        const val STORE_DIRECTORY = "block_guide"
        // 128 UTF-16 code units tiennent toujours dans la borne binaire de
        // 512 octets, y compris avec des caracteres UTF-8 sur quatre octets.
        private const val MAX_SOURCE_NAME_CHARS = 128
    }
}
