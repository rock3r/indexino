package dev.sebastiano.indexino.producer.xml

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.ResourceDefinitionRecord
import dev.sebastiano.indexino.core.record.ResourceUsageRecord
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
import javax.xml.stream.XMLStreamReader

@Suppress("TooManyFunctions")
internal class XmlResourceProducer : IndexProducer {
    override val id: String = "xml-resources"
    override val namespace: String = "res"
    override val displayName: String = "XmlResourceProducer"

    override val progressTotal: (IndexBuildContext) -> Int = { context ->
        resourceFilesToProcess(context).size
    }

    override fun produce(context: IndexBuildContext, store: CodeIndexStore) {
        SourceRecordCleanup.deleteXmlOriginRecords(store, affectedResourceSources(context).toSet())
        val xmlFiles = resourceFilesToProcess(context)
        xmlFiles.forEachIndexed { index, indexedSource ->
            context.reportFileProgress(index + 1, xmlFiles.size, indexedSource)
            val source =
                if (indexedSource.path.endsWith(".xml")) {
                    context.readSource(indexedSource)
                } else {
                    ""
                }
            parse(indexedSource, source, store, context)
        }
    }

    private fun affectedResourceSources(context: IndexBuildContext): List<IndexedSource> =
        ((context.changedSources + context.deletedSources).filter {
                ResourceMetadata.isResourceXml(it.path)
            } + dependentResources(context))
            .distinctBy { it.originId to it.path }

    private fun resourceFilesToProcess(context: IndexBuildContext): List<IndexedSource> =
        (context.changedSources.filter { ResourceMetadata.isResourceXml(it.path) } +
                dependentResources(context))
            .distinctBy { it.originId to it.path }

    private fun dependentResources(context: IndexBuildContext): List<IndexedSource> {
        val metadataModules =
            (context.changedSources + context.deletedSources).mapNotNull { source ->
                ResourceMetadata.metadataModule(source.path)?.let { source.originId to it }
            }
        if (metadataModules.isEmpty()) return emptyList()
        return context.sources.filter { source ->
            ResourceMetadata.isResourceXml(source.path) &&
                (source.originId to ResourceMetadata.moduleDirectory(source.path)) in
                    metadataModules
        }
    }

    private fun parse(
        indexedSource: IndexedSource,
        source: String,
        store: CodeIndexStore,
        context: IndexBuildContext,
    ) {
        val relativePath = indexedSource.path
        val sourcePositions = XmlSourcePositions(source)
        val packageName = ResourceMetadata.resourcePackage(context, indexedSource)
        val pathResource = ResourceMetadata.resourceFromPath(relativePath)
        if (pathResource != null && pathResource.type != "values") {
            putResource(
                store,
                indexedSource,
                pathResource.type,
                pathResource.name,
                1,
                1,
                0,
                packageName,
            )
        }
        if (!relativePath.endsWith(".xml")) return
        val factory = secureFactory()
        try {
            val reader = factory.createXMLStreamReader(StringReader(source))
            var depth = 0
            var elementSearchOffset = 0
            var declareStyleableName: String? = null
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        depth++
                        val elementStart = nextStartElementOffset(source, elementSearchOffset)
                        val elementEnd = startTagEndOffset(source, elementStart)
                        elementSearchOffset = elementEnd + 1
                        val attributeValueOffsets =
                            attributeValueOffsets(
                                source,
                                elementStart,
                                elementEnd,
                                sourcePositions.isXml11,
                            )
                        declareStyleableName =
                            indexStartElement(
                                store = store,
                                indexedSource = indexedSource,
                                source = source,
                                reader = reader,
                                depth = depth,
                                pathResource = pathResource,
                                declareStyleableName = declareStyleableName,
                                packageName = packageName,
                                sourcePositions = sourcePositions,
                                elementStart = elementStart,
                                attributeValueOffsets = attributeValueOffsets,
                            )
                    }
                    XMLStreamConstants.END_ELEMENT -> {
                        if (
                            depth == RESOURCE_CHILD_DEPTH && reader.localName == "declare-styleable"
                        ) {
                            declareStyleableName = null
                        }
                        depth--
                    }
                }
            }
            reader.close()
            indexTextReferences(store, indexedSource, source, sourcePositions, packageName)
        } catch (exception: XMLStreamException) {
            throw IllegalArgumentException("$relativePath: ${exception.message}", exception)
        }
    }

    private fun indexStartElement(
        store: CodeIndexStore,
        indexedSource: IndexedSource,
        source: String,
        reader: XMLStreamReader,
        depth: Int,
        pathResource: ResourcePath?,
        declareStyleableName: String?,
        packageName: String?,
        sourcePositions: XmlSourcePositions,
        elementStart: Int,
        attributeValueOffsets: Map<String, RawAttribute>,
    ): String? {
        var activeDeclareStyleable = declareStyleableName
        val declarationPosition = sourcePositions.at(elementStart)
        if (pathResource?.type == "values" && depth == RESOURCE_CHILD_DEPTH) {
            valuesResource(
                    reader.localName,
                    reader.getAttributeValue(null, "type"),
                    reader.getAttributeValue(null, "name"),
                )
                ?.let {
                    if (reader.localName == "declare-styleable") {
                        activeDeclareStyleable = it.name
                    }
                    putResource(
                        store,
                        indexedSource,
                        it.type,
                        it.name,
                        declarationPosition.line,
                        declarationPosition.column,
                        elementStart,
                        packageName,
                    )
                }
        } else if (
            pathResource?.type == "values" &&
                depth == DECLARE_STYLEABLE_CHILD_DEPTH &&
                reader.localName == "attr" &&
                activeDeclareStyleable != null
        ) {
            putNestedStyleableAttribute(
                store,
                indexedSource,
                activeDeclareStyleable,
                reader,
                declarationPosition.line,
                declarationPosition.column,
                elementStart,
                packageName,
            )
        }
        for (attributeIndex in 0 until reader.attributeCount) {
            val qualifiedName = reader.attributeQualifiedName(attributeIndex)
            val rawAttribute =
                checkNotNull(attributeValueOffsets[qualifiedName]) {
                    "Missing raw XML attribute $qualifiedName in ${attributeValueOffsets.keys}"
                }
            val rawValue = source.substring(rawAttribute.valueStart, rawAttribute.valueEnd)
            val decodedValue = decodeXml(rawValue, sourcePositions.isXml11)
            val valuePosition = sourcePositions.at(rawAttribute.valueStart)
            indexStyleParent(
                store,
                indexedSource,
                reader.getAttributeLocalName(attributeIndex),
                decodedValue.value,
                valuePosition.line,
                valuePosition.column,
                rawAttribute.valueStart,
                packageName,
            )
            indexRawValue(
                store,
                indexedSource,
                rawValue,
                sourcePositions,
                rawAttribute.valueStart,
                packageName,
            )
        }
        return activeDeclareStyleable
    }

    private fun putNestedStyleableAttribute(
        store: CodeIndexStore,
        indexedSource: IndexedSource,
        declareStyleableName: String,
        reader: XMLStreamReader,
        line: Int,
        column: Int,
        offset: Int,
        packageName: String?,
    ) {
        val attributeName =
            reader.getAttributeValue(null, "name")?.takeIf(String::isNotBlank) ?: return
        if (':' !in attributeName) {
            putResource(
                store,
                indexedSource,
                "attr",
                attributeName,
                line,
                column,
                offset,
                packageName,
            )
        }
        putResource(
            store,
            indexedSource,
            "styleable",
            "${declareStyleableName}_${attributeName.replace(':', '_')}",
            line,
            column,
            offset,
            packageName,
        )
    }

    private fun indexRawValue(
        store: CodeIndexStore,
        indexedSource: IndexedSource,
        rawValue: String,
        sourcePositions: XmlSourcePositions,
        valueOffset: Int,
        resolvedPackageName: String?,
    ) {
        val decodedValue = decodeXml(rawValue, sourcePositions.isXml11)
        RESOURCE_REFERENCE.findAll(decodedValue.value).forEach { match ->
            val rawMatchOffset = decodedValue.rawOffsets[match.range.first]
            val absoluteOffset = valueOffset + rawMatchOffset
            val position = sourcePositions.at(absoluteOffset)
            indexMatch(store, indexedSource, match, position, absoluteOffset, resolvedPackageName)
        }
    }

    private fun indexLiteralValue(
        store: CodeIndexStore,
        indexedSource: IndexedSource,
        value: String,
        sourcePositions: XmlSourcePositions,
        valueOffset: Int,
        resolvedPackageName: String?,
    ) {
        RESOURCE_REFERENCE.findAll(value).forEach { match ->
            val absoluteOffset = valueOffset + match.range.first
            indexMatch(
                store,
                indexedSource,
                match,
                sourcePositions.at(absoluteOffset),
                absoluteOffset,
                resolvedPackageName,
            )
        }
    }

    private fun indexMatch(
        store: CodeIndexStore,
        indexedSource: IndexedSource,
        match: MatchResult,
        position: SourcePosition,
        offset: Int,
        resolvedPackageName: String?,
    ) {
        val createsId = match.groupValues[CREATE_MARKER_GROUP] == "+"
        val packageName = match.groupValues[RESOURCE_PACKAGE_GROUP].ifBlank { null }
        val type = match.groupValues[RESOURCE_TYPE_GROUP]
        val name = match.groupValues[RESOURCE_NAME_GROUP]
        if (createsId && packageName == null && type == "id") {
            putResource(
                store,
                indexedSource,
                type,
                name,
                position.line,
                position.column,
                offset,
                resolvedPackageName,
            )
        } else {
            val target = resourceFqn(packageName, type, name)
            store.put(
                CodeIndexKey.ref(
                    target,
                    indexedSource.originId,
                    indexedSource.path,
                    position.line,
                    position.column,
                ),
                ReferenceRecord(
                    symbolFqn = target,
                    relativeFile = indexedSource.path,
                    originId = indexedSource.originId,
                    line = position.line,
                    column = position.column,
                    context = "resource",
                    language = LANGUAGE,
                    referencedName = name,
                    qualifier = listOfNotNull(packageName, type).joinToString(":"),
                    candidateSymbolFqns = listOf(target),
                ),
            )
            store.put(
                CodeIndexKey.resourceUsage(
                    packageName = packageName ?: resolvedPackageName,
                    type = type,
                    name = name,
                    originId = indexedSource.originId,
                    relativeFile = indexedSource.path,
                    line = position.line,
                    column = position.column,
                ),
                ResourceUsageRecord(
                    packageName = packageName ?: resolvedPackageName,
                    type = type,
                    name = name,
                    relativeFile = indexedSource.path,
                    line = position.line,
                    column = position.column,
                    offset = offset,
                    language = LANGUAGE,
                    originId = indexedSource.originId,
                ),
            )
        }
    }

    private fun indexTextReferences(
        store: CodeIndexStore,
        indexedSource: IndexedSource,
        source: String,
        sourcePositions: XmlSourcePositions,
        resolvedPackageName: String?,
    ) {
        var textStart = 0
        while (textStart < source.length) {
            val markupStart =
                source.indexOf('<', textStart).let { if (it >= 0) it else source.length }
            if (markupStart > textStart) {
                indexRawValue(
                    store,
                    indexedSource,
                    source.substring(textStart, markupStart),
                    sourcePositions,
                    textStart,
                    resolvedPackageName,
                )
            }
            if (markupStart == source.length) return
            if (source.startsWith(CDATA_START, markupStart)) {
                val contentStart = markupStart + CDATA_START.length
                val contentEnd =
                    source.indexOf(CDATA_END, contentStart).let {
                        if (it >= 0) it else source.length
                    }
                indexLiteralValue(
                    store,
                    indexedSource,
                    source.substring(contentStart, contentEnd),
                    sourcePositions,
                    contentStart,
                    resolvedPackageName,
                )
                textStart = (contentEnd + CDATA_END.length).coerceAtMost(source.length)
            } else {
                textStart =
                    skippedMarkupEnd(source, markupStart)
                        ?: (startTagEndOffset(source, markupStart) + 1).coerceAtMost(source.length)
            }
        }
    }

    private fun indexStyleParent(
        store: CodeIndexStore,
        indexedSource: IndexedSource,
        attributeName: String?,
        value: String,
        line: Int,
        column: Int,
        offset: Int,
        resolvedPackageName: String?,
    ) {
        if (
            attributeName != "parent" ||
                value.isBlank() ||
                value.startsWith("@") ||
                value.startsWith("?")
        ) {
            return
        }
        val stylePackage =
            value.substringBefore(':', missingDelimiterValue = "").takeIf {
                ':' in value && it.isNotBlank()
            }
        val styleName = value.substringAfter(':').substringAfter('/')
        val target = resourceFqn(stylePackage, "style", styleName)
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
                referencedName = styleName,
                qualifier = listOfNotNull(stylePackage, "style").joinToString(":"),
                candidateSymbolFqns = listOf(target),
            ),
        )
        store.put(
            CodeIndexKey.resourceUsage(
                packageName = stylePackage ?: resolvedPackageName,
                type = "style",
                name = styleName,
                originId = indexedSource.originId,
                relativeFile = indexedSource.path,
                line = line,
                column = column,
            ),
            ResourceUsageRecord(
                packageName = stylePackage ?: resolvedPackageName,
                type = "style",
                name = styleName,
                relativeFile = indexedSource.path,
                line = line,
                column = column,
                offset = offset,
                language = LANGUAGE,
                originId = indexedSource.originId,
            ),
        )
    }

    private fun putResource(
        store: CodeIndexStore,
        indexedSource: IndexedSource,
        type: String,
        name: String,
        line: Int,
        column: Int,
        offset: Int,
        packageName: String?,
    ) {
        val fqn = resourceFqn(type, name)
        val qualifiers = ResourceMetadata.resourceFromPath(indexedSource.path)?.qualifiers.orEmpty()
        store.put(
            CodeIndexKey.resourceDefinition(
                packageName = packageName,
                type = type,
                name = name,
                qualifiers = qualifiers,
                originId = indexedSource.originId,
                relativeFile = indexedSource.path,
                line = line,
            ),
            ResourceDefinitionRecord(
                packageName = packageName,
                type = type,
                name = name,
                qualifiers = qualifiers,
                relativeFile = indexedSource.path,
                line = line,
                column = column,
                offset = offset,
                originId = indexedSource.originId,
            ),
        )
        store.put(
            CodeIndexKey.resource(
                type,
                name,
                indexedSource.originId,
                indexedSource.path,
                line,
                column,
            ),
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

    private fun XMLStreamReader.attributeQualifiedName(index: Int): String {
        val prefix = getAttributePrefix(index)
        return if (prefix.isNullOrBlank()) getAttributeLocalName(index)
        else "$prefix:${getAttributeLocalName(index)}"
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
                "declare-styleable" -> "styleable"
                else -> rawType
            }
        return type?.takeIf { it.isNotBlank() }?.let { ResourceName(it, name) }
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

    private class XmlSourcePositions(source: String) {
        val isXml11: Boolean = XML_11_DECLARATION.containsMatchIn(source)
        private val lineStarts = buildList {
            add(0)
            var offset = 0
            while (offset < source.length) {
                when {
                    source[offset] == '\r' -> {
                        offset += xmlCarriageReturnLength(source, offset, isXml11)
                        add(offset)
                    }
                    source[offset] == '\n' ||
                        (isXml11 && (source[offset] == '\u0085' || source[offset] == '\u2028')) -> {
                        offset++
                        add(offset)
                    }
                    else -> offset++
                }
            }
        }

        fun at(offset: Int): SourcePosition {
            val searchResult = lineStarts.binarySearch(offset)
            val lineIndex = if (searchResult >= 0) searchResult else -searchResult - 2
            return SourcePosition(lineIndex + 1, offset - lineStarts[lineIndex] + 1)
        }
    }

    private data class SourcePosition(val line: Int, val column: Int)

    private companion object {
        const val LANGUAGE = "xml"
        const val RESOURCE_CHILD_DEPTH = 2
        const val DECLARE_STYLEABLE_CHILD_DEPTH = 3
        const val CREATE_MARKER_GROUP = 1
        const val RESOURCE_PACKAGE_GROUP = 2
        const val RESOURCE_TYPE_GROUP = 3
        const val RESOURCE_NAME_GROUP = 4
        val RESOURCE_REFERENCE =
            Regex("[@?](\\+)?(?:([A-Za-z0-9_.]+):)?([A-Za-z0-9_]+)/([A-Za-z0-9_.]+)")
    }
}

private fun nextStartElementOffset(source: String, fromOffset: Int): Int {
    var candidate = source.indexOf('<', fromOffset)
    var skippedEnd = candidate.takeIf { it >= 0 }?.let { skippedMarkupEnd(source, it) }
    while (candidate >= 0 && skippedEnd != null) {
        candidate = source.indexOf('<', skippedEnd)
        skippedEnd = candidate.takeIf { it >= 0 }?.let { skippedMarkupEnd(source, it) }
    }
    return candidate.coerceAtLeast(0)
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

private fun decodeXml(rawValue: String, isXml11: Boolean): DecodedXml {
    val decoded = StringBuilder(rawValue.length)
    val rawOffsets = mutableListOf<Int>()
    var rawOffset = 0
    while (rawOffset < rawValue.length) {
        val entityEnd =
            rawValue
                .takeIf { it[rawOffset] == '&' }
                ?.indexOf(';', rawOffset + 1)
                ?.takeIf { it >= 0 }
        val entity = entityEnd?.let { decodeXmlEntity(rawValue, rawOffset, it) }
        when {
            entityEnd != null && entity != null -> {
                decoded.append(entity)
                repeat(entity.length) { rawOffsets += rawOffset }
                rawOffset = entityEnd + 1
            }
            rawValue[rawOffset] == '\r' -> {
                decoded.append('\n')
                rawOffsets += rawOffset
                rawOffset += xmlCarriageReturnLength(rawValue, rawOffset, isXml11)
            }
            isXml11 && (rawValue[rawOffset] == '\u0085' || rawValue[rawOffset] == '\u2028') -> {
                decoded.append('\n')
                rawOffsets += rawOffset
                rawOffset++
            }
            else -> {
                decoded.append(rawValue[rawOffset])
                rawOffsets += rawOffset
                rawOffset++
            }
        }
    }
    return DecodedXml(decoded.toString(), rawOffsets.toIntArray())
}

private fun decodeXmlEntity(rawValue: String, start: Int, end: Int): String? {
    val body = rawValue.substring(start + 1, end)
    XML_PREDEFINED_ENTITIES[body]?.let {
        return it
    }
    val codePoint =
        when {
            body.startsWith("#x", ignoreCase = true) -> body.drop(2).toIntOrNull(16)
            body.startsWith('#') -> body.drop(1).toIntOrNull()
            else -> null
        }
    return codePoint?.takeIf(Character::isValidCodePoint)?.let(Character::toChars)?.concatToString()
}

private data class DecodedXml(val value: String, val rawOffsets: IntArray)

private fun xmlCarriageReturnLength(value: String, offset: Int, isXml11: Boolean): Int =
    if (
        value.getOrNull(offset + 1) == '\n' || (isXml11 && value.getOrNull(offset + 1) == '\u0085')
    ) {
        2
    } else {
        1
    }

private fun skippedMarkupEnd(source: String, offset: Int): Int? =
    when {
        source.startsWith(COMMENT_START, offset) ->
            source.endAfter(COMMENT_END, offset + COMMENT_START.length)
        source.startsWith(CDATA_START, offset) ->
            source.endAfter(CDATA_END, offset + CDATA_START.length)
        source.startsWith("<?", offset) -> source.endAfter("?>", offset + 2)
        source.startsWith("<!", offset) -> declarationEndOffset(source, offset)
        source.startsWith("</", offset) -> source.endAfter(">", offset + 2)
        else -> null
    }

private fun String.endAfter(marker: String, fromOffset: Int): Int =
    indexOf(marker, fromOffset).takeIf { it >= 0 }?.plus(marker.length) ?: length

@Suppress("CyclomaticComplexMethod")
private fun declarationEndOffset(source: String, declarationStart: Int): Int {
    var quote: Char? = null
    var subsetDepth = 0
    var offset = declarationStart + 2
    while (offset < source.length) {
        val opaqueRegionEnd =
            if (quote == null) declarationOpaqueRegionEnd(source, offset) else null
        if (opaqueRegionEnd != null) {
            offset = opaqueRegionEnd
            continue
        }
        val character = source[offset]
        when {
            quote == null && (character == '\'' || character == '\"') -> quote = character
            character == quote -> quote = null
            quote == null && character == '[' -> subsetDepth++
            quote == null && character == ']' && subsetDepth > 0 -> subsetDepth--
            quote == null && character == '>' && subsetDepth == 0 -> return offset + 1
        }
        offset++
    }
    return source.length
}

private fun declarationOpaqueRegionEnd(source: String, offset: Int): Int? =
    when {
        source.startsWith(COMMENT_START, offset) ->
            source.endAfter(COMMENT_END, offset + COMMENT_START.length)
        source.startsWith("<?", offset) -> source.endAfter("?>", offset + 2)
        else -> null
    }

private fun attributeValueOffsets(
    source: String,
    elementStart: Int,
    elementEnd: Int,
    isXml11: Boolean,
): Map<String, RawAttribute> = buildMap {
    var offset = elementStart + 1
    while (offset < elementEnd && !source[offset].isXmlWhitespace(isXml11)) offset++
    var attribute = parseAttribute(source, offset, elementEnd, isXml11)
    while (attribute != null) {
        put(attribute.qualifiedName, attribute)
        attribute = parseAttribute(source, attribute.nextOffset, elementEnd, isXml11)
    }
}

private fun parseAttribute(
    source: String,
    fromOffset: Int,
    elementEnd: Int,
    isXml11: Boolean,
): RawAttribute? {
    var offset = skipWhitespace(source, fromOffset, elementEnd, isXml11)
    if (offset >= elementEnd || source[offset] == '/') return null
    val nameStart = offset
    while (offset < elementEnd && source[offset].isAttributeNameCharacter(isXml11)) offset++
    val qualifiedName = source.substring(nameStart, offset)
    offset = skipWhitespace(source, offset, elementEnd, isXml11)
    if (source.getOrNull(offset) != '=') return null
    offset = skipWhitespace(source, offset + 1, elementEnd, isXml11)
    val quote = source.getOrNull(offset)?.takeIf { it == '\'' || it == '\"' } ?: return null
    val valueStart = offset + 1
    val valueEnd = source.indexOf(quote, valueStart).takeIf { it in valueStart..elementEnd }
    return RawAttribute(
        qualifiedName,
        valueStart,
        valueEnd ?: elementEnd,
        valueEnd?.plus(1) ?: elementEnd,
    )
}

private fun skipWhitespace(
    source: String,
    fromOffset: Int,
    elementEnd: Int,
    isXml11: Boolean,
): Int {
    var offset = fromOffset
    while (offset < elementEnd && source[offset].isXmlWhitespace(isXml11)) offset++
    return offset
}

private fun Char.isAttributeNameCharacter(isXml11: Boolean): Boolean =
    !isXmlWhitespace(isXml11) && this != '=' && this != '/'

private fun Char.isXmlWhitespace(isXml11: Boolean): Boolean =
    isWhitespace() || (isXml11 && (this == '\u0085' || this == '\u2028'))

private data class RawAttribute(
    val qualifiedName: String,
    val valueStart: Int,
    val valueEnd: Int,
    val nextOffset: Int,
)

private const val COMMENT_START = "<!--"
private const val COMMENT_END = "-->"
private const val CDATA_START = "<![CDATA["
private const val CDATA_END = "]]>"
private val XML_PREDEFINED_ENTITIES =
    mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")
private val XML_11_DECLARATION = Regex("^<\\?xml\\s+version\\s*=\\s*(['\"])1\\.1\\1")
