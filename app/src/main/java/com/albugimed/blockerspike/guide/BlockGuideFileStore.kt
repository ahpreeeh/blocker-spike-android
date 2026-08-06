package com.albugimed.blockerspike.guide

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal fun interface AtomicPromoter {
    fun promote(temporary: Path, target: Path)
}

internal object NioAtomicPromoter : AtomicPromoter {
    override fun promote(temporary: Path, target: Path) {
        Files.move(
            temporary,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

internal sealed interface ActiveFileRead {
    data object Absent : ActiveFileRead
    data class Valid(val guide: ActiveBlockGuide) : ActiveFileRead
    data class Corrupted(val reason: String) : ActiveFileRead
}

internal sealed interface ReportFileRead {
    data object Absent : ReportFileRead
    data class Valid(val report: GuideValidationReport) : ReportFileRead
    data class Corrupted(val reason: String) : ReportFileRead
}

internal data class BlockGuideDiskState(
    val active: ActiveFileRead,
    val report: ReportFileRead,
)

/**
 * Magasin prive reserve au guide. Il n'utilise ni le DataStore `block_policy`,
 * ni aucun fichier partage avec lui.
 *
 * Chaque enregistrement est ecrit dans le meme repertoire, synchronise sur
 * disque, puis promu par renommage atomique. Une interruption ne peut donc
 * exposer qu'une ancienne ou une nouvelle version complete.
 */
internal class BlockGuideFileStore(
    private val directory: File,
    private val promoter: AtomicPromoter = NioAtomicPromoter,
) {
    private val activeFile = File(directory, "active_guide.bin")
    private val reportFile = File(directory, "last_validation.bin")

    fun read(): BlockGuideDiskState = BlockGuideDiskState(
        active = readActive(),
        report = readReport(),
    )

    fun nextSequence(): Long {
        val disk = read()
        val activeSequence = (disk.active as? ActiveFileRead.Valid)?.guide?.metadata?.sequence ?: 0L
        val reportSequence = (disk.report as? ReportFileRead.Valid)?.report?.sequence ?: 0L
        val current = maxOf(activeSequence, reportSequence)
        if (current == Long.MAX_VALUE) throw IOException("Compteur du magasin epuise")
        return current + 1L
    }

    fun promoteActive(guide: ActiveBlockGuide) {
        atomicWrite(activeFile) { output ->
            output.write(ACTIVE_MAGIC)
            output.writeInt(FORMAT_VERSION)
            output.writeLong(guide.metadata.sequence)
            output.writeLong(guide.metadata.importedAtMillis)
            output.writeNullableString(guide.metadata.sourceName, MAX_SOURCE_NAME_BYTES)
            output.write(hexToBytes(guide.metadata.sha256))
            val raw = guide.content.toByteArray(StandardCharsets.UTF_8)
            output.writeInt(raw.size)
            output.write(raw)
        }
    }

    fun promoteReport(report: GuideValidationReport) {
        atomicWrite(reportFile) { output ->
            output.write(REPORT_MAGIC)
            output.writeInt(FORMAT_VERSION)
            output.writeLong(report.sequence)
            output.writeLong(report.validatedAtMillis)
            output.writeBoolean(report.accepted)
            output.writeNullableString(report.sourceName, MAX_SOURCE_NAME_BYTES)
            output.writeNullableString(report.candidateSha256, SHA_HEX_LENGTH)
            output.writeInt(report.issues.size)
            report.issues.forEach { issue ->
                output.writeString(issue.code.name, MAX_CODE_BYTES)
                output.writeString(issue.message, MAX_MESSAGE_BYTES)
                output.writeInt(issue.line ?: -1)
            }
        }
    }

    private fun readActive(): ActiveFileRead {
        if (!activeFile.exists()) return ActiveFileRead.Absent
        return try {
            ensureBounded(activeFile, MAX_ACTIVE_RECORD_BYTES)
            DataInputStream(BufferedInputStream(FileInputStream(activeFile))).use { input ->
                input.requireMagic(ACTIVE_MAGIC)
                input.requireVersion()
                val sequence = input.readLong().also { requireStore(it > 0L) }
                val importedAt = input.readLong()
                val sourceName = input.readNullableString(MAX_SOURCE_NAME_BYTES)
                val expectedHash = ByteArray(SHA_BYTES).also(input::readFully).toHex()
                val rawSize = input.readInt().also {
                    requireStore(it in 0..MAX_GUIDE_BYTES)
                }
                val raw = ByteArray(rawSize).also(input::readFully)
                requireStore(input.read() == -1)
                requireStore(sha256(raw) == expectedHash)

                val parsed = BlockGuideParser.parse(raw)
                requireStore(parsed is GuideParseResult.Valid)
                val valid = (parsed as GuideParseResult.Valid).guide
                ActiveFileRead.Valid(
                    ActiveBlockGuide(
                        metadata = GuideMetadata(
                            sequence = sequence,
                            guideVersion = valid.guideVersion,
                            updatedAt = valid.updatedAt,
                            importedAtMillis = importedAt,
                            sizeBytes = raw.size,
                            sha256 = expectedHash,
                            sourceName = sourceName,
                            headers = valid.headers,
                        ),
                        content = valid.content,
                        body = valid.body,
                    ),
                )
            }
        } catch (error: Exception) {
            ActiveFileRead.Corrupted(error.readableStorageReason("guide actif"))
        }
    }

    private fun readReport(): ReportFileRead {
        if (!reportFile.exists()) return ReportFileRead.Absent
        return try {
            ensureBounded(reportFile, MAX_REPORT_RECORD_BYTES)
            DataInputStream(BufferedInputStream(FileInputStream(reportFile))).use { input ->
                input.requireMagic(REPORT_MAGIC)
                input.requireVersion()
                val sequence = input.readLong().also { requireStore(it > 0L) }
                val validatedAt = input.readLong()
                val accepted = input.readBoolean()
                val sourceName = input.readNullableString(MAX_SOURCE_NAME_BYTES)
                val candidateHash = input.readNullableString(SHA_HEX_LENGTH)?.also {
                    requireStore(it.length == SHA_HEX_LENGTH && it.all(::isLowerHex))
                }
                val issueCount = input.readInt().also { requireStore(it in 0..MAX_ISSUES) }
                val issues = buildList(issueCount) {
                    repeat(issueCount) {
                        val code = GuideIssueCode.valueOf(input.readString(MAX_CODE_BYTES))
                        val message = input.readString(MAX_MESSAGE_BYTES)
                        val rawLine = input.readInt()
                        requireStore(rawLine >= -1)
                        add(GuideValidationIssue(code, message, rawLine.takeIf { it >= 1 }))
                    }
                }
                requireStore(input.read() == -1)
                ReportFileRead.Valid(
                    GuideValidationReport(
                        sequence = sequence,
                        validatedAtMillis = validatedAt,
                        accepted = accepted,
                        sourceName = sourceName,
                        candidateSha256 = candidateHash,
                        issues = issues,
                    ),
                )
            }
        } catch (error: Exception) {
            ReportFileRead.Corrupted(error.readableStorageReason("rapport de validation"))
        }
    }

    private fun atomicWrite(target: File, encode: (DataOutputStream) -> Unit) {
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Creation du magasin impossible")
        }
        if (!directory.isDirectory) throw IOException("Le magasin n'est pas un repertoire")

        val temporary = File(directory, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { fileOutput ->
                val output = DataOutputStream(BufferedOutputStream(fileOutput))
                encode(output)
                output.flush()
                fileOutput.fd.sync()
            }
            promoter.promote(temporary.toPath(), target.toPath())
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun DataInputStream.requireMagic(expected: ByteArray) {
        val actual = ByteArray(expected.size)
        readFully(actual)
        requireStore(actual.contentEquals(expected))
    }

    private fun DataInputStream.requireVersion() {
        requireStore(readInt() == FORMAT_VERSION)
    }

    private fun DataOutputStream.writeNullableString(value: String?, maximumBytes: Int) {
        writeBoolean(value != null)
        if (value != null) writeString(value, maximumBytes)
    }

    private fun DataOutputStream.writeString(value: String, maximumBytes: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > maximumBytes) throw IOException("Texte interne trop long")
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readNullableString(maximumBytes: Int): String? =
        if (readBoolean()) readString(maximumBytes) else null

    private fun DataInputStream.readString(maximumBytes: Int): String {
        val size = readInt()
        requireStore(size in 0..maximumBytes)
        val bytes = ByteArray(size).also(::readFully)
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun ensureBounded(file: File, maximum: Long) {
        requireStore(file.length() in 1..maximum)
    }

    private fun requireStore(condition: Boolean) {
        if (!condition) throw IOException("Format du magasin invalide")
    }

    private fun Exception.readableStorageReason(subject: String): String = when (this) {
        is EOFException -> "$subject tronque"
        else -> "$subject illisible (${javaClass.simpleName})"
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val SHA_BYTES = 32
        private const val SHA_HEX_LENGTH = 64
        private const val MAX_SOURCE_NAME_BYTES = 512
        private const val MAX_CODE_BYTES = 64
        private const val MAX_MESSAGE_BYTES = 4_096
        private const val MAX_ISSUES = 32
        private const val MAX_ACTIVE_RECORD_BYTES = MAX_GUIDE_BYTES.toLong() + 2_048L
        private const val MAX_REPORT_RECORD_BYTES = 128L * 1024L
        private val ACTIVE_MAGIC = "ABGUIDE1".toByteArray(StandardCharsets.US_ASCII)
        private val REPORT_MAGIC = "ABRPORT1".toByteArray(StandardCharsets.US_ASCII)
    }
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

private fun hexToBytes(value: String): ByteArray {
    if (value.length % 2 != 0 || value.any { !isLowerHex(it) }) {
        throw IOException("Empreinte interne invalide")
    }
    return ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun isLowerHex(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f'
