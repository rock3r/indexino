package dev.sebastiano.indexino.topology.bazel

internal object BuildTargetSelector {
    private val RULE_CALL = Regex("""(?m)^\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\(""")
    private val ALIAS_CALL = Regex("""^\s*alias\s*\(""")
    private val NAME_ASSIGNMENT = Regex("""(?<![A-Za-z0-9_])name\s*=\s*["']([^"']+)["']""")
    private val SOURCE_ATTRIBUTE = Regex("""(?<![A-Za-z0-9_])(?:srcs|resource_files)\s*=""")
    private val ACTUAL_ATTRIBUTE = Regex("""(?<![A-Za-z0-9_])actual\s*=""")
    private val ACTUAL_FILE = Regex("""actual\s*=\s*["']([^"']+\.(?:kt|java|xml))["']""")
    private val SOURCE_LABEL = Regex("""["'](?::([^"']+)|//([^:"']*)(?::([^"']+))?)["']""")
    private val TRIPLE_QUOTED_STRING = Regex("(?s)'''.*?'''|\"\"\".*?\"\"\"")
    private val INDEXABLE_EXTENSIONS = setOf("kt", "java", "xml")

    fun select(content: String, targetName: String, packagePath: String): String {
        val rulesByName =
            RULE_CALL.findAll(content)
                .filter { match -> isCodeAt(content, match.range.first, expectedDepth = 0) }
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
            sourceLabels(rule, packagePath)
                .filter { label -> rulesByName[label]?.let(::isSourceAggregator) == true }
                .forEach(::addRule)
        }
        addRule(targetName)
        return selected.values.joinToString("\n") { rule ->
            normalizeFileLabels(TRIPLE_QUOTED_STRING.replace(rule, "\"\""), packagePath)
        }
    }

    private fun ruleName(rule: String): String? {
        val assignment =
            NAME_ASSIGNMENT.findAll(rule).firstOrNull { match ->
                isCodeAt(rule, match.range.first, expectedDepth = 1)
            }
        return assignment?.groupValues?.get(1)
    }

    private fun sourceLabels(rule: String, packagePath: String): Sequence<String> =
        sourceAttributes(rule)
            .filter { match -> isCodeAt(rule, match.range.first, expectedDepth = 1) }
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
        if (isAlias(rule)) {
            ACTUAL_ATTRIBUTE.findAll(rule)
        } else {
            SOURCE_ATTRIBUTE.findAll(rule)
        }

    private fun sourceTargetName(label: MatchResult, packagePath: String): String? {
        val localName = label.groupValues[1]
        val canonicalPackage = label.groupValues[2]
        val canonicalName = label.groupValues[3]
        val name = localName.ifEmpty {
            canonicalName
                .ifEmpty { canonicalPackage.substringAfterLast('/') }
                .takeIf { canonicalPackage == packagePath }
        }
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
        return if (isAlias(rule)) {
            val actual =
                ACTUAL_FILE.findAll(normalized).firstOrNull { match ->
                    isCodeAt(normalized, match.range.first, expectedDepth = 1)
                }
            if (actual == null) {
                normalized
            } else {
                normalized.replaceRange(actual.range, "srcs = [\"${actual.groupValues[1]}\"]")
            }
        } else {
            normalized
        }
    }

    private fun isIndexableFile(name: String): Boolean =
        name.substringAfterLast('.', missingDelimiterValue = "") in INDEXABLE_EXTENSIONS

    private fun isAlias(rule: String): Boolean = ALIAS_CALL.containsMatchIn(rule)

    private fun isSourceAggregator(rule: String): Boolean {
        val callName = RULE_CALL.find(rule)?.groupValues?.get(1)?.substringAfterLast('.')
        return callName == "filegroup" || callName == "alias"
    }

    @Suppress("CyclomaticComplexMethod")
    private fun isCodeAt(text: String, position: Int, expectedDepth: Int): Boolean {
        var depth = 0
        var stringDelimiter: String? = null
        var escaped = false
        var inComment = false
        var index = 0
        while (index < position) {
            val char = text[index]
            when {
                inComment -> inComment = char != '\n'
                stringDelimiter != null ->
                    when {
                        escaped -> escaped = false
                        stringDelimiter.length == 1 && char == '\\' -> escaped = true
                        text.startsWith(stringDelimiter, index) -> {
                            index += stringDelimiter.length - 1
                            stringDelimiter = null
                        }
                    }
                char == '#' -> inComment = true
                char == '"' || char == '\'' -> {
                    val triple = char.toString().repeat(3)
                    stringDelimiter =
                        if (text.startsWith(triple, index)) triple else char.toString()
                    index += stringDelimiter.length - 1
                }
                char == '(' -> depth++
                char == ')' -> depth--
            }
            index++
        }
        return depth == expectedDepth && stringDelimiter == null && !inComment
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
