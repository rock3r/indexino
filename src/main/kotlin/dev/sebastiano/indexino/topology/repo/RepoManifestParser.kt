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
        val projects = linkedMapOf<Pair<String, String>, RepoProject>()
        val visited = mutableSetOf<Path>()

        fun apply(document: Path) {
            val canonicalDocument = document.toRealPath()
            if (!visited.add(canonicalDocument)) return
            parseDirectives(canonicalDocument.readText()).directives.forEach { directive ->
                applyDirective(directive, canonicalDocument, manifestDirectory, projects, ::apply)
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
        RepoManifest(
            parseDirectives(content)
                .directives
                .filterIsInstance<RepoManifestDirective.Project>()
                .map(RepoManifestDirective.Project::project)
                .sortedBy { it.name }
        )

    private fun parseDirectives(content: String): RepoManifestDirectives {
        val reader =
            XMLInputFactory.newFactory()
                .apply {
                    setProperty(XMLInputFactory.SUPPORT_DTD, false)
                    setProperty("javax.xml.stream.isSupportingExternalEntities", false)
                }
                .createXMLStreamReader(StringReader(content))
        var defaultRevision: String? = null
        val directives = mutableListOf<RepoManifestDirective>()
        while (reader.hasNext()) {
            if (reader.next() != XMLStreamConstants.START_ELEMENT) continue
            when (reader.localName) {
                "default" -> defaultRevision = reader.getAttributeValue(null, "revision")
                "include" ->
                    directives +=
                        RepoManifestDirective.Include(
                            requiredAttribute(reader, "name", "repo manifest include")
                        )
                "project" -> {
                    val name = requiredAttribute(reader, "name", "repo project")
                    val path = reader.getAttributeValue(null, "path") ?: name
                    requireSafePath(path)
                    directives +=
                        RepoManifestDirective.Project(
                            RepoProject(
                                name,
                                path,
                                reader.getAttributeValue(null, "revision") ?: defaultRevision,
                            )
                        )
                }
                "remove-project" -> {
                    val name = requiredAttribute(reader, "name", "repo remove-project")
                    val path = reader.getAttributeValue(null, "path")
                    path?.let(::requireSafePath)
                    directives += RepoManifestDirective.Removal(RepoProjectSelector(name, path))
                }
                "extend-project" -> {
                    val name = requiredAttribute(reader, "name", "repo extend-project")
                    val path = reader.getAttributeValue(null, "path")
                    path?.let(::requireSafePath)
                    directives +=
                        RepoManifestDirective.Extension(
                            RepoProjectExtension(
                                name,
                                path,
                                reader.getAttributeValue(null, "revision"),
                            )
                        )
                }
            }
        }
        reader.close()
        return RepoManifestDirectives(directives)
    }

    private fun applyDirective(
        directive: RepoManifestDirective,
        document: Path,
        manifestDirectory: Path,
        projects: MutableMap<Pair<String, String>, RepoProject>,
        apply: (Path) -> Unit,
    ) {
        when (directive) {
            is RepoManifestDirective.Include -> {
                val includedDocument = document.parent.resolve(directive.name).normalize()
                require(includedDocument.startsWith(manifestDirectory)) {
                    "repo manifest include escapes manifest root: ${directive.name}"
                }
                require(includedDocument.exists()) {
                    "repo manifest include is unavailable: ${directive.name}"
                }
                apply(includedDocument)
            }
            is RepoManifestDirective.Project -> {
                val project = directive.project
                projects[project.name to project.path] = project
            }
            is RepoManifestDirective.Removal -> {
                val removal = directive.selector
                projects.keys
                    .filter { (name, path) ->
                        name == removal.name && (removal.path == null || path == removal.path)
                    }
                    .forEach(projects::remove)
            }
            is RepoManifestDirective.Extension -> {
                val extension = directive.extension
                projects.entries
                    .filter { (key, _) ->
                        key.first == extension.name &&
                            (extension.path == null || key.second == extension.path)
                    }
                    .forEach { (key, project) ->
                        projects.remove(key)
                        val updated =
                            project.copy(revision = extension.revision ?: project.revision)
                        projects[updated.name to updated.path] = updated
                    }
            }
        }
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

private data class RepoManifestDirectives(val directives: List<RepoManifestDirective>)

private sealed interface RepoManifestDirective {
    data class Include(val name: String) : RepoManifestDirective

    data class Project(val project: RepoProject) : RepoManifestDirective

    data class Removal(val selector: RepoProjectSelector) : RepoManifestDirective

    data class Extension(val extension: RepoProjectExtension) : RepoManifestDirective
}

private data class RepoProjectSelector(val name: String, val path: String?)

private data class RepoProjectExtension(val name: String, val path: String?, val revision: String?)
