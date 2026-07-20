package dev.sebastiano.indexino.topology.gradle

internal object BuildGradleParser {
    private val projectDepPattern =
        Regex(
            """
            (?:implementation|api|compileOnly|runtimeOnly|testImplementation)
            \s*\(\s*project\s*\(\s*"([^"]+)"\s*\)\s*\)
            """
                .trimIndent()
                .replace("\n", "")
        )

    fun parseProjectDependencies(content: String): List<String> =
        projectDepPattern.findAll(content).map { it.groupValues[1] }.distinct().toList()
}
