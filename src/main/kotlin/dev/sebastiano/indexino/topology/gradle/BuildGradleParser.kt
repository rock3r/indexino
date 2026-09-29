package dev.sebastiano.indexino.topology.gradle

internal object BuildGradleParser {
    private val configurations =
        setOf("implementation", "api", "compileOnly", "runtimeOnly", "testImplementation")

    fun parseProjectDependencies(
        content: String,
        includedModules: List<String> = emptyList(),
    ): List<String> {
        val tokens = GradleScriptTokens.parse(content)
        val accessors = includedModules.groupBy(::accessor)
        return tokens.indices
            .filter { tokens[it] in configurations }
            .mapNotNull { index ->
                dependency(GradleScriptTokens.Cursor(tokens, index + 1), accessors)
            }
            .distinct()
    }

    private fun accessor(module: String): String =
        module.removePrefix(":").split(':').joinToString(".") { segment ->
            segment
                .split('-', '_')
                .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
                .replaceFirstChar(Char::lowercaseChar)
        }

    private fun dependency(
        cursor: GradleScriptTokens.Cursor,
        accessors: Map<String, List<String>>,
    ): String? {
        val parenthesized = cursor.consume("(")
        val path =
            when {
                cursor.consume("project", "(") -> {
                    val literal = cursor.readLiteral() ?: return null
                    if (!cursor.consume(")")) return null
                    literal
                }
                cursor.consume("projects") -> accessorDependency(cursor, accessors)
                else -> null
            }
        if (parenthesized && !cursor.consume(")")) return null
        return path
    }

    private fun accessorDependency(
        cursor: GradleScriptTokens.Cursor,
        accessors: Map<String, List<String>>,
    ): String? {
        val segments = mutableListOf<String>()
        while (cursor.consume(".")) {
            segments += cursor.readIdentifier() ?: return null
        }
        val name = segments.joinToString(".")
        val candidates = accessors[name].orEmpty()
        require(candidates.size <= 1) { "Ambiguous Gradle project accessor: $name" }
        return candidates.singleOrNull()
    }
}
