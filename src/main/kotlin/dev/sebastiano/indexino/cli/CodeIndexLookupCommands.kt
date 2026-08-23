package dev.sebastiano.indexino.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.IndexinoException
import dev.sebastiano.indexino.core.git.GitHeadResolver
import dev.sebastiano.indexino.core.path.IndexPathResolver
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecordCodec
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.store.IndexStoreOpener
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.ReferenceQuery
import dev.sebastiano.indexino.model.SymbolQuery
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal class FindSymbolCommand : CliktCommand(name = "find-symbol") {
    private val project by
        option("--project").file(mustExist = true, mustBeReadable = true).required()
    private val name by option("--name").required()
    private val kind by option("--kind")
    private val language by option("--language")
    private val sessionId by option("--session-id")
    private val format by option("--format").default(JSONL)
    private val progressFormat by option("--progress-format").default(TEXT_PROGRESS_FORMAT)

    override fun run() {
        runLookup(
            command = "find-symbol",
            query = lookupQuery("name" to name, "kind" to kind, "language" to language),
            progressFormat = progressFormat,
            output = ::echo,
            progressOutput = { echo(it, err = true) },
        ) {
            requireJsonl(format)
            try {
                require(sessionId == null) {
                    "--session-id is not supported by daemon-backed lookup"
                }
                runBlocking {
                    Indexino.connect(project.toPath()).use { indexino ->
                        val query =
                            SymbolQuery.named(name)
                                .let { current -> kind?.let(current::withKind) ?: current }
                                .let { current -> language?.let(current::withLanguage) ?: current }
                        indexino.snapshot().use { snapshot ->
                            snapshot
                                .findSymbols(query, QueryOptions.page(LOOKUP_PAGE_SIZE))
                                .items
                                .map(::symbolRecord)
                        }
                    }
                }
            } catch (_: IndexinoException) {
                withStore(project.toPath(), sessionId) { findSymbols(it, name, kind, language) }
            }
        }
    }

    internal fun findSymbols(
        store: CodeIndexStore,
        name: String,
        kind: String? = null,
        language: String? = null,
    ): List<SymbolRecord> =
        sequenceOf("sym:", "res:")
            .flatMap(store::prefixScan)
            .map { it.second }
            .filterIsInstance<SymbolRecord>()
            .filter { symbol ->
                (symbol.name == name || symbol.fqn == name || name in symbol.aliases) &&
                    (kind == null || symbol.kind == kind) &&
                    (language == null || symbol.language == language)
            }
            .sortedWith(
                compareBy(
                    SymbolRecord::fqn,
                    SymbolRecord::relativeFile,
                    SymbolRecord::line,
                    SymbolRecord::column,
                )
            )
            .toList()
}

internal class FindReferencesCommand : CliktCommand(name = "find-references") {
    private val project by
        option("--project").file(mustExist = true, mustBeReadable = true).required()
    private val symbol by option("--symbol").required()
    private val sessionId by option("--session-id")
    private val format by option("--format").default(JSONL)
    private val progressFormat by option("--progress-format").default(TEXT_PROGRESS_FORMAT)

    override fun run() {
        runLookup(
            command = "find-references",
            query = lookupQuery("symbol" to symbol),
            progressFormat = progressFormat,
            output = ::echo,
            progressOutput = { echo(it, err = true) },
        ) {
            requireJsonl(format)
            if (symbol.startsWith("@")) {
                return@runLookup withStore(project.toPath(), sessionId) {
                    findReferences(it, symbol)
                }
            }
            try {
                require(sessionId == null) {
                    "--session-id is not supported by daemon-backed lookup"
                }
                runBlocking {
                    Indexino.connect(project.toPath()).use { indexino ->
                        indexino.snapshot().use { snapshot ->
                            snapshot
                                .findSymbols(
                                    SymbolQuery.named(
                                        symbol.substringAfterLast('#').substringAfterLast('.')
                                    ),
                                    QueryOptions.page(LOOKUP_PAGE_SIZE),
                                )
                                .items
                                .filter { symbol in it.aliases }
                                .flatMap { definition ->
                                    snapshot
                                        .findReferences(
                                            ReferenceQuery.to(definition.id),
                                            QueryOptions.page(LOOKUP_PAGE_SIZE),
                                        )
                                        .items
                                }
                                .distinctBy { it.location }
                                .map { referenceRecord(it, legacySymbolFqn(symbol)) }
                        }
                    }
                }
            } catch (_: IndexinoException) {
                withStore(project.toPath(), sessionId) { findReferences(it, symbol) }
            }
        }
    }

    internal fun findReferences(store: CodeIndexStore, symbol: String): List<ReferenceRecord> {
        val targetIds = linkedSetOf(symbol)
        sequenceOf("sym:", "res:")
            .flatMap(store::prefixScan)
            .map { it.second }
            .filterIsInstance<SymbolRecord>()
            .filter { it.fqn == symbol || symbol in it.aliases }
            .forEach {
                targetIds += it.fqn
                targetIds += it.aliases
            }
        return store
            .prefixScan("ref:")
            .map { it.second }
            .filterIsInstance<ReferenceRecord>()
            .filter { reference ->
                reference.symbolFqn in targetIds ||
                    reference.candidateSymbolFqns.any { it in targetIds }
            }
            .sortedWith(
                compareBy(
                    ReferenceRecord::relativeFile,
                    ReferenceRecord::line,
                    ReferenceRecord::column,
                )
            )
            .toList()
    }
}

internal class ResolveResourceCommand : CliktCommand(name = "resolve-resource") {
    private val project by
        option("--project").file(mustExist = true, mustBeReadable = true).required()
    private val type by option("--type").required()
    private val name by option("--name").required()
    private val sessionId by option("--session-id")
    private val format by option("--format").default(JSONL)
    private val progressFormat by option("--progress-format").default(TEXT_PROGRESS_FORMAT)

    override fun run() {
        runLookup(
            command = "resolve-resource",
            query = lookupQuery("type" to type, "name" to name),
            progressFormat = progressFormat,
            output = ::echo,
            progressOutput = { echo(it, err = true) },
        ) {
            requireJsonl(format)
            withStore(project.toPath(), sessionId) { resolveResources(it, type, name) }
        }
    }

    internal fun resolveResources(
        store: CodeIndexStore,
        type: String,
        name: String,
    ): List<SymbolRecord> =
        store
            .prefixScan("res:$type:$name:")
            .map { it.second }
            .filterIsInstance<SymbolRecord>()
            .filter { it.fqn == "res:$type:$name" }
            .sortedWith(
                compareBy(
                    SymbolRecord::relativeFile,
                    SymbolRecord::line,
                    SymbolRecord::column,
                    SymbolRecord::fqn,
                )
            )
            .toList()
}

private fun symbolRecord(symbol: dev.sebastiano.indexino.model.Symbol): SymbolRecord =
    SymbolRecord(
        fqn = symbol.aliases.lastOrNull() ?: symbol.id.value,
        relativeFile = symbol.location.file.path,
        line = symbol.location.line,
        column = symbol.location.column ?: 1,
        kind = symbol.kind,
        name = symbol.name,
        language = symbol.language,
        ownerFqn = symbol.ownerId?.value,
        signature = symbol.signature,
        arity = symbol.arity,
        aliases = symbol.aliases,
        originId = symbol.location.file.originId.value,
    )

private fun legacySymbolFqn(symbol: String): String =
    if (symbol.startsWith("@")) "res:${symbol.removePrefix("@").replace('/', ':')}" else symbol

private fun referenceRecord(
    reference: dev.sebastiano.indexino.model.Reference,
    requestedSymbol: String = reference.symbolId.value,
): ReferenceRecord =
    ReferenceRecord(
        symbolFqn = requestedSymbol,
        relativeFile = reference.location.file.path,
        line = reference.location.line,
        column = reference.location.column ?: 1,
        language = reference.language,
        referencedName = reference.referencedName,
        qualifier = reference.qualifier,
        candidateSymbolFqns = reference.candidateSymbolIds.map { it.value },
        arity = reference.arity,
        originId = reference.location.file.originId.value,
    )

private fun <T> withStore(project: Path, sessionId: String?, block: (CodeIndexStore) -> T): T {
    val commit = GitHeadResolver.resolve(project)
    val resolver = IndexPathResolver(project)
    if (!resolver.resolveManifest(commit).exists()) {
        throw IndexNotFoundException(commit)
    }
    val store = IndexStoreOpener.openForQuery(project, commit, sessionId)
    return try {
        block(store)
    } finally {
        store.close()
    }
}

private fun encode(record: CodeIndexRecord): String =
    CodeIndexRecordCodec.encode(record).decodeToString()

@Suppress("TooGenericExceptionCaught")
private fun <T : CodeIndexRecord> runLookup(
    command: String,
    query: JsonObject,
    progressFormat: String,
    output: (String) -> Unit,
    progressOutput: (String) -> Unit,
    lookup: () -> List<T>,
) {
    val reporter =
        when (progressFormat) {
            TEXT_PROGRESS_FORMAT -> null
            JSONL -> JsonlLookupProgressReporter(progressOutput)
            else ->
                throw UsageError(
                    "Unknown --progress-format: $progressFormat (expected text or jsonl)",
                    "--progress-format",
                    CliExitCodes.INVALID_ARGUMENTS,
                )
        }
    val elapsed = TimeSource.Monotonic.markNow()
    reporter?.started(command, query)
    try {
        val matches = lookup()
        matches.forEachIndexed { index, record ->
            reporter?.match(command, index + 1, record)
            output(encode(record))
        }
        reporter?.completed(command, matches.size, elapsed.elapsedNow().inWholeMilliseconds)
    } catch (exception: InvalidLookupFormatException) {
        handleLookupFailure(
            reporter = reporter,
            command = command,
            exception = exception,
            durationMillis = elapsed.elapsedNow().inWholeMilliseconds,
            exitCode = CliExitCodes.INVALID_ARGUMENTS,
        )
    } catch (exception: Exception) {
        handleLookupFailure(
            reporter = reporter,
            command = command,
            exception = exception,
            durationMillis = elapsed.elapsedNow().inWholeMilliseconds,
            exitCode = CliExitCodes.ANALYSIS_ERROR,
        )
    }
}

private fun handleLookupFailure(
    reporter: JsonlLookupProgressReporter?,
    command: String,
    exception: Exception,
    durationMillis: Long,
    exitCode: Int,
): Nothing {
    reporter?.failed(
        command = command,
        reason = lookupFailureReason(exception),
        message = exception.message ?: exception.javaClass.name,
        durationMillis = durationMillis,
    )
    if (reporter != null) {
        throw ProgramResult(exitCode)
    }
    throw exception
}

private fun lookupQuery(vararg fields: Pair<String, String?>): JsonObject = buildJsonObject {
    fields.forEach { (name, value) -> value?.let { put(name, JsonPrimitive(it)) } }
}

private fun lookupFailureReason(exception: Exception): String =
    when (exception) {
        is IndexNotFoundException -> "index_not_found"
        is InvalidLookupFormatException -> "invalid_format"
        else -> "lookup_error"
    }

private fun requireJsonl(format: String) {
    if (format != JSONL) {
        throw InvalidLookupFormatException()
    }
}

private class IndexNotFoundException(commit: String) :
    IllegalStateException("No index found for commit $commit; run 'index' first")

private class InvalidLookupFormatException :
    IllegalArgumentException("Only jsonl format is supported")

private const val JSONL = "jsonl"
private const val LOOKUP_PAGE_SIZE = 10_000
private const val TEXT_PROGRESS_FORMAT = "text"
