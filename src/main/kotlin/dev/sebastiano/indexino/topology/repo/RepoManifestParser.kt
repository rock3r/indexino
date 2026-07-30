package dev.sebastiano.indexino.topology.repo

import java.io.StringReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

internal data class RepoProject(val name: String, val path: String, val revision: String?)

internal data class RepoManifest(val projects: List<RepoProject>)

/** Parses the resolved Android repo manifest into repository identities and local mounts. */
internal object RepoManifestParser {
    fun parse(content: String): RepoManifest {
        val reader =
            XMLInputFactory.newFactory()
                .apply {
                    setProperty(XMLInputFactory.SUPPORT_DTD, false)
                    setProperty("javax.xml.stream.isSupportingExternalEntities", false)
                }
                .createXMLStreamReader(StringReader(content))
        var defaultRevision: String? = null
        val projects = mutableListOf<RepoProject>()
        while (reader.hasNext()) {
            if (reader.next() != XMLStreamConstants.START_ELEMENT) continue
            when (reader.localName) {
                "default" -> defaultRevision = reader.getAttributeValue(null, "revision")
                "project" -> {
                    val name =
                        requireNotNull(reader.getAttributeValue(null, "name")) {
                            "repo project is missing name"
                        }
                    val path = reader.getAttributeValue(null, "path") ?: name
                    require(path.split('/').all { it.isNotBlank() && it != "." && it != ".." }) {
                        "repo project path escapes manifest root: $path"
                    }
                    projects +=
                        RepoProject(
                            name,
                            path,
                            reader.getAttributeValue(null, "revision") ?: defaultRevision,
                        )
                }
            }
        }
        reader.close()
        return RepoManifest(projects.sortedBy { it.name })
    }
}
