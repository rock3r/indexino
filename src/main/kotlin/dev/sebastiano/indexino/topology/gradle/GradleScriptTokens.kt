package dev.sebastiano.indexino.topology.gradle

/**
 * Lexical tokens only: strings stay opaque so examples inside strings cannot become declarations.
 */
internal object GradleScriptTokens {
    private val token =
        Regex(
            "\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|" +
                "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|[A-Za-z_][A-Za-z_0-9]*|[^\\s]"
        )

    fun parse(content: String): List<String> {
        val tokens = mutableListOf<String>()
        var index = 0
        while (index < content.length) {
            when {
                content.startsWith("//", index) -> {
                    index = content.indexOf('\n', index).takeIf { it >= 0 } ?: content.length
                }
                content.startsWith("/*", index) -> {
                    var depth = 1
                    index += 2
                    while (index < content.length && depth > 0) {
                        when {
                            content.startsWith("/*", index) -> {
                                depth++
                                index += 2
                            }
                            content.startsWith("*/", index) -> {
                                depth--
                                index += 2
                            }
                            else -> index++
                        }
                    }
                }
                content[index].isWhitespace() -> index++
                else -> {
                    val match = token.matchAt(content, index) ?: break
                    tokens += match.value
                    index = match.range.last + 1
                }
            }
        }
        return tokens
    }

    fun literal(token: String?): String? {
        if (token == null || token.length < 2 || token.first() !in "\"'") return null
        if (token.last() != token.first() || token.startsWith("\"\"\"") || token.startsWith("'''"))
            return null
        return token.substring(1, token.lastIndex).takeUnless { '\\' in it || '$' in it }
    }

    class Cursor(private val tokens: List<String>, private var index: Int) {
        fun peek(): String? = tokens.getOrNull(index)

        fun consume(vararg expected: String): Boolean {
            if (!expected.indices.all { tokens.getOrNull(index + it) == expected[it] }) return false
            index += expected.size
            return true
        }

        fun readLiteral(): String? = literal(peek())?.also { index++ }

        fun readIdentifier(): String? = peek()?.takeIf { it.matches(identifier) }?.also { index++ }
    }

    private val identifier = Regex("[A-Za-z_][A-Za-z_0-9]*")
}
