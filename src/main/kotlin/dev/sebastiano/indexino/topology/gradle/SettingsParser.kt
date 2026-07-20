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

    private val projectPattern = Regex("""project\s*\(\s*"([^"]+)"\s*\)""")
    private val quotedModulePattern = Regex(""""([^"]+)"""")
}
