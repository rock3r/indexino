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
            putResource(store, indexedSource, pathResource.type, pathResource.name, 1, 1)
        }
        val factory = secureFactory()
        try {
            val reader = factory.createXMLStreamReader(StringReader(source))
            var depth = 0
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        depth++
                        val elementStart =
                            elementStartOffset(source, reader.location.characterOffset)
                        val elementEnd = startTagEndOffset(source, elementStart)
                        val declarationPosition = sourcePosition(source, elementStart)
                        if (pathResource?.type == "values" && depth == RESOURCE_CHILD_DEPTH) {
                            valuesResource(
                                    reader.localName,
                                    reader.getAttributeValue(null, "type"),
                                    reader.getAttributeValue(null, "name"),
                                )
                                ?.let {
                                    putResource(
                                        store,
                                        indexedSource,
                                        it.type,
                                        it.name,
                                        declarationPosition.line,
                                        declarationPosition.column,
                                    )
                                }
                        }
                        for (attributeIndex in 0 until reader.attributeCount) {
                            val valuePosition =
                                attributeValuePosition(
                                    source,
                                    elementStart,
                                    elementEnd + 1,
                                    reader.attributeQualifiedName(attributeIndex),
                                )
                            indexAttribute(
                                store,
                                indexedSource,
                                reader.getAttributeValue(attributeIndex),
                                valuePosition.line,
                                valuePosition.column,
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
                putResource(store, indexedSource, type, name, line, column + match.range.first)
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
        column: Int,
    ) {
        val fqn = resourceFqn(type, name)
        store.put(
            CodeIndexKey.resource(type, name, indexedSource.originId, indexedSource.path, line),
            SymbolRecord(
                fqn = fqn,
                relativeFile = indexedSource.path,
                line = line,
                column = column,
                originId = indexedSource.originId,
                kind = "resource",
                name = name,
                language = LANGUAGE,
                ownerFqn = "res:$type",
                aliases = listOf("@$type/$name"),
            ),
        )
    }

    private fun elementStartOffset(source: String, characterOffset: Int): Int {
        val searchFrom = (characterOffset - 1).coerceIn(0, source.lastIndex)
        return source.lastIndexOf('<', searchFrom).coerceAtLeast(0)
    }

    private fun startTagEndOffset(source: String, elementStart: Int): Int {
        var quote: Char? = null
        for (offset in elementStart + 1 until source.length) {
            val character = source[offset]
            if (quote == null && (character == '\'' || character == '\"')) {
                quote = character
            } else if (character == quote) {
                quote = null
            } else if (quote == null && character == '>') {
                return offset
            }
        }
        return source.lastIndex
    }

    private fun attributeValuePosition(
        source: String,
        elementStart: Int,
        characterOffset: Int,
        qualifiedName: String,
    ): SourcePosition {
        val elementEnd = characterOffset.coerceIn(elementStart, source.length)
        val startTag = source.substring(elementStart, elementEnd)
        val attribute =
            Regex("(?<![A-Za-z0-9_.:-])${Regex.escape(qualifiedName)}\\s*=").find(startTag)
        val quoteStart = attribute?.let {
            startTag.indexOfAny(charArrayOf('\'', '\"'), it.range.last + 1)
        }
        val valueStart = quoteStart?.takeIf { it >= 0 }?.plus(1) ?: 0
        return sourcePosition(source, elementStart + valueStart)
    }

    private fun javax.xml.stream.XMLStreamReader.attributeQualifiedName(index: Int): String {
        val prefix = getAttributePrefix(index)
        return if (prefix.isNullOrBlank()) getAttributeLocalName(index)
        else "$prefix:${getAttributeLocalName(index)}"
    }

    private fun sourcePosition(source: String, offset: Int): SourcePosition {
        val lineStart = source.lastIndexOf('\n', offset - 1)
        val line = source.substring(0, offset).count { it == '\n' } + 1
        return SourcePosition(line, offset - lineStart)
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

    private data class SourcePosition(val line: Int, val column: Int)

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
