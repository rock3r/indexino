package dev.sebastiano.indexino.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import dev.sebastiano.indexino.application.selectioncontext.SelectionContextJsonlFormatter
import dev.sebastiano.indexino.application.selectioncontext.SelectionContextQueryService
import dev.sebastiano.indexino.core.git.GitHeadResolver
import dev.sebastiano.indexino.core.manifest.ManifestIO
import dev.sebastiano.indexino.core.path.IndexPathResolver
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.store.IndexStoreOpener
import java.nio.file.Path
import kotlin.io.path.exists

internal class QueryCommand : CliktCommand(name = "query") {
    private val project by
        option("--project").file(mustExist = true, mustBeReadable = true).required()
    private val application by option("--application").required()
    private val preset by option("--preset")
    private val format by option("--format").default("jsonl")
    private val file by option("--file")
    private val line by option("--line").int()
    private val column by option("--column").int()
    private val sessionId by option("--session-id")

    override fun run() {
        runQuery(
            project = requireNotNull(project).toPath(),
            application = application,
            preset = preset,
            file = file,
            line = line,
            column = column,
            sessionId = sessionId,
            format = format,
            output = { echo(it) },
        )
    }

    fun runQuery(
        project: Path,
        application: String,
        preset: String? = null,
        file: String? = null,
        line: Int? = null,
        column: Int? = null,
        sessionId: String? = null,
        format: String = "jsonl",
        output: (String) -> Unit = {},
    ): Int {
        validateArgs(preset, file, line, format)
        val commit = GitHeadResolver.resolve(project)
        val resolver = IndexPathResolver(project)
        val manifestPath = resolver.resolveManifest(commit)
        if (!manifestPath.exists()) {
            error("No index found for commit $commit; run 'index' first")
        }
        val manifest = ManifestIO.read(manifestPath)
        val store = IndexStoreOpener.openForQuery(project, commit, sessionId)
        try {
            when (application) {
                "selection-context" -> {
                    val lines =
                        selectionContextJsonl(
                            store = store,
                            preset = preset,
                            file = file,
                            line = line,
                            column = column,
                            scopeTarget = manifest.scope,
                            scopeTopology = manifest.topology,
                        )
                    lines.forEach(output)
                }
                else -> error("Unknown application: $application")
            }
        } finally {
            store.close()
        }
        return 0
    }

    private fun validateArgs(preset: String?, file: String?, line: Int?, format: String) {
        val hasPointQuery = file != null || line != null
        when {
            hasPointQuery && (file == null || line == null) ->
                error("Point query requires both --file and --line")
            !hasPointQuery && preset == null -> error("Preset query requires --preset")
            hasPointQuery && preset != null ->
                error("Specify either --preset or --file/--line, not both")
            format != "jsonl" -> error("Only jsonl format is supported")
        }
    }

    private fun selectionContextJsonl(
        store: CodeIndexStore,
        preset: String?,
        file: String?,
        line: Int?,
        column: Int?,
        scopeTarget: String,
        scopeTopology: String,
    ): List<String> {
        val service =
            SelectionContextQueryService(
                store = store,
                scopeTarget = scopeTarget,
                scopeTopology = scopeTopology,
            )
        val rows =
            if (file != null && line != null) {
                service.queryPoint(file, line, column)
            } else {
                service.queryPreset(checkNotNull(preset))
            }
        return SelectionContextJsonlFormatter.formatLines(rows)
    }
}
