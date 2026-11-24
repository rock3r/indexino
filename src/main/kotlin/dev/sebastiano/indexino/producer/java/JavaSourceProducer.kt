package dev.sebastiano.indexino.producer.java

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.ResourceDefinitionRecord
import dev.sebastiano.indexino.core.record.ResourceUsageRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.store.hasSymbol
import dev.sebastiano.indexino.producer.FactSink
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.IndexProducer
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.producer.SortedFactBuffer
import dev.sebastiano.indexino.producer.SourceRecordCleanup
import dev.sebastiano.indexino.producer.xml.ResourceMetadata

internal class JavaSourceProducer : IndexProducer {
    override val id: String = "java-source"
    override val namespace: String = "sym"
    override val displayName: String = "JavaSourceProducer"

    override val progressTotal: (IndexBuildContext) -> Int = { context ->
        sourceFilesToProcess(context).size
    }

    override fun produce(context: IndexBuildContext, store: CodeIndexStore) {
        val affectedSources =
            ((context.changedSources + context.deletedSources).filter {
                    it.path.endsWith(".java")
                } + metadataDependentSources(context))
                .distinctBy { it.originId to it.path }
        SourceRecordCleanup.deleteLanguageOriginRecords(
            store,
            LANGUAGE,
            ".java",
            affectedSources.toSet(),
        )
        val javaFiles = sourceFilesToProcess(context)
        if (javaFiles.isEmpty()) return
        // Parse every file once, in parallel bounded batches. The scanner keeps declaration
        // metadata and unresolved candidates; no source text is reparsed for a second pass.
        val parsed =
            JavaSourceParser()
                .parse(
                    javaFiles.map { source ->
                        JavaParseInput(
                            source = source,
                            defaultResourcePackage =
                                ResourceMetadata.resourcePackage(context, source),
                        ) {
                            context.readSource(source)
                        }
                    }
                )
                .flatMap { batch -> batch.files }
        check(parsed.size == javaFiles.size) { "Java parse result count changed" }
        check(parsed.map { file -> file.source } == javaFiles) { "Java parse result order changed" }

        val processedSources =
            javaFiles.mapTo(HashSet()) { source -> source.originId to source.path }
        val mergeStoredSymbols =
            context.sources.any { source ->
                source.isCode && (source.originId to source.path) !in processedSources
            }
        val symbols =
            JavaProjectSymbols(
                parsed.flatMap { file -> file.facts.symbols },
                store,
                mergeStoredSymbols,
            )
        val resourceDefinitions = JavaResourceDefinitionLookup(store)
        val facts = SortedFactBuffer(store, displayName)
        var processed = 0
        parsed.forEach { file ->
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Java indexing interrupted")
            }
            processed += 1
            context.reportFileProgress(processed, parsed.size, file.source)
            emit(file, symbols, resourceDefinitions, facts)
        }
        facts.flush()
    }

    private fun emit(
        file: JavaParsedFile,
        symbols: JavaProjectSymbols,
        resourceDefinitions: JavaResourceDefinitionLookup,
        sink: FactSink,
    ) {
        val originId = file.source.originId
        val relativePath = file.source.path
        val facts = file.facts
        facts.symbols.forEach { symbol ->
            sink.put(
                CodeIndexKey.symbolDefinition(
                    symbol.fqn,
                    originId,
                    relativePath,
                    symbol.line,
                    symbol.column,
                ),
                symbol,
            )
        }
        facts.references.forEach { draft ->
            val target = draft.target.resolve(symbols::hasSymbol)
            sink.put(
                CodeIndexKey.ref(
                    target.symbolFqn,
                    originId,
                    relativePath,
                    draft.line,
                    draft.column,
                ),
                ReferenceRecord(
                    symbolFqn = target.symbolFqn,
                    relativeFile = relativePath,
                    originId = originId,
                    line = draft.line,
                    column = draft.column,
                    context = draft.context,
                    language = LANGUAGE,
                    referencedName = target.name,
                    qualifier = target.qualifier,
                    candidateSymbolFqns = target.candidates,
                    arity = draft.arity,
                ),
            )
        }
        facts.calls.forEach { draft ->
            val target = draft.target?.resolve(symbols::hasSymbol)
            val parameterNames =
                target
                    ?.let { resolved ->
                        symbols.parameterNames(resolved.symbolFqn, draft.arguments.size, originId)
                    }
                    .orEmpty()
            sink.put(
                CodeIndexKey.call(draft.identity),
                CallSiteRecord(
                    identity = draft.identity,
                    calleeName = target?.name ?: draft.fallbackName,
                    candidateSymbolFqns = target?.candidates.orEmpty(),
                    receiver = target?.qualifier,
                    enclosingSymbolFqn = draft.enclosingSymbolFqn,
                    parentCallIdentity = draft.parentCallIdentity,
                    relativeFile = relativePath,
                    originId = originId,
                    startLine = draft.startLine,
                    startColumn = draft.startColumn,
                    startOffset = draft.startOffset,
                    endLine = draft.endLine,
                    endColumn = draft.endColumn,
                    endOffset = draft.endOffset,
                    arguments =
                        draft.arguments.mapIndexed { index, argument ->
                            argument.copy(resolvedName = parameterNames.getOrNull(index))
                        },
                    confidence = if (target == null) "UNRESOLVED" else "HEURISTIC",
                ),
            )
        }
        facts.resourceUsages.forEach { draft ->
            if (!draft.requireDefinition || resourceDefinitions.has(draft.record)) {
                val record = draft.record
                sink.put(
                    CodeIndexKey.resourceUsage(
                        packageName = record.packageName,
                        type = record.type,
                        name = record.name,
                        originId = record.originId,
                        relativeFile = record.relativeFile,
                        line = record.line,
                        column = record.column,
                    ),
                    record,
                )
            }
        }
    }

    private fun sourceFilesToProcess(context: IndexBuildContext): List<IndexedSource> =
        (context.changedSources.filter { it.path.endsWith(".java") } +
                metadataDependentSources(context))
            .filter { it.isCode }
            .distinctBy { it.originId to it.path }

    private fun metadataDependentSources(context: IndexBuildContext): List<IndexedSource> {
        val metadataModules =
            (context.changedSources + context.deletedSources).mapNotNull { source ->
                ResourceMetadata.metadataModule(source.path)?.let { source.originId to it }
            }
        val resourceModules =
            (context.changedSources + context.deletedSources)
                .filter { ResourceMetadata.isResourceXml(it.path) }
                .map { it.originId to ResourceMetadata.moduleDirectory(it.path) }
        val dependencyModules = metadataModules + resourceModules
        if (dependencyModules.isEmpty()) return emptyList()
        return context.sources.filter { source ->
            source.path.endsWith(".java") &&
                (source.originId to ResourceMetadata.moduleDirectory(source.path)) in
                    dependencyModules
        }
    }

    /**
     * Cross-file lookups for the current run stay in memory. Targets absent from the run-local
     * table fall back to stored symbols. When any code source is unprocessed, stored declarations
     * for the same FQN also remain candidates (the symbol namespace is shared across language
     * producers); merge them before applying caller-origin preference so an incremental result
     * matches a full rebuild.
     */
    private class JavaProjectSymbols(
        currentRunSymbols: List<SymbolRecord>,
        private val store: CodeIndexStore,
        private val mergeStoredSymbols: Boolean,
    ) {
        private val byFqn = currentRunSymbols.groupBy { symbol -> symbol.fqn }
        private val storedByFqn = mutableMapOf<String, List<SymbolRecord>>()
        private val presenceByFqn = mutableMapOf<String, Boolean>()

        fun parameterNames(fqn: String, argumentCount: Int, originId: String): List<String> {
            val currentRun = byFqn[fqn].orEmpty()
            val candidates =
                (if (mergeStoredSymbols || currentRun.isEmpty()) {
                        currentRun + storedSymbols(fqn)
                    } else {
                        currentRun
                    })
                    .distinct()
                    .filter { symbol -> symbol.arity == argumentCount }
            return (candidates.singleOrNull { symbol -> symbol.originId == originId }
                    ?: candidates.singleOrNull())
                ?.parameterNames
                .orEmpty()
        }

        fun hasSymbol(fqn: String): Boolean =
            byFqn.containsKey(fqn) || presenceByFqn.getOrPut(fqn) { store.hasSymbol(fqn) }

        private fun storedSymbols(fqn: String): List<SymbolRecord> =
            storedByFqn.getOrPut(fqn) {
                val matches = mutableListOf<SymbolRecord>()
                store.forEachSymbolMatching(fqn, prefix = false) { _, record ->
                    if (record.fqn == fqn) matches += record
                    true
                }
                matches
            }
    }

    private class JavaResourceDefinitionLookup(private val store: CodeIndexStore) {
        private val cache = mutableMapOf<String, Boolean>()

        fun has(usage: ResourceUsageRecord): Boolean {
            val packageName = usage.packageName.orEmpty()
            val key = "$packageName:${usage.type}:${usage.name}"
            val prefix = "resdef:$packageName:${usage.type}:${usage.name}:"
            return cache.getOrPut(key) {
                var found = false
                store.forEachPrefix(prefix) { _, record ->
                    if (
                        record is ResourceDefinitionRecord &&
                            record.packageName == usage.packageName &&
                            record.type == usage.type &&
                            record.name == usage.name
                    ) {
                        found = true
                    }
                    !found
                }
                found
            }
        }
    }

    private companion object {
        const val LANGUAGE = "java"
    }
}
