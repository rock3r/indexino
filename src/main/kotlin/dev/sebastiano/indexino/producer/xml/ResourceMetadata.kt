package dev.sebastiano.indexino.producer.xml

import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.IndexedSource
import java.nio.file.Files
import java.nio.file.Path

internal object ResourceMetadata {
    val RESOURCE_TYPES =
        setOf(
            "anim",
            "animator",
            "array",
            "attr",
            "bool",
            "color",
            "dimen",
            "drawable",
            "font",
            "fraction",
            "id",
            "integer",
            "interpolator",
            "layout",
            "menu",
            "mipmap",
            "navigation",
            "plurals",
            "raw",
            "string",
            "style",
            "styleable",
            "transition",
            "xml",
        )

    private val RESOURCE_PATH =
        Regex(
            "(?:^|/)(?:src/[^/]+/)?" +
                "(?:res|resources|composeResources|[^/]+[_-]res|[^/]+[_-]resources)/" +
                "([^/]+)/([^/]+)\\.xml$"
        )

    fun resourceFromPath(relativePath: String): ResourcePath? {
        val match = RESOURCE_PATH.find(relativePath) ?: return null
        val directory = match.groupValues[1]
        return ResourcePath(
            type = directory.substringBefore('-'),
            name = match.groupValues[2],
            qualifiers = directory.substringAfter('-', ""),
        )
    }

    fun isResourceXml(relativePath: String): Boolean = resourceFromPath(relativePath) != null

    fun isMetadataPath(relativePath: String): Boolean =
        relativePath == "build.gradle" ||
            relativePath == "build.gradle.kts" ||
            relativePath.endsWith("/build.gradle") ||
            relativePath.endsWith("/build.gradle.kts") ||
            relativePath.endsWith("AndroidManifest.xml")

    fun moduleDirectory(relativePath: String): String {
        val resourceMatch = RESOURCE_PATH.find(relativePath)
        if (resourceMatch != null) {
            return relativePath.substring(0, resourceMatch.range.first).trimEnd('/')
        }
        return when {
            relativePath.startsWith("src/") -> ""
            "/src/" in relativePath -> relativePath.substringBefore("/src/")
            relativePath.startsWith("res/") -> ""
            "/res/" in relativePath -> relativePath.substringBefore("/res/")
            else -> relativePath.substringBeforeLast('/', "")
        }
    }

    fun resourcePackage(context: IndexBuildContext, indexedSource: IndexedSource): String? {
        val metadata =
            metadataPathsForResource(indexedSource.path).mapNotNull { metadataPath ->
                metadataContent(context, indexedSource, metadataPath)?.let { metadataPath to it }
            }
        return metadata.firstNotNullOfOrNull { (_, content) ->
            GRADLE_NAMESPACE.find(content)?.groupValues?.get(1)
        }
            ?: metadata.firstNotNullOfOrNull { (path, content) ->
                if (path.endsWith("AndroidManifest.xml")) {
                    MANIFEST_PACKAGE.find(content)?.groupValues?.get(1)
                } else {
                    null
                }
            }
            ?: metadata.firstNotNullOfOrNull { (_, content) ->
                GRADLE_APPLICATION_ID.find(content)?.groupValues?.get(1)
            }
    }

    fun metadataPathsForResource(relativePath: String): List<String> {
        val moduleDirectory = moduleDirectory(relativePath)
        return listOf(
            metadataPath(moduleDirectory, "build.gradle.kts"),
            metadataPath(moduleDirectory, "build.gradle"),
            metadataPath(moduleDirectory, "src/main/AndroidManifest.xml"),
            metadataPath(moduleDirectory, "src/androidMain/AndroidManifest.xml"),
            metadataPath(moduleDirectory, "AndroidManifest.xml"),
        )
    }

    fun metadataModule(relativePath: String): String? =
        when {
            relativePath == "build.gradle" || relativePath == "build.gradle.kts" -> ""
            relativePath.endsWith("/build.gradle") || relativePath.endsWith("/build.gradle.kts") ->
                relativePath.substringBeforeLast('/')
            relativePath.endsWith("AndroidManifest.xml") -> moduleDirectory(relativePath)
            else -> null
        }

    fun additionalMetadataPaths(originRoot: Path, sourcePaths: List<String>): List<String> {
        val existing = sourcePaths.toSet()
        return sourcePaths
            .filter(::isResourceXml)
            .flatMap(::metadataPathsForResource)
            .distinct()
            .filter { it !in existing && Files.isRegularFile(originRoot.resolve(it)) }
            .sorted()
    }

    private fun metadataContent(
        context: IndexBuildContext,
        indexedSource: IndexedSource,
        metadataPath: String,
    ): String? {
        val indexedMetadata =
            context.sources.firstOrNull {
                it.originId == indexedSource.originId && it.path == metadataPath
            }
        return indexedMetadata?.let { runCatching { context.readSource(it) }.getOrNull() }
            ?: indexedSource.originRoot.resolve(metadataPath).let { path ->
                if (Files.isRegularFile(path)) {
                    runCatching { Files.readString(path) }.getOrNull()
                } else {
                    null
                }
            }
    }

    private fun metadataPath(moduleDirectory: String, relativePath: String): String =
        if (moduleDirectory.isEmpty()) relativePath else "$moduleDirectory/$relativePath"

    private val MANIFEST_PACKAGE = Regex("\\bpackage\\s*=\\s*[\"']([^\"']+)[\"']")
    private val GRADLE_NAMESPACE = Regex("\\bnamespace\\s*(?:=\\s*)?[\"']([^\"']+)[\"']")
    private val GRADLE_APPLICATION_ID = Regex("\\bapplicationId\\s*(?:=\\s*)?[\"']([^\"']+)[\"']")
}

internal data class ResourcePath(val type: String, val name: String, val qualifiers: String)
