package com.albugimed.blockerspike.guide

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object BlockGuideParser {
    private val multilineMarker = Regex("^[|>][+-]?$")
    private val canonicalDate = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")

    fun parse(bytes: ByteArray): GuideParseResult {
        // La borne protège aussi le travail du décodeur : un fichier local de
        // plusieurs Gio ne doit pas immobiliser l'application uniquement pour
        // choisir entre deux motifs de refus.
        if (bytes.size > MAX_GUIDE_BYTES) {
            return invalid(
                GuideIssueCode.TOO_LARGE,
                "Le fichier depasse la limite de 256 Kio.",
            )
        }

        val content = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            return invalid(
                GuideIssueCode.INVALID_UTF8,
                "Le fichier n'est pas un texte UTF-8 valide.",
            )
        }

        val lines = content.split('\n').map { it.removeSuffix("\r") }
        if (lines.firstOrNull() != "---") {
            return invalid(
                GuideIssueCode.HEADER_MISSING,
                "L'en-tete doit commencer par --- sur la premiere ligne.",
                line = 1,
            )
        }

        val closingIndex = lines.indexOfFirstFrom(1) { it == "---" }
        if (closingIndex < 0) {
            return invalid(
                GuideIssueCode.HEADER_UNTERMINATED,
                "L'en-tete ouvert en ligne 1 n'est pas referme par ---.",
                line = 1,
            )
        }

        val headers = linkedMapOf<String, String>()
        val keyLines = mutableMapOf<String, Int>()
        for (index in 1 until closingIndex) {
            val lineNumber = index + 1
            val rawLine = lines[index]
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            if (rawLine.first().isWhitespace()) {
                return invalid(
                    GuideIssueCode.MULTILINE_VALUE,
                    "Les structures imbriquees ou valeurs indentees ne sont pas acceptees.",
                    lineNumber,
                )
            }

            val separator = rawLine.indexOf(':')
            if (separator < 0) {
                return invalid(
                    GuideIssueCode.HEADER_LINE_INVALID,
                    "La ligne d'en-tete doit avoir la forme cle: valeur.",
                    lineNumber,
                )
            }
            val key = rawLine.substring(0, separator).trim()
            if (key.isEmpty()) {
                return invalid(
                    GuideIssueCode.HEADER_LINE_INVALID,
                    "La cle d'en-tete est vide.",
                    lineNumber,
                )
            }
            if (headers.containsKey(key)) {
                return invalid(
                    GuideIssueCode.DUPLICATE_KEY,
                    "La cle '$key' est declaree plusieurs fois.",
                    lineNumber,
                )
            }

            val scalar = parseScalar(rawLine.substring(separator + 1), lineNumber)
            if (scalar is ScalarResult.Invalid) return GuideParseResult.Invalid(listOf(scalar.issue))
            val value = (scalar as ScalarResult.Valid).value
            headers[key] = value
            keyLines[key] = lineNumber
        }

        val issues = mutableListOf<GuideValidationIssue>()
        val guideVersion = headers["guide_version"]
        when {
            guideVersion == null -> issues += GuideValidationIssue(
                GuideIssueCode.GUIDE_VERSION_MISSING,
                "La cle obligatoire guide_version manque.",
            )
            guideVersion.isBlank() -> issues += GuideValidationIssue(
                GuideIssueCode.GUIDE_VERSION_EMPTY,
                "guide_version doit etre une chaine non vide.",
                keyLines["guide_version"],
            )
            guideVersion.codePointCount(0, guideVersion.length) > 32 -> issues += GuideValidationIssue(
                GuideIssueCode.GUIDE_VERSION_TOO_LONG,
                "guide_version depasse 32 caracteres.",
                keyLines["guide_version"],
            )
        }

        val updatedAtRaw = headers["updated_at"]
        val updatedAt = when {
            updatedAtRaw == null -> {
                issues += GuideValidationIssue(
                    GuideIssueCode.UPDATED_AT_MISSING,
                    "La cle obligatoire updated_at manque.",
                )
                null
            }
            else -> try {
                if (!canonicalDate.matches(updatedAtRaw)) throw DateTimeParseException(
                    "Format non canonique",
                    updatedAtRaw,
                    0,
                )
                LocalDate.parse(updatedAtRaw, DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (_: DateTimeParseException) {
                issues += GuideValidationIssue(
                    GuideIssueCode.UPDATED_AT_INVALID,
                    "updated_at doit etre une vraie date au format AAAA-MM-JJ.",
                    keyLines["updated_at"],
                )
                null
            }
        }

        if (issues.isNotEmpty()) return GuideParseResult.Invalid(issues)

        val body = bodyAfterClosingDelimiter(content, closingIndex)
        return GuideParseResult.Valid(
            ParsedBlockGuide(
                content = content,
                body = body,
                headers = headers.toMap(),
                guideVersion = requireNotNull(guideVersion),
                updatedAt = requireNotNull(updatedAt),
            ),
        )
    }

    private fun parseScalar(raw: String, line: Int): ScalarResult {
        val withoutComment = stripInlineComment(raw).trim()
        if (multilineMarker.matches(withoutComment)) {
            return ScalarResult.Invalid(
                GuideValidationIssue(
                    GuideIssueCode.MULTILINE_VALUE,
                    "Les valeurs YAML multilignes ne sont pas acceptees.",
                    line,
                ),
            )
        }
        if (withoutComment.isEmpty()) return ScalarResult.Valid("")

        val quote = withoutComment.first()
        if (quote != '\'' && quote != '"') return ScalarResult.Valid(withoutComment)
        val decoded = decodeQuotedScalar(withoutComment, quote)
        if (decoded == null) {
            return ScalarResult.Invalid(
                GuideValidationIssue(
                    GuideIssueCode.QUOTED_VALUE_INVALID,
                    "La valeur entre guillemets n'est pas refermee proprement.",
                    line,
                ),
            )
        }
        if (decoded.any { it == '\n' || it == '\r' }) {
            return ScalarResult.Invalid(
                GuideValidationIssue(
                    GuideIssueCode.MULTILINE_VALUE,
                    "Les valeurs YAML multilignes ne sont pas acceptees.",
                    line,
                ),
            )
        }
        return ScalarResult.Valid(decoded)
    }

    private fun decodeQuotedScalar(value: String, quote: Char): String? {
        if (value.length < 2) return null
        val decoded = StringBuilder(value.length - 2)
        var index = 1
        while (index < value.length) {
            val char = value[index]
            if (char == quote) {
                if (quote == '\'' && index + 1 < value.length && value[index + 1] == '\'') {
                    decoded.append('\'')
                    index += 2
                    continue
                }
                return decoded.toString().takeIf { index == value.lastIndex }
            }
            if (quote == '"' && char == '\\') {
                if (index + 1 >= value.length) return null
                val escaped = value[index + 1]
                decoded.append(
                    when (escaped) {
                        '"' -> '"'
                        '\\' -> '\\'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        else -> return null
                    },
                )
                index += 2
                continue
            }
            decoded.append(char)
            index++
        }
        return null
    }

    /** Conserve les octets logiques du corps, notamment ses fins de ligne CRLF. */
    private fun bodyAfterClosingDelimiter(content: String, closingLineIndex: Int): String {
        var cursor = 0
        repeat(closingLineIndex + 1) {
            val newline = content.indexOf('\n', cursor)
            if (newline < 0) return ""
            cursor = newline + 1
        }
        return content.substring(cursor)
    }

    /** Un # colle au texte reste une valeur ; espace + # ouvre un commentaire YAML. */
    private fun stripInlineComment(raw: String): String {
        var quote: Char? = null
        raw.forEachIndexed { index, char ->
            if ((char == '\'' || char == '"') && !isBackslashEscaped(raw, index)) {
                quote = if (quote == null) char else if (quote == char) null else quote
            }
            if (char == '#' && quote == null && (index == 0 || raw[index - 1].isWhitespace())) {
                return raw.substring(0, index)
            }
        }
        return raw
    }

    private fun isBackslashEscaped(value: String, index: Int): Boolean {
        var cursor = index - 1
        var backslashes = 0
        while (cursor >= 0 && value[cursor] == '\\') {
            backslashes++
            cursor--
        }
        return backslashes % 2 == 1
    }

    private fun invalid(
        code: GuideIssueCode,
        message: String,
        line: Int? = null,
    ): GuideParseResult.Invalid = GuideParseResult.Invalid(
        listOf(GuideValidationIssue(code, message, line)),
    )

    private fun <T> List<T>.indexOfFirstFrom(start: Int, predicate: (T) -> Boolean): Int {
        for (index in start until size) if (predicate(this[index])) return index
        return -1
    }

    private sealed interface ScalarResult {
        data class Valid(val value: String) : ScalarResult
        data class Invalid(val issue: GuideValidationIssue) : ScalarResult
    }
}
