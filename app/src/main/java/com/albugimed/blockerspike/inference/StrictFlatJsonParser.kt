package com.albugimed.blockerspike.inference

/** Strict RFC-8259 parser for the flat string/number object emitted by T3. */
internal object StrictFlatJsonParser {
    fun parse(text: String): Map<String, Value> = Parser(text).parseObject()

    sealed interface Value
    data class StringValue(val value: String) : Value
    data class NumberValue(val token: String) : Value

    private class Parser(private val source: String) {
        private var index = 0

        fun parseObject(): Map<String, Value> {
            skipWhitespace()
            expect('{')
            skipWhitespace()
            val values = linkedMapOf<String, Value>()
            if (consumeIf('}')) {
                requireEnd()
                return values
            }

            while (true) {
                val key = parseString()
                require(key !in values) { "Duplicate JSON key" }
                skipWhitespace()
                expect(':')
                skipWhitespace()
                val next = peek()
                val value = when {
                    next == '"' -> StringValue(parseString())
                    next == '-' || (next != null && next in '0'..'9') ->
                        NumberValue(parseNumber())
                    else -> error("Only JSON strings and numbers are accepted")
                }
                values[key] = value
                skipWhitespace()
                when {
                    consumeIf('}') -> {
                        requireEnd()
                        return values
                    }
                    consumeIf(',') -> {
                        skipWhitespace()
                        require(peek() != '}') { "Trailing comma" }
                    }
                    else -> error("Expected comma or object end")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < source.length) {
                val character = source[index++]
                when {
                    character == '"' -> return result.toString()
                    character == '\\' -> result.append(parseEscape())
                    character.code < 0x20 -> error("Unescaped control character")
                    else -> result.append(character)
                }
            }
            error("Unterminated JSON string")
        }

        private fun parseEscape(): Char {
            require(index < source.length) { "Incomplete JSON escape" }
            return when (val escaped = source[index++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    require(index + 4 <= source.length) { "Incomplete Unicode escape" }
                    val token = source.substring(index, index + 4)
                    require(token.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
                        "Invalid Unicode escape"
                    }
                    index += 4
                    token.toInt(16).toChar()
                }
                else -> error("Invalid JSON escape")
            }
        }

        private fun parseNumber(): String {
            val start = index
            consumeIf('-')
            require(index < source.length) { "Incomplete JSON number" }
            when (val first = source[index]) {
                '0' -> {
                    index++
                    require(!peek().isAsciiDigit()) { "Leading zero" }
                }
                in '1'..'9' -> {
                    index++
                    while (peek().isAsciiDigit()) index++
                }
                else -> error("Invalid JSON number: $first")
            }
            if (consumeIf('.')) {
                require(peek().isAsciiDigit()) { "Missing fraction digits" }
                while (peek().isAsciiDigit()) index++
            }
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                require(peek().isAsciiDigit()) { "Missing exponent digits" }
                while (peek().isAsciiDigit()) index++
            }
            return source.substring(start, index)
        }

        private fun skipWhitespace() {
            while (peek()?.let(JSON_WHITESPACE::contains) == true) index++
        }

        private fun requireEnd() {
            skipWhitespace()
            require(index == source.length) { "Text after JSON object" }
        }

        private fun peek(): Char? = source.getOrNull(index)

        private fun Char?.isAsciiDigit(): Boolean = this != null && this in '0'..'9'

        private fun consumeIf(expected: Char): Boolean {
            if (peek() != expected) return false
            index++
            return true
        }

        private fun expect(expected: Char) {
            require(consumeIf(expected)) { "Expected '$expected'" }
        }
    }

    private val JSON_WHITESPACE = setOf(' ', '\t', '\r', '\n')
}
