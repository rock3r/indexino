package dev.sebastiano.indexino.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import dev.sebastiano.indexino.api.IndexSnapshot
import dev.sebastiano.indexino.api.SnapshotFreshness
import dev.sebastiano.indexino.core.git.GitHeadResolver
import dev.sebastiano.indexino.core.manifest.ManifestIO
import dev.sebastiano.indexino.core.path.IndexPathResolver
import dev.sebastiano.indexino.core.store.IndexStoreOpener
import dev.sebastiano.indexino.model.CheckRequest
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.SourceOriginRevision
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import dev.sebastiano.indexino.model.WorkspaceRevision
import java.nio.file.Path
import kotlin.io.path.exists
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@OptIn(IndexinoInternalApi::class)
internal class QueryCommand : CliktCommand(name = "query") {
    private val project by
        option("--project").file(mustExist = true, mustBeReadable = true).required()
    private val application by option("--application").required()
    private val preset by option("--preset").required()
    private val format by option("--format").default("jsonl")
    private val sessionId by option("--session-id")

    override fun run() {
        runQuery(
            project = requireNotNull(project).toPath(),
            application = application,
            checkId = preset,
            sessionId = sessionId,
            format = format,
            output = { echo(it) },
        )
    }

    fun runQuery(
        project: Path,
        application: String,
        checkId: String,
        sessionId: String? = null,
        format: String = "jsonl",
        output: (String) -> Unit = {},
    ): Int {
        require(format == "jsonl") { "Only jsonl format is supported" }
        val commit = GitHeadResolver.resolve(project)
        val resolver = IndexPathResolver(project)
        val manifestPath = resolver.resolveManifest(commit)
        if (!manifestPath.exists()) error("No index found for commit $commit; run 'index' first")
        val manifest = ManifestIO.read(manifestPath)
        val store = IndexStoreOpener.openForQuery(project, commit, sessionId)
        val revision =
            WorkspaceRevision(
                fingerprint = manifest.sourcesContentHash,
                origins =
                    listOf(
                        SourceOriginRevision(
                            originId = SourceOriginId.of("workspace"),
                            revision = commit.takeUnless(GitHeadResolver::isFilesystemRevision),
                            stateFingerprint = manifest.sourcesContentHash,
                            expectedRevision = null,
                        )
                    ),
            )
        val snapshot =
            IndexSnapshot.create(
                store = store,
                revision = revision,
                generation = WorkspaceGenerationId.of(commit),
                freshnessAtAcquisition = SnapshotFreshness.UNKNOWN,
            )
        try {
            val findings = runBlocking {
                snapshot.runCheck(
                    CheckRequest.of(PluginId.of(application), checkId),
                    QueryOptions.page(limit = QUERY_LIMIT),
                )
            }
            findings.items.map(::toJsonl).forEach(output)
        } finally {
            snapshot.close()
        }
        return 0
    }

    private fun toJsonl(finding: dev.sebastiano.indexino.model.Finding): String =
        Json.encodeToString(
            buildJsonObject {
                put("plugin", finding.plugin.value)
                put("checkId", finding.checkId)
                put("message", finding.message)
                finding.range?.let { range ->
                    putJsonObject("range") {
                        put("file", range.start.file.path)
                        put("line", range.start.line)
                        range.start.column?.let { put("column", it) }
                    }
                }
                putJsonObject("properties") {
                    finding.properties.forEach { (key, value) -> put(key, value) }
                }
            }
        )

    private companion object {
        const val QUERY_LIMIT: Int = 10_000
    }
}
