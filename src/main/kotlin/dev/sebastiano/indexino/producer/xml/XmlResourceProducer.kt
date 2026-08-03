package dev.sebastiano.indexino.producer.xml

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.IndexProducer
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.producer.SourceRecordCleanup
import java.io.StringReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

internal class XmlResourceProducer : IndexProducer {
    override val id: String = "xml-resources"
    override val namespace: String = "res"
    override val displayName: String = "XmlResourceProducer"

    override val progressTotal: (IndexBuildContext) -> Int = { context ->
        context.changedSources.count { it.path.endsWith(".xml") }
    }

    override fun produce(context: IndexBuildContext, store: CodeIndexStore) {
        val affectedSources =
            (context.changedSources + context.deletedSources).filterTo(linkedSetOf()) {
                it.path.endsWith(".xml")
            }
        SourceRecordCleanup.deleteXmlOriginRecords(store, affectedSources)
        val xmlFiles = context.changedSources.filter { it.path.endsWith(".xml") }
        xmlFiles.forEachIndexed { index, indexedSource ->
            context.reportFileProgress(index + 1, xmlFiles.size, indexedSource)
            parse(indexedSource, context.readSource(indexedSource), store)
        }
    }

    private fun parse(indexedSource: IndexedSource, source: String, store: CodeIndexStore) {
        val relativePath = indexedSource.path
        val pathResource = resourceFromPath(relativePath)
        if (pathResource != null && pathResource.type != "values") {
            putResource(store, indexedSource, pathResource.type, pathResource.name, 1)
        }
        val factory = secureFactory()
        try {
            val reader = factory.createXMLStreamReader(StringReader(source))
            var depth = 0
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        depth++
                        val line = reader.location.lineNumber.coerceAtLeast(1)
                        if (pathResource?.type == "values" && depth == RESOURCE_CHILD_DEPTH) {
                            valuesResource(
                                    reader.localName,
                                    reader.getAttributeValue(null, "type"),
                                    reader.getAttributeValue(null, "name"),
                                )
                                ?.let { putResource(store, indexedSource, it.type, it.name, line) }
                        }
                        for (attributeIndex in 0 until reader.attributeCount) {
                            indexAttribute(
                                store,
                                indexedSource,
                                reader.getAttributeValue(attributeIndex),
                                line,
                                reader.location.columnNumber.coerceAtLeast(1) + attributeIndex,
                            )
                        }
                    }
                    XMLStreamConstants.END_ELEMENT -> depth--
                    XMLStreamConstants.CHARACTERS,
                    XMLStreamConstants.CDATA ->
                        indexAttribute(
                            store,
                            indexedSource,
                            reader.text,
                            reader.location.lineNumber.coerceAtLeast(1),
                            reader.location.columnNumber.coerceAtLeast(1),
                        )
                }
            }
            reader.close()
        } catch (exception: XMLStreamException) {
            throw IllegalArgumentException("$relativePath: ${exception.message}", exception)
        }
    }

    private fun indexAttribute(
        store: CodeIndexStore,
        indexedSource: IndexedSource,
        value: String,
        line: Int,
        column: Int,
    ) {
        RESOURCE_REFERENCE.findAll(value).forEach { match ->
            val createsId = match.groupValues[CREATE_MARKER_GROUP] == "+"
            val packageName = match.groupValues[RESOURCE_PACKAGE_GROUP].ifBlank { null }
            val type = match.groupValues[RESOURCE_TYPE_GROUP]
            val name = match.groupValues[RESOURCE_NAME_GROUP]
            if (createsId && packageName == null && type == "id") {
                putResource(store, indexedSource, type, name, line)
            } else {
                val target = resourceFqn(packageName, type, name)
                store.put(
                    CodeIndexKey.ref(
                        target,
                        indexedSource.originId,
                        indexedSource.path,
                        line,
                        column,
                    ),
                    ReferenceRecord(
                        symbolFqn = target,
                        relativeFile = indexedSource.path,
                        originId = indexedSource.originId,
                        line = line,
                        column = column,
                        context = "resource",
                        language = LANGUAGE,
                        referencedName = name,
                        qualifier = listOfNotNull(packageName, type).joinToString(":"),
                        candidateSymbolFqns = listOf(target),
                    ),
                )
            }
        }
    }

    private fun putResource(
        store: CodeIndexStore,
        indexedSource: IndexedSource,
        type: String,
        name: String,
        line: Int,
    ) {
        val fqn = resourceFqn(type, name)
        store.put(
            CodeIndexKey.resource(type, name, indexedSource.originId, indexedSource.path, line),
            SymbolRecord(
                fqn = fqn,
                relativeFile = indexedSource.path,
                line = line,
                originId = indexedSource.originId,
                kind = "resource",
                name = name,
                language = LANGUAGE,
                ownerFqn = "res:$type",
                aliases = listOf("@$type/$name"),
            ),
        )
    }

    private fun valuesResource(element: String, itemType: String?, name: String?): ResourceName? {
        if (name.isNullOrBlank()) {
            return null
        }
        val rawType = if (element == "item") itemType else element
        val type =
            when (rawType) {
                "string-array",
                "integer-array" -> "array"
                else -> rawType
            }
        return type?.takeIf { it.isNotBlank() }?.let { ResourceName(it, name) }
    }

    private fun resourceFromPath(relativePath: String): ResourceName? {
        val match = RESOURCE_PATH.find(relativePath) ?: return null
        return ResourceName(match.groupValues[1].substringBefore('-'), match.groupValues[2])
    }

    private fun secureFactory(): XMLInputFactory =
        XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty("javax.xml.stream.isSupportingExternalEntities", false)
        }

    private fun resourceFqn(type: String, name: String): String = "res:$type:$name"

    private fun resourceFqn(packageName: String?, type: String, name: String): String =
        listOfNotNull("res", packageName, type, name).joinToString(":")

    private data class ResourceName(val type: String, val name: String)

    private companion object {
        const val LANGUAGE = "xml"
        const val RESOURCE_CHILD_DEPTH = 2
        const val CREATE_MARKER_GROUP = 1
        const val RESOURCE_PACKAGE_GROUP = 2
        const val RESOURCE_TYPE_GROUP = 3
        const val RESOURCE_NAME_GROUP = 4
        val RESOURCE_PATH =
            Regex("(?:^|/)(?:src/[^/]+/)?(?:res|[^/]+[_-]res)/([^/]+)/([^/]+)\\.xml$")
        val RESOURCE_REFERENCE =
            Regex("[@?](\\+)?(?:([A-Za-z0-9_.]+):)?([A-Za-z0-9_]+)/([A-Za-z0-9_.]+)")
    }
}
