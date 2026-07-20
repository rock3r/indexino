package dev.sebastiano.indexino.cli

import com.github.ajalt.clikt.testing.test
import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.manifest.IndexManifest
import dev.sebastiano.indexino.core.manifest.ManifestIO
import dev.sebastiano.indexino.core.path.IndexPathResolver
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CodeIndexLookupCommandTest {
    private val tempDirs = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `find symbol emits lookup progress events without changing result rows`() {
        val workspace = indexedWorkspace()
        val result =
            runCli(
                "find-symbol",
                "--project",
                workspace.toString(),
                "--name",
                "Panel",
                "--progress-format",
                "jsonl",
            )

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.stderr.contains("\"event\":\"lookup_started\""))
        assertTrue(result.stderr.contains("\"event\":\"lookup_match\""))
        assertTrue(result.stderr.contains("\"event\":\"lookup_completed\""))
        assertTrue(result.output.contains("\"fqn\":\"sample.Panel\""))
    }

    @Test
    fun `lookup match events preserve final result order and emitted count`() {
        val workspace = indexedWorkspace()
        val result =
            runCli(
                "find-symbol",
                "--project",
                workspace.toString(),
                "--name",
                "Panel",
                "--progress-format",
                "jsonl",
            )

        assertEquals(0, result.statusCode, result.output)
        val events = progressEvents(result.stderr)
        val matches = events.filter { it.value("event") == "lookup_match" }
        assertEquals(listOf("1", "2"), matches.map { it.value("emittedMatchCount") })
        assertEquals(
            result.output.lineSequence().filter(String::isNotBlank).toList(),
            matches.map { checkNotNull(it["record"]).toString() },
        )
        assertEquals("lookup_completed", events.last().value("event"))
        assertEquals("2", events.last().value("totalMatchCount"))
        assertTrue(events.last().value("durationMillis")!!.toLong() >= 0)
    }

    @Test
    fun `lookup progress completes without match events for zero matches`() {
        val workspace = indexedWorkspace()
        val result =
            runCli(
                "find-symbol",
                "--project",
                workspace.toString(),
                "--name",
                "Missing",
                "--progress-format",
                "jsonl",
            )

        assertEquals(0, result.statusCode, result.output)
        val events = progressEvents(result.stderr)
        assertEquals("lookup_started", events.first().value("event"))
        assertFalse(events.any { it.value("event") == "lookup_match" })
        assertEquals("lookup_completed", events.last().value("event"))
        assertEquals("0", events.last().value("totalMatchCount"))
    }

    @Test
    fun `lookup progress includes aliases and cross language references`() {
        val workspace = indexedWorkspace()
        val result =
            runCli(
                "find-references",
                "--project",
                workspace.toString(),
                "--symbol",
                "sample.greet",
                "--progress-format",
                "jsonl",
            )

        assertEquals(0, result.statusCode, result.output)
        val match = progressEvents(result.stderr).single { it.value("event") == "lookup_match" }
        assertEquals("find-references", match.value("command"))
        assertTrue(match["record"].toString().contains("sample.KotlinApiKt#greet"))
    }

    @Test
    fun `resolve resource emits lookup progress events`() {
        val workspace = indexedWorkspace()
        val result =
            runCli(
                "resolve-resource",
                "--project",
                workspace.toString(),
                "--type",
                "string",
                "--name",
                "title",
                "--progress-format",
                "jsonl",
            )

        assertEquals(0, result.statusCode, result.output)
        val events = progressEvents(result.stderr)
        assertEquals("resolve-resource", events.first().value("command"))
        assertEquals("lookup_match", events[1].value("event"))
        assertEquals("lookup_completed", events.last().value("event"))
    }

    @Test
    fun `lookup progress reports typed query errors`() {
        val workspace = indexedWorkspace()
        val result =
            runCli(
                "find-symbol",
                "--project",
                workspace.toString(),
                "--name",
                "Panel",
                "--format",
                "text",
                "--progress-format",
                "jsonl",
            )

        assertEquals(CliExitCodes.INVALID_ARGUMENTS, result.statusCode, result.output)
        val failed = progressEvents(result.stderr).last()
        assertEquals("lookup_failed", failed.value("event"))
        assertEquals("invalid_format", failed.value("failureReason"))
        assertTrue(failed.value("message")!!.contains("Only jsonl format"))
    }

    @Test
    fun `lookup default output remains unchanged when progress is not requested`() {
        val workspace = indexedWorkspace()
        val defaultResult =
            runCli("find-symbol", "--project", workspace.toString(), "--name", "Panel")
        val progressResult =
            runCli(
                "find-symbol",
                "--project",
                workspace.toString(),
                "--name",
                "Panel",
                "--progress-format",
                "jsonl",
            )

        assertEquals(0, defaultResult.statusCode, defaultResult.output)
        assertEquals("", defaultResult.stderr)
        assertEquals(defaultResult.output, progressResult.output)
    }

    @Test
    fun `lookup progress reports a missing index with a typed failure`() {
        val workspace = unindexedWorkspace()
        val result =
            runCli(
                "find-symbol",
                "--project",
                workspace.toString(),
                "--name",
                "Panel",
                "--progress-format",
                "jsonl",
            )

        assertFalse(result.statusCode == 0, result.output)
        val failed = progressEvents(result.stderr).last()
        assertEquals("lookup_failed", failed.value("event"))
        assertEquals("index_not_found", failed.value("failureReason"))
    }

    @Test
    fun `invalid progress format remains a usage error`() {
        val workspace = indexedWorkspace()
        val result =
            runCli(
                "find-symbol",
                "--project",
                workspace.toString(),
                "--name",
                "Panel",
                "--progress-format",
                "invalid",
            )

        assertEquals(CliExitCodes.INVALID_ARGUMENTS, result.statusCode)
        assertTrue(result.stderr.contains("Unknown --progress-format"))
    }

    @Test
    fun `find symbol emits deterministic machine readable rows`() {
        val workspace = indexedWorkspace()
        val result = runCli("find-symbol", "--project", workspace.toString(), "--name", "Panel")

        assertEquals(0, result.statusCode, result.output)
        assertTrue(
            result.output.lineSequence().filter { it.isNotBlank() }.all { it.startsWith("{") }
        )
        assertTrue(result.output.contains("\"fqn\":\"sample.Panel\""))
        assertTrue(result.output.contains("\"language\":\"java\""))
    }

    @Test
    fun `find references resolves a language neutral member id`() {
        val workspace = indexedWorkspace()
        val result =
            runCli(
                "find-references",
                "--project",
                workspace.toString(),
                "--symbol",
                "sample.Panel#render",
            )

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("\"symbolFqn\":\"sample.Panel#render\""))
        assertTrue(result.output.contains("\"relativeFile\":\"src/App.kt\""))
    }

    @Test
    fun `lookups join Kotlin source ids with generated JVM facade aliases`() {
        val workspace = indexedWorkspace()
        val symbolResult =
            runCli(
                "find-symbol",
                "--project",
                workspace.toString(),
                "--name",
                "sample.KotlinApiKt#greet",
            )
        val referencesResult =
            runCli("find-references", "--project", workspace.toString(), "--symbol", "sample.greet")

        assertEquals(0, symbolResult.statusCode, symbolResult.output)
        assertTrue(symbolResult.output.contains("\"fqn\":\"sample.greet\""))
        assertEquals(0, referencesResult.statusCode, referencesResult.output)
        assertTrue(referencesResult.output.contains("\"symbolFqn\":\"sample.KotlinApiKt#greet\""))
    }

    @Test
    fun `resolve resource disambiguates by type and name`() {
        val workspace = indexedWorkspace()
        val result =
            runCli(
                "resolve-resource",
                "--project",
                workspace.toString(),
                "--type",
                "string",
                "--name",
                "title",
            )

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("\"fqn\":\"res:string:title\""))
        assertTrue(result.output.contains("values/strings.xml"))
    }

    @Test
    fun `find references expands Android resource aliases`() {
        val workspace = indexedWorkspace()
        val result =
            runCli(
                "find-references",
                "--project",
                workspace.toString(),
                "--symbol",
                "@string/title",
            )

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("\"symbolFqn\":\"res:string:title\""))
    }

    private fun progressEvents(output: String): List<JsonObject> =
        output
            .lineSequence()
            .filter(String::isNotBlank)
            .map { Json.parseToJsonElement(it).jsonObject }
            .toList()

    private fun JsonObject.value(name: String): String? = this[name]?.jsonPrimitive?.content

    private fun unindexedWorkspace(): java.nio.file.Path {
        val workspace = createTempDirectory("unindexed-lookup-cli-")
        tempDirs.add(workspace)
        runGit(workspace, "init")
        runGit(workspace, "config", "user.email", "test@example.com")
        runGit(workspace, "config", "user.name", "Test User")
        workspace.resolve("README").toFile().writeText("fixture")
        runGit(workspace, "add", ".")
        runGit(workspace, "commit", "-m", "fixture")
        return workspace
    }

    private fun indexedWorkspace(): java.nio.file.Path {
        val workspace = createTempDirectory("lookup-cli-")
        tempDirs.add(workspace)
        runGit(workspace, "init")
        runGit(workspace, "config", "user.email", "test@example.com")
        runGit(workspace, "config", "user.name", "Test User")
        workspace.resolve("README").toFile().writeText("fixture")
        runGit(workspace, "add", ".")
        runGit(workspace, "commit", "-m", "fixture")
        val commit = runGit(workspace, "rev-parse", "HEAD").trim()
        val resolver = IndexPathResolver(workspace)
        val store = XodusCodeIndexStore.open(resolver.resolveBaseStore(commit))
        try {
            populateLookupRecords(store)
        } finally {
            store.close()
        }
        ManifestIO.write(
            resolver.resolveManifest(commit),
            IndexManifest(
                commit = commit,
                indexerVersion = "test",
                scope = ":",
                topology = "gradle-parse",
                sourceFileCount = 3,
                sourcesContentHash = "sha256:test",
                builtAt = "2026-01-01T00:00:00Z",
            ),
        )
        return workspace
    }

    private fun populateLookupRecords(store: XodusCodeIndexStore) {
        val symbol =
            SymbolRecord(
                fqn = "sample.Panel",
                relativeFile = "src/Panel.java",
                line = 3,
                kind = "class",
                name = "Panel",
                language = "java",
            )
        store.put(
            CodeIndexKey.symbolDefinition(symbol.fqn, symbol.relativeFile, symbol.line, 1),
            symbol,
        )
        val earlierPanel =
            SymbolRecord(
                fqn = "sample.ZPanel",
                relativeFile = "src/Earlier.java",
                line = 1,
                kind = "class",
                name = "Panel",
                language = "java",
            )
        store.put(
            CodeIndexKey.symbolDefinition(
                earlierPanel.fqn,
                earlierPanel.relativeFile,
                earlierPanel.line,
                1,
            ),
            earlierPanel,
        )
        val reference =
            ReferenceRecord(
                symbolFqn = "sample.Panel#render",
                relativeFile = "src/App.kt",
                line = 8,
                column = 5,
                language = "kotlin",
            )
        store.put(
            CodeIndexKey.ref(
                reference.symbolFqn,
                reference.relativeFile,
                reference.line,
                reference.column,
            ),
            reference,
        )
        val topLevelFunction =
            SymbolRecord(
                fqn = "sample.greet",
                relativeFile = "src/KotlinApi.kt",
                line = 3,
                kind = "function",
                name = "greet",
                language = "kotlin",
                aliases = listOf("sample.KotlinApiKt#greet"),
            )
        store.put(
            CodeIndexKey.symbolDefinition(
                topLevelFunction.fqn,
                topLevelFunction.relativeFile,
                topLevelFunction.line,
                1,
            ),
            topLevelFunction,
        )
        val facadeReference =
            ReferenceRecord(
                symbolFqn = "sample.KotlinApiKt#greet",
                relativeFile = "src/JavaCaller.java",
                line = 6,
                column = 9,
                language = "java",
            )
        store.put(
            CodeIndexKey.ref(
                facadeReference.symbolFqn,
                facadeReference.relativeFile,
                facadeReference.line,
                facadeReference.column,
            ),
            facadeReference,
        )
        populateResourceRecords(store)
    }

    private fun populateResourceRecords(store: XodusCodeIndexStore) {
        val resource =
            SymbolRecord(
                fqn = "res:string:title",
                relativeFile = "app/src/main/res/values/strings.xml",
                line = 2,
                kind = "resource",
                name = "title",
                language = "xml",
                ownerFqn = "res:string",
                aliases = listOf("@string/title"),
            )
        store.put(
            CodeIndexKey.resource("string", "title", resource.relativeFile, resource.line),
            resource,
        )
        val resourceReference =
            ReferenceRecord(
                symbolFqn = "res:string:title",
                relativeFile = "app/src/main/res/layout/main.xml",
                line = 4,
                column = 12,
                context = "resource",
                language = "xml",
            )
        store.put(
            CodeIndexKey.ref(
                resourceReference.symbolFqn,
                resourceReference.relativeFile,
                resourceReference.line,
                resourceReference.column,
            ),
            resourceReference,
        )
    }

    private fun runCli(vararg args: String): CliResult {
        val result = MainCommand().test(args.toList())
        return CliResult(result.stdout, result.statusCode, result.stderr)
    }

    private fun runGit(workspace: java.nio.file.Path, vararg args: String): String {
        val process =
            ProcessBuilder(*listOf("git", "-C", workspace.toString(), *args).toTypedArray())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output
    }

    private data class CliResult(val output: String, val statusCode: Int, val stderr: String)
}
