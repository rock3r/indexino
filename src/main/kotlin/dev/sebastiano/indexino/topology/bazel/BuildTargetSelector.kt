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
        for (index in openParen until content.length) {
            when (content[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return content.substring(match.range.first, index + 1)
                }
            }
        }
        return null
    }
}
