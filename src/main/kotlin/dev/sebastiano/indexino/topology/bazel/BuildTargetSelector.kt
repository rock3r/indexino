package dev.sebastiano.indexino.topology.bazel

internal object BuildTargetSelector {
    private val RULE_CALL = Regex("""(?m)^\s*[A-Za-z_][A-Za-z0-9_.]*\s*\(""")
    private val NAME_ASSIGNMENT = Regex("""(?<![A-Za-z0-9_])name\s*=\s*["']([^"']+)["']""")
    private val SOURCE_ATTRIBUTE = Regex("""(?<![A-Za-z0-9_])(?:srcs|resource_files)\s*=""")
    private val ACTUAL_ATTRIBUTE = Regex("""(?<![A-Za-z0-9_])actual\s*=""")
    private val ACTUAL_FILE = Regex("""(?m)^(\s*)actual\s*=\s*["']([^"']+\.(?:kt|java|xml))["']""")
    private val SOURCE_LABEL = Regex("""["'](?::([^"']+)|//([^:"']*):([^"']+))["']""")
    private val INDEXABLE_EXTENSIONS = setOf("kt", "java", "xml")

    fun select(content: String, targetName: String, packagePath: String): String {
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
            sourceLabels(rule, packagePath).filter { it in rulesByName }.forEach(::addRule)
        }
        addRule(targetName)
        return selected.values.joinToString("\n") { rule -> normalizeFileLabels(rule, packagePath) }
    }

    private fun ruleName(rule: String): String? {
        val assignment =
            NAME_ASSIGNMENT.findAll(rule).firstOrNull { match ->
                isTopLevelCode(rule, match.range.first)
            }
        return assignment?.groupValues?.get(1)
    }

    private fun sourceLabels(rule: String, packagePath: String): Sequence<String> =
        sourceAttributes(rule)
            .filter { match -> isTopLevelCode(rule, match.range.first) }
            .flatMap { match ->
                val valueStart = match.range.last + 1
                val valueEnd = BuildFileSrcsParser.findSrcsValueEnd(rule, valueStart) ?: rule.length
                val value = rule.substring(valueStart, valueEnd)
                SOURCE_LABEL.findAll(value)
                    .filterNot { label ->
                        BuildFileComments.isCommentedOutInBlock(value, label.range.first)
                    }
                    .mapNotNull { label -> sourceTargetName(label, packagePath) }
            }

    private fun sourceAttributes(rule: String): Sequence<MatchResult> =
        if (rule.trimStart().startsWith("alias(")) {
            ACTUAL_ATTRIBUTE.findAll(rule)
        } else {
            SOURCE_ATTRIBUTE.findAll(rule)
        }

    private fun sourceTargetName(label: MatchResult, packagePath: String): String? {
        val localName = label.groupValues[1]
        val canonicalPackage = label.groupValues[2]
        val canonicalName = label.groupValues[3]
        val name = localName.ifEmpty { canonicalName.takeIf { canonicalPackage == packagePath } }
        return name?.takeUnless(::isIndexableFile)
    }

    private fun normalizeFileLabels(rule: String, packagePath: String): String {
        val normalized =
            SOURCE_LABEL.replace(rule) { label ->
                val name = sourceTargetName(label, packagePath)
                if (name != null) {
                    label.value
                } else {
                    val fileName = label.groupValues[1].ifEmpty { label.groupValues[3] }
                    val canonicalPackage = label.groupValues[2]
                    if (
                        isIndexableFile(fileName) &&
                            (canonicalPackage.isEmpty() || canonicalPackage == packagePath)
                    ) {
                        "\"$fileName\""
                    } else if (isIndexableFile(fileName) && canonicalPackage.isNotEmpty()) {
                        "\"//$canonicalPackage/$fileName\""
                    } else {
                        label.value
                    }
                }
            }
        return if (rule.trimStart().startsWith("alias(")) {
            ACTUAL_FILE.replace(normalized) { match ->
                "${match.groupValues[1]}srcs = [\"${match.groupValues[2]}\"]"
            }
        } else {
            normalized
        }
    }

    private fun isIndexableFile(name: String): Boolean =
        name.substringAfterLast('.', missingDelimiterValue = "") in INDEXABLE_EXTENSIONS

    private fun isTopLevelCode(rule: String, position: Int): Boolean {
        var depth = 0
        var inString: Char? = null
        var escaped = false
        var inComment = false
        for (index in 0 until position) {
            val char = rule[index]
            when {
                inComment -> inComment = char != '\n'
                inString != null ->
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == inString -> inString = null
                    }
                char == '#' -> inComment = true
                char == '"' || char == '\'' -> inString = char
                char == '(' -> depth++
                char == ')' -> depth--
            }
        }
        return depth == 1 && inString == null && !inComment
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
