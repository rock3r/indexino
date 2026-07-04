package com.kotlincodeindex.topology.gradle

object BuildGradleParser {
    private val projectDepPattern = Regex(
        """(?:implementation|api|compileOnly|runtimeOnly|testImplementation)\s*\(\s*project\s*\(\s*"([^"]+)"\s*\)\s*\)""",
    )

    fun parseProjectDependencies(content: String): List<String> =
        projectDepPattern.findAll(content).map { it.groupValues[1] }.distinct().toList()
}
