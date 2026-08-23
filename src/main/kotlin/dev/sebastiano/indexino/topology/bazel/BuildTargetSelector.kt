package dev.sebastiano.indexino.topology.bazel

internal object BuildTargetSelector {
    private val RULE_CALL = Regex("""(?m)^\s*[A-Za-z_][A-Za-z0-9_.]*\s*\(""")
    private val NAME_ASSIGNMENT = Regex("""(?<![A-Za-z0-9_])name\s*=\s*["']([^"']+)["']""")
    private val SOURCE_ATTRIBUTE = Regex("""(?<![A-Za-z0-9_])(?:srcs|resource_files)\s*=""")
    private val LOCAL_LABEL = Regex("""["']:([A-Za-z0-9_.+\-]+)["']""")

    fun select(content: String, targetName: String): String {
        val rulesByName =
            RULE_CALL.findAll(content)
                .mapNotNull { match -> ruleAt(content, match) }
                .mapNotNull { rule -> ruleName(rule)?.let { name -> name to rule } }
                .toMap()
        check(targetName in rulesByName) { "Target '$targetName' was not found in BUILD file" }

        val selected = linkedMapOf<String, String>()
        fun addRule(name: String) {
            if (name in selected) return
            val rule =
                rulesByName[name]
                    ?: error("Referenced source target ':$name' was not found in BUILD file")
            selected[name] = rule
            sourceLabels(rule).forEach(::addRule)
        }
        addRule(targetName)
        return selected.values.joinToString("\n")
    }

    private fun ruleName(rule: String): String? {
        val assignment =
            NAME_ASSIGNMENT.findAll(rule).firstOrNull { match ->
                !BuildFileComments.isCommentedOutInBlock(rule, match.range.first)
            }
        return assignment?.groupValues?.get(1)
    }

    private fun sourceLabels(rule: String): Sequence<String> =
        SOURCE_ATTRIBUTE.findAll(rule)
            .filterNot { match -> BuildFileComments.isCommentedOutInBlock(rule, match.range.first) }
            .flatMap { match ->
                val valueStart = match.range.last + 1
                val valueEnd = BuildFileSrcsParser.findSrcsValueEnd(rule, valueStart) ?: rule.length
                LOCAL_LABEL.findAll(rule.substring(valueStart, valueEnd)).map { it.groupValues[1] }
            }

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
