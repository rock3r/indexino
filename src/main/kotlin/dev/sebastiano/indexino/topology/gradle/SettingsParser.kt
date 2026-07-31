package dev.sebastiano.indexino.topology.gradle

internal object SettingsParser {
    private val includePattern = Regex("""include\s*\(([^)]+)\)""")

    fun parseIncludes(content: String): List<String> {
        val modules = mutableListOf<String>()
        for (match in includePattern.findAll(content)) {
            val args = match.groupValues[1]
            projectPattern.findAll(args).forEach { projectMatch ->
                modules += projectMatch.groupValues[1]
            }
            quotedModulePattern.findAll(args).forEach { quotedMatch ->
                modules += quotedMatch.groupValues[1]
            }
        }
        return modules.distinct()
    }

    fun parseIncludedBuilds(content: String): List<String> =
        includeBuildPattern
            .findAll(withoutComments(content))
            .map { it.groupValues[1] }
            .distinct()
            .toList()

    private fun withoutComments(content: String): String {
        val result = StringBuilder(content.length)
        var index = 0
        var quote: Char? = null
        while (index < content.length) {
            if (quote != null) {
                val quoted = appendQuotedCharacter(content, result, index, quote)
                index = quoted.first
                quote = quoted.second
                continue
            }
            when (val current = content[index]) {
                '\'',
                '"' -> {
                    quote = current
                    result.append(current)
                    index++
                }
                '/' -> {
                    index =
                        skipComment(content, index)
                            ?: run {
                                result.append(current)
                                index + 1
                            }
                }
                else -> {
                    result.append(current)
                    index++
                }
            }
        }
        return result.toString()
    }

    private fun appendQuotedCharacter(
        content: String,
        result: StringBuilder,
        index: Int,
        quote: Char,
    ): Pair<Int, Char?> {
        val current = content[index]
        result.append(current)
        if (current == '\\' && index + 1 < content.length) {
            result.append(content[index + 1])
            return index + 2 to quote
        }
        return index + 1 to quote.takeUnless { current == it }
    }

    private fun skipComment(content: String, index: Int): Int? =
        when (content.getOrNull(index + 1)) {
            '/' ->
                content.indexOf('\n', index).let { newline ->
                    if (newline == -1) content.length else newline
                }
            '*' ->
                content.indexOf("*/", index + 2).let { end ->
                    if (end == -1) content.length else end + 2
                }
            else -> null
        }

    private val includeBuildPattern =
        Regex("""includeBuild\s*(?:\(\s*)?['\"]([^'\"]+)['\"]\s*\)?""")
    private val projectPattern = Regex("""project\s*\(\s*"([^"]+)"\s*\)""")
    private val quotedModulePattern = Regex(""""([^"]+)"""")
}
