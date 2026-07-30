package dev.sebastiano.indexino.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import dev.sebastiano.indexino.api.IndexSnapshot
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.IndexinoException
import dev.sebastiano.indexino.api.SnapshotFreshness
import dev.sebastiano.indexino.core.git.GitHeadResolver
import dev.sebastiano.indexino.core.manifest.IndexManifest
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
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.path.exists
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@OptIn(IndexinoInternalApi::class)
internal class QueryCommand : CliktCommand(name = "query") {
    private fun IndexManifest.workspaceRevisionFingerprint(): String {
        val graph =
            origins
                .ifEmpty {
                    listOf(
                        dev.sebastiano.indexino.core.manifest.IndexManifestOrigin(
                            originId = "workspace",
                            revision = commit.takeUnless(GitHeadResolver::isFilesystemRevision),
                            stateFingerprint = sourcesContentHash,
                        )
                    )
                }
                .sortedBy { it.originId }
                .joinToString("\u0001") { origin ->
                    listOf(
                            origin.originId,
                            origin.revision.orEmpty(),
                            origin.stateFingerprint,
                            origin.expectedRevision.orEmpty(),
                        )
                        .joinToString("\u0002")
                }
        val input =
            listOf(commit, scope, topology, includeDeps.toString(), sourcesContentHash, graph)
                .joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") {
            "%02x".format(Locale.ROOT, it)
        }
    }

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
        try {
            runBlocking {
                Indexino.connect(project).use { indexino ->
                    indexino.snapshot().use { snapshot ->
                        val request = CheckRequest.of(PluginId.of(application), checkId)
                        var offset = 0
                        do {
                            val findings =
                                snapshot.runCheck(request, QueryOptions.page(QUERY_LIMIT, offset))
                            findings.items.map(::toJsonl).forEach(output)
                            check(!findings.hasMore || findings.items.isNotEmpty()) {
                                "Check '${checkId}' returned an empty page with more results"
                            }
                            offset += findings.items.size
                        } while (findings.hasMore)
                    }
                }
            }
            return 0
        } catch (_: IndexinoException) {
            // Compatibility fallback for pre-daemon immutable project indexes.
        }
        val commit = GitHeadResolver.resolve(project)
        val resolver = IndexPathResolver(project)
        val manifestPath = resolver.resolveManifest(commit)
        if (!manifestPath.exists()) error("No index found for commit $commit; run 'index' first")
        val manifest = ManifestIO.read(manifestPath)
        val store = IndexStoreOpener.openForQuery(project, commit, sessionId)
        val revision =
            WorkspaceRevision(
                fingerprint = manifest.workspaceRevisionFingerprint(),
                origins =
                    manifest.origins
                        .ifEmpty {
                            listOf(
                                dev.sebastiano.indexino.core.manifest.IndexManifestOrigin(
                                    originId = "workspace",
                                    revision =
                                        commit.takeUnless(GitHeadResolver::isFilesystemRevision),
                                    stateFingerprint = manifest.sourcesContentHash,
                                )
                            )
                        }
                        .map { origin ->
                            SourceOriginRevision(
                                originId = SourceOriginId.of(origin.originId),
                                revision = origin.revision,
                                stateFingerprint = origin.stateFingerprint,
                                expectedRevision = origin.expectedRevision,
                            )
                        },
            )
        val snapshot =
            IndexSnapshot.create(
                store = store,
                revision = revision,
                generation = WorkspaceGenerationId.of(commit),
                freshnessAtAcquisition = SnapshotFreshness.UNKNOWN,
            )
        try {
            runBlocking {
                val request = CheckRequest.of(PluginId.of(application), checkId)
                var offset = 0
                do {
                    val findings =
                        snapshot.runCheck(
                            request,
                            QueryOptions.page(limit = QUERY_LIMIT, offset = offset),
                        )
                    findings.items.map(::toJsonl).forEach(output)
                    check(!findings.hasMore || findings.items.isNotEmpty()) {
                        "Check '${checkId}' returned an empty page with more results"
                    }
                    offset += findings.items.size
                } while (findings.hasMore)
            }
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
