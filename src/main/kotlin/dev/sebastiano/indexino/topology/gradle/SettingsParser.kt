package dev.sebastiano.indexino.topology.gradle

internal object SettingsParser {
    fun parseIncludes(content: String): List<String> = parseDeclarations(content, "include")

    fun parseIncludedBuilds(content: String): List<String> =
        parseDeclarations(content, "includeBuild")

    private fun parseDeclarations(content: String, name: String): List<String> {
        val tokens = GradleScriptTokens.parse(content)
        return tokens.indices
            .filter { tokens[it] == name && tokens.getOrNull(it - 1) != "." }
            .flatMap { arguments(GradleScriptTokens.Cursor(tokens, it + 1)) }
            .distinct()
    }

    private fun arguments(cursor: GradleScriptTokens.Cursor): List<String> {
        val parenthesized = cursor.consume("(")
        val arguments = mutableListOf<String>()
        do {
            val project = cursor.consume("project", "(")
            val argument = cursor.readLiteral() ?: return emptyList()
            if (project && !cursor.consume(")")) return emptyList()
            arguments += argument
        } while (cursor.consume(",") && cursor.peek() != ")")
        if (parenthesized && !cursor.consume(")")) return emptyList()
        if (!parenthesized && cursor.peek() in setOf("+", ".", "?")) return emptyList()
        return arguments
    }

    /** Only literal file(...) mappings; Gradle expressions are never evaluated. */
    fun parseProjectDirectories(content: String): Map<String, String> {
        val tokens = GradleScriptTokens.parse(content)
        return tokens.indices
            .mapNotNull { projectDirectory(GradleScriptTokens.Cursor(tokens, it)) }
            .toMap()
    }

    private fun projectDirectory(cursor: GradleScriptTokens.Cursor): Pair<String, String>? {
        if (!cursor.consume("project", "(")) return null
        val module = cursor.readLiteral() ?: return null
        if (!cursor.consume(")", ".", "projectDir", "=", "file", "(")) return null
        val directory = cursor.readLiteral() ?: return null
        if (!cursor.consume(")")) return null
        if (cursor.peek() in setOf("+", ".", "?")) return null
        return module to directory
    }
}
