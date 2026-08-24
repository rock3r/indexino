package dev.sebastiano.indexino.core.sourcelink

import dev.sebastiano.indexino.model.ArtifactDigest
import dev.sebastiano.indexino.model.ResolvedComponentCoordinate
import dev.sebastiano.indexino.model.SourceLinkCheckout
import java.nio.file.Path

internal data class SourceLinkConfigEntry(
    val component: ResolvedComponentCoordinate,
    val binarySha256: ArtifactDigest,
    val checkout: String,
    val linkedWorkspace: String,
    val ref: String?,
    val sourceRoots: List<String>,
    val declaredOnly: Boolean = false,
    val variant: String? = null,
    val substitution: String? = null,
    val submoduleRevisions: Map<String, String> = emptyMap(),
    val publishedSourceCompanionDigest: ArtifactDigest? = null,
)

/** Parses illustrative [[sourceLink]] blocks from workspace configuration. */
internal object SourceLinkConfigParser {
    fun parse(text: String): List<SourceLinkConfigEntry> {
        val entries = mutableListOf<SourceLinkConfigEntry>()
        var builder: BlockBuilder? = null
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            if (line == "[[sourceLink]]") {
                builder?.let { entries += it.toEntry() }
                builder = BlockBuilder()
                return@forEach
            }
            builder?.accept(line)
        }
        builder?.let { entries += it.toEntry() }
        return entries
    }

    fun parseFile(path: Path): List<SourceLinkConfigEntry> =
        if (java.nio.file.Files.isRegularFile(path)) {
            parse(java.nio.file.Files.readString(path))
        } else {
            emptyList()
        }

    private class BlockBuilder {
        private val values = linkedMapOf<String, String>()
        private var listKey: String? = null
        private val listValues = ArrayList<String>()

        fun accept(line: String) {
            if (line.startsWith("[")) return
            if (line.startsWith("-")) {
                listValues.add(line.removePrefix("-").trim().trim('"'))
                return
            }
            val separator = line.indexOf('=')
            if (separator <= 0) return
            flushList()
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim().trim('"')
            values[key] = value
            if (key == "sourceRoots") {
                listKey = key
                listValues.clear()
                listValues.add(value)
            }
        }

        fun toEntry(): SourceLinkConfigEntry = entryFrom(values)

        private fun flushList() {
            val key = listKey ?: return
            values[key] = listValues.joinToString("\u0001")
            listKey = null
            listValues.clear()
        }
    }

    private fun entryFrom(map: Map<String, String>): SourceLinkConfigEntry {
        val component =
            requireNotNull(map["component"]) { "sourceLink component is required" }
                .let(ResolvedComponentCoordinate::of)
        val digest =
            requireNotNull(map["binarySha256"]) { "sourceLink binarySha256 is required" }
                .let(ArtifactDigest::of)
        val checkout = requireNotNull(map["checkout"]) { "sourceLink checkout is required" }
        val linkedWorkspace = map["linkedWorkspace"] ?: checkout
        val sourceRoots =
            map["sourceRoots"]?.split('\u0001')?.filter(String::isNotBlank)?.ifEmpty {
                listOfNotNull(map["sourceRoots"])
            } ?: error("sourceLink sourceRoots is required")
        return SourceLinkConfigEntry(
            component = component,
            binarySha256 = digest,
            checkout = checkout,
            linkedWorkspace = linkedWorkspace,
            ref = map["ref"],
            sourceRoots = sourceRoots,
            declaredOnly = map["declaredOnly"]?.toBooleanStrictOrNull() ?: false,
            variant = map["variant"],
            substitution = map["substitution"],
            submoduleRevisions =
                map["submodules"]
                    ?.split(',')
                    ?.mapNotNull { token ->
                        val parts = token.split('=', limit = 2)
                        if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
                    }
                    ?.toMap()
                    .orEmpty(),
            publishedSourceCompanionDigest =
                map["publishedSourceCompanion"]?.let(ArtifactDigest::of),
        )
    }
}

internal fun SourceLinkConfigEntry.toCheckout(
    checkoutRoot: Path,
    dirty: Boolean,
    revision: String?,
): SourceLinkCheckout =
    SourceLinkCheckout.of(
        repositoryIdentity = checkout,
        checkoutPath = checkoutRoot.toString(),
        revision = revision,
        tag = ref?.takeIf { !it.startsWith("commit:") },
        dirty = dirty,
        submoduleRevisions = submoduleRevisions,
        sourceRoots = sourceRoots,
    )
