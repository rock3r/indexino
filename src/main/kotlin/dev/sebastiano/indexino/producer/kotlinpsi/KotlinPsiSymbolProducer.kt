package dev.sebastiano.indexino.producer.kotlinpsi

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.store.hasSymbol
import dev.sebastiano.indexino.parse.KotlinPsiParserPool
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.IndexProducer
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.producer.SourceRecordCleanup
import dev.sebastiano.indexino.producer.xml.ResourceMetadata
import java.util.concurrent.TimeUnit

@Suppress("TooManyFunctions")
internal class KotlinPsiSymbolProducer : IndexProducer {
    override val id: String = "kotlin-psi-symbols"
    override val namespace: String = "sym"
    override val displayName: String = "KotlinPsiSymbolProducer"

    override val progressTotal: (IndexBuildContext) -> Int = { context ->
        sourceFilesToProcess(context, ".kt").size
    }

    override fun produce(context: IndexBuildContext, store: CodeIndexStore) {
        val affectedSources =
            ((context.changedSources + context.deletedSources).filter { it.path.endsWith(".kt") } +
                    metadataDependentSources(context, ".kt"))
                .distinctBy { it.originId to it.path }
        if (affectedSources.isNotEmpty()) {
            timedPhase(context, "kotlin-cleanup") {
                SourceRecordCleanup.deleteLanguageOriginRecords(
                    store,
                    LANGUAGE,
                    ".kt",
                    affectedSources.toSet(),
                )
            }
        }
        val ktFiles = sourceFilesToProcess(context, ".kt")
        if (ktFiles.isEmpty()) return
        timedPhase(context, "kotlin-parser") {
            val workerCount = KotlinSourceParser.workerCount(ktFiles.size)
            val parsers =
                timedPhase(context, "kotlin-parser-setup") { KotlinPsiParserPool(workerCount) }
            parsers.use { pool ->
                // Parse and walk every file once, concurrently. Only detached records survive a
                // file pass, so no project-wide syntax trees stay in heap.
                val inputs = ktFiles.map { source ->
                    KotlinParseInput(
                        source = source,
                        defaultResourcePackage = ResourceMetadata.resourcePackage(context, source),
                        readSource = { context.readSource(source) },
                    )
                }
                val parsed =
                    timedPhase(context, "kotlin-declarations") {
                        KotlinSourceParser().parse(inputs, pool)
                    }
                check(parsed.size == inputs.size) { "Kotlin parse result count changed" }
                check(parsed.map { file -> file.source } == ktFiles) {
                    "Kotlin parse result order changed"
                }
                writeFacts(context, parsed, store)
            }
        }
    }

    private fun writeFacts(
        context: IndexBuildContext,
        parsed: List<KotlinParsedFile>,
        store: CodeIndexStore,
    ) {
        val processedSources =
            parsed.mapTo(HashSet()) { file -> file.source.originId to file.source.path }
        val mergeStoredSymbols =
            context.sources.any { source ->
                source.isCode && (source.originId to source.path) !in processedSources
            }
        val symbols =
            KotlinProjectSymbols(
                parsed.flatMap { file -> file.facts.symbols },
                store,
                mergeStoredSymbols,
            )
        timedPhase(context, "kotlin-fact-write") {
            var processed = 0
            parsed.chunked(EMIT_BATCH_SIZE).forEach { batch ->
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Kotlin indexing interrupted")
                }
                store.transaction {
                    batch.forEach { file ->
                        processed += 1
                        context.reportFileProgress(processed, parsed.size, file.source)
                        emit(file, symbols, store)
                    }
                }
            }
        }
    }

    private fun emit(file: KotlinParsedFile, symbols: KotlinProjectSymbols, store: CodeIndexStore) {
        val originId = file.source.originId
        val relativePath = file.source.path
        val facts = file.facts
        facts.symbols.forEach { symbol ->
            store.put(
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
        facts.calls.forEach { draft ->
            val target = draft.target?.let { resolution -> resolveCall(resolution, symbols) }
            if (target != null) {
                store.put(
                    CodeIndexKey.ref(
                        target.symbolFqn,
                        originId,
                        relativePath,
                        draft.startLine,
                        draft.startColumn,
                    ),
                    ReferenceRecord(
                        symbolFqn = target.symbolFqn,
                        relativeFile = relativePath,
                        originId = originId,
                        line = draft.startLine,
                        column = draft.startColumn,
                        context = "call",
                        language = LANGUAGE,
                        referencedName = target.name,
                        qualifier = target.qualifier,
                        candidateSymbolFqns = target.candidates,
                        arity = draft.valueArgumentCount,
                    ),
                )
            }
            val parameterNames =
                target
                    ?.let { resolved ->
                        symbols.parameterNames(
                            resolved.symbolFqn,
                            draft.parameterLookupCount,
                            originId,
                        )
                    }
                    .orEmpty()
            val enclosingSymbolFqn =
                draft.enclosingFunctionLine?.let { line ->
                    draft.enclosingFunctionName?.let { name ->
                        facts.symbols
                            .firstOrNull { symbol -> symbol.line == line && symbol.name == name }
                            ?.fqn
                    }
                }
            store.put(
                CodeIndexKey.call(draft.identity),
                CallSiteRecord(
                    identity = draft.identity,
                    calleeName = draft.calleeName,
                    candidateSymbolFqns = target?.candidates.orEmpty(),
                    receiver = target?.qualifier,
                    enclosingSymbolFqn = enclosingSymbolFqn,
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
                        draft.arguments.map { argument -> argument.resolve(parameterNames) },
                    confidence = if (target == null) "UNRESOLVED" else "HEURISTIC",
                ),
            )
        }
        facts.memberReferences.forEach { reference ->
            store.put(
                CodeIndexKey.ref(
                    reference.symbolFqn,
                    originId,
                    relativePath,
                    reference.line,
                    reference.column,
                ),
                reference,
            )
        }
        facts.qualifiedResourceUsages.forEach { usage -> store.put(resourceUsageKey(usage), usage) }
        facts.staticResourceUsages.forEach { usage -> store.put(resourceUsageKey(usage), usage) }
    }

    private fun resolveCall(
        resolution: KotlinCallResolution,
        symbols: KotlinProjectSymbols,
    ): KotlinInvocationTarget? =
        when (resolution) {
            is KotlinCallResolution.Resolved -> resolution.target
            is KotlinCallResolution.Named -> resolveNamedCall(resolution.resolution, symbols)
        }

    private fun resolveNamedCall(
        resolution: KotlinNamedCallResolution,
        symbols: KotlinProjectSymbols,
    ): KotlinInvocationTarget? {
        val classOwner = resolution.classOwner
        if (classOwner != null) {
            symbols.firstByNameAndOwner(resolution.name, classOwner)?.let { symbol ->
                return KotlinInvocationTarget(symbol.fqn, resolution.name, null)
            }
        }
        val inheritedTarget = resolution.inheritedTarget
        if (inheritedTarget != null && symbols.hasSymbol(inheritedTarget)) {
            return KotlinInvocationTarget(inheritedTarget, resolution.name, null)
        }
        if (symbols.hasSymbol(resolution.topLevelTarget)) {
            return KotlinInvocationTarget(resolution.topLevelTarget, resolution.name, null)
        }
        resolution.imported?.let { imported ->
            val memberId =
                "${imported.substringBeforeLast('.')}#${imported.substringAfterLast('.')}"
            return KotlinInvocationTarget(
                imported,
                resolution.name,
                null,
                listOf(imported, memberId),
            )
        }
        return null
    }

    private fun resourceUsageKey(
        usage: dev.sebastiano.indexino.core.record.ResourceUsageRecord
    ): CodeIndexKey =
        CodeIndexKey.resourceUsage(
            packageName = usage.packageName,
            type = usage.type,
            name = usage.name,
            originId = usage.originId,
            relativeFile = usage.relativeFile,
            line = usage.line,
            column = usage.column,
        )

    private inline fun <T> timedPhase(
        context: IndexBuildContext,
        phase: String,
        block: () -> T,
    ): T {
        val progress = context.progress ?: return block()
        progress("index phase=$phase state=started")
        val started = System.nanoTime()
        val result = block()
        progress(
            "index phase=$phase state=completed durationMillis=" +
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        )
        return result
    }

    private fun sourceFilesToProcess(
        context: IndexBuildContext,
        extension: String,
    ): List<IndexedSource> =
        (context.changedSources.filter { it.path.endsWith(extension) } +
                metadataDependentSources(context, extension))
            .filter { it.isCode }
            .distinctBy { it.originId to it.path }

    private fun metadataDependentSources(
        context: IndexBuildContext,
        extension: String,
    ): List<IndexedSource> {
        val metadataModules =
            (context.changedSources + context.deletedSources).mapNotNull { source ->
                ResourceMetadata.metadataModule(source.path)?.let { source.originId to it }
            }
        if (metadataModules.isEmpty()) return emptyList()
        return context.sources.filter { source ->
            source.path.endsWith(extension) &&
                (source.originId to ResourceMetadata.moduleDirectory(source.path)) in
                    metadataModules
        }
    }

    /**
     * Cross-file lookups for the current run stay in memory. Targets absent from the run-local
     * table fall back to stored symbols. When any code source is unprocessed, stored declarations
     * for the same FQN also remain candidates (the symbol namespace is shared across language
     * producers); merge them before applying caller-origin preference so an incremental result
     * matches a full rebuild.
     */
    private class KotlinProjectSymbols(
        currentRunSymbols: List<SymbolRecord>,
        private val store: CodeIndexStore,
        private val mergeStoredSymbols: Boolean,
    ) {
        private val byFqn = currentRunSymbols.groupBy { symbol -> symbol.fqn }
        private val byName = currentRunSymbols.groupBy { symbol -> symbol.name }
        private val storedByFqn = mutableMapOf<String, List<SymbolRecord>>()
        private val presenceByFqn = mutableMapOf<String, Boolean>()

        fun firstByNameAndOwner(name: String, ownerFqn: String): SymbolRecord? =
            byName[name]?.firstOrNull { symbol -> symbol.ownerFqn == ownerFqn }

        fun hasSymbol(fqn: String): Boolean =
            byFqn.containsKey(fqn) || presenceByFqn.getOrPut(fqn) { store.hasSymbol(fqn) }

        fun parameterNames(fqn: String, argumentCount: Int, originId: String): List<String> {
            val currentRun = byFqn[fqn].orEmpty()
            val merged =
                (if (mergeStoredSymbols || currentRun.isEmpty()) {
                        currentRun + storedSymbols(fqn)
                    } else {
                        currentRun
                    })
                    .distinct()
            val matching = merged.filter { symbol -> symbol.matchesArity(argumentCount) }
            matching
                .singleOrNull { symbol -> symbol.originId == originId }
                ?.let { symbol ->
                    return symbol.parameterNames
                }
            if (!mergeStoredSymbols) {
                val storedMatching =
                    storedSymbols(fqn).distinct().filter { symbol ->
                        symbol.matchesArity(argumentCount)
                    }
                storedMatching
                    .singleOrNull { symbol -> symbol.originId == originId }
                    ?.let { symbol ->
                        return symbol.parameterNames
                    }
            }
            matching.singleOrNull()?.let { symbol ->
                return symbol.parameterNames
            }
            if (!mergeStoredSymbols) {
                storedSymbols(fqn)
                    .distinct()
                    .singleOrNull { symbol -> symbol.matchesArity(argumentCount) }
                    ?.let { symbol ->
                        return symbol.parameterNames
                    }
            }
            return emptyList()
        }

        private fun SymbolRecord.matchesArity(argumentCount: Int): Boolean =
            arity == null || arity >= argumentCount

        private fun storedSymbols(fqn: String): List<SymbolRecord> =
            storedByFqn.getOrPut(fqn) {
                val matches = mutableListOf<SymbolRecord>()
                store.forEachPrefix("sym:$fqn:") { _, record ->
                    if (record is SymbolRecord && record.fqn == fqn) matches += record
                    true
                }
                matches
            }
    }

    private companion object {
        const val LANGUAGE = "kotlin"
        const val EMIT_BATCH_SIZE = 64
    }
}
