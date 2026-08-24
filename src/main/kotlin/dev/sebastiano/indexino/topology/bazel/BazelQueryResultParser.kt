package dev.sebastiano.indexino.topology.bazel

internal object BazelQueryResultParser {
    private val sourceExtensions = setOf("kt", "java", "xml")
    private val resourcePath =
        Regex(
            "(?:^|/)(?:src/[^/]+/)?" +
                "(?:res|resources|composeResources|[^/]+[_-]res|[^/]+[_-]resources)/"
        )

    fun parseKotlinSourcePaths(lines: Iterable<String>): List<String> = lines.mapNotNull { line ->
        val trimmed = line.trim()
        if (!trimmed.startsWith("//")) return@mapNotNull null
        val withoutPrefix = trimmed.removePrefix("//")
        val separator = withoutPrefix.indexOf(':')
        if (separator < 0) return@mapNotNull null
        val packagePath = withoutPrefix.substring(0, separator)
        val targetPath = withoutPrefix.substring(separator + 1)
        val path = if (packagePath.isBlank()) targetPath else "$packagePath/$targetPath"
        val extension = path.substringAfterLast('.', "")
        if (extension in sourceExtensions || resourcePath.containsMatchIn(path)) path else null
    }
}
