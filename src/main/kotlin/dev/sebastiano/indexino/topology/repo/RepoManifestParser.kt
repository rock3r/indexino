package dev.sebastiano.indexino.topology.repo

import java.io.StringReader
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

internal data class RepoProject(val name: String, val path: String, val revision: String?)

internal data class RepoManifest(val projects: List<RepoProject>) {
    val digest: String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                projects
                    .joinToString("\n") { "${it.name}\t${it.path}\t${it.revision.orEmpty()}" }
                    .toByteArray()
            )
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}

/** Parses the resolved Android repo manifest into repository identities and local mounts. */
internal object RepoManifestParser {
    fun parse(manifestPath: Path): RepoManifest {
        val manifestDirectory = manifestPath.parent.toRealPath()
        val projects = linkedMapOf<String, RepoProject>()
        val visited = mutableSetOf<Path>()

        fun apply(document: Path) {
            val canonicalDocument = document.toRealPath()
            if (!visited.add(canonicalDocument)) return
            val directives = parseDirectives(canonicalDocument.readText())
            directives.includes.forEach { include ->
                val includedDocument = canonicalDocument.parent.resolve(include).normalize()
                require(includedDocument.startsWith(manifestDirectory)) {
                    "repo manifest include escapes manifest root: $include"
                }
                require(includedDocument.exists()) {
                    "repo manifest include is unavailable: $include"
                }
                apply(includedDocument)
            }
            directives.projects.forEach { project -> projects[project.name] = project }
            directives.removedProjects.forEach(projects::remove)
            directives.extensions.forEach { extension ->
                val project = projects[extension.name] ?: return@forEach
                projects[extension.name] =
                    project.copy(
                        path = extension.path ?: project.path,
                        revision = extension.revision ?: project.revision,
                    )
            }
        }

        apply(manifestPath)
        val localManifests = manifestDirectory.resolve("local_manifests")
        if (localManifests.exists() && localManifests.isDirectory()) {
            localManifests.listDirectoryEntries("*.xml").sorted().forEach(::apply)
        }
        return RepoManifest(projects.values.sortedBy { it.name })
    }

    fun parse(content: String): RepoManifest =
        RepoManifest(parseDirectives(content).projects.sortedBy { it.name })

    private fun parseDirectives(content: String): RepoManifestDirectives {
        val reader =
            XMLInputFactory.newFactory()
                .apply {
                    setProperty(XMLInputFactory.SUPPORT_DTD, false)
                    setProperty("javax.xml.stream.isSupportingExternalEntities", false)
                }
                .createXMLStreamReader(StringReader(content))
        var defaultRevision: String? = null
        val projects = mutableListOf<RepoProject>()
        val includes = mutableListOf<String>()
        val removedProjects = mutableListOf<String>()
        val extensions = mutableListOf<RepoProjectExtension>()
        while (reader.hasNext()) {
            if (reader.next() != XMLStreamConstants.START_ELEMENT) continue
            when (reader.localName) {
                "default" -> defaultRevision = reader.getAttributeValue(null, "revision")
                "include" -> includes += requiredAttribute(reader, "name", "repo manifest include")
                "project" -> {
                    val name = requiredAttribute(reader, "name", "repo project")
                    val path = reader.getAttributeValue(null, "path") ?: name
                    requireSafePath(path)
                    projects +=
                        RepoProject(
                            name,
                            path,
                            reader.getAttributeValue(null, "revision") ?: defaultRevision,
                        )
                }
                "remove-project" ->
                    removedProjects += requiredAttribute(reader, "name", "repo remove-project")
                "extend-project" -> {
                    val name = requiredAttribute(reader, "name", "repo extend-project")
                    val path = reader.getAttributeValue(null, "path")
                    path?.let(::requireSafePath)
                    extensions +=
                        RepoProjectExtension(name, path, reader.getAttributeValue(null, "revision"))
                }
            }
        }
        reader.close()
        return RepoManifestDirectives(projects, includes, removedProjects, extensions)
    }

    private fun requiredAttribute(
        reader: javax.xml.stream.XMLStreamReader,
        name: String,
        element: String,
    ): String = requireNotNull(reader.getAttributeValue(null, name)) { "$element is missing $name" }

    private fun requireSafePath(path: String) {
        require(path.split('/').all { it.isNotBlank() && it != "." && it != ".." }) {
            "repo project path escapes manifest root: $path"
        }
    }
}

private data class RepoManifestDirectives(
    val projects: List<RepoProject>,
    val includes: List<String>,
    val removedProjects: List<String>,
    val extensions: List<RepoProjectExtension>,
)

private data class RepoProjectExtension(val name: String, val path: String?, val revision: String?)
