package dev.sebastiano.indexino.topology.bazel

internal object BuildTargetSelector {
    private val RULE_CALL = Regex("""(?m)^\s*[A-Za-z_][A-Za-z0-9_.]*\s*\(""")
    private val NAME_ASSIGNMENT = Regex("""name\s*=\s*["']([^"']+)["']""")

    fun select(content: String, targetName: String): String =
        RULE_CALL.findAll(content)
            .mapNotNull { match -> ruleAt(content, match) }
            .firstOrNull { rule ->
                val name =
                    NAME_ASSIGNMENT.find(rule)?.takeUnless { assignment ->
                        BuildFileComments.isCommentedOutInBlock(rule, assignment.range.first)
                    }
                name?.groupValues?.get(1) == targetName
            } ?: error("Target '$targetName' was not found in BUILD file")

    private fun ruleAt(content: String, match: MatchResult): String? {
        if (BuildFileComments.isCommentedOutInBlock(content, match.range.first)) return null
        val openParen = match.range.last
        var depth = 0
        var inString: Char? = null
        var escaped = false
        var inComment = false
        for (index in openParen until content.length) {
            val char = content[index]
            when {
                inComment -> inComment = char != '\n'
                inString != null -> {
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == inString -> inString = null
                    }
                }
                char == '#' -> inComment = true
                char == '"' || char == '\'' -> inString = char
                char == '(' -> depth++
                char == ')' -> {
                    depth--
                    if (depth == 0) return content.substring(match.range.first, index + 1)
                }
            }
        }
        return null
    }
}
