package dev.sebastiano.indexino.cli

import com.github.ajalt.clikt.testing.test
import dev.sebastiano.indexino.core.manifest.ManifestIO
import dev.sebastiano.indexino.core.path.IndexPathResolver
import dev.sebastiano.indexino.core.record.PluginFactRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.topology.BuildSystem
import dev.sebastiano.indexino.topology.TopologyRequest
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GradleIndexCommandTest {
    private val tempDirs = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun `index gradle module builds store with selection context`() {
        val workspace = createGitWorkspace()
        val exitCode =
            IndexCommand()
                .runIndexedBuild(
                    project = workspace,
                    topologyRequest =
                        TopologyRequest(
                            buildSystem = BuildSystem.GRADLE,
                            gradleModule = ":ui",
                            includeDeps = false,
                        ),
                    applications = listOf("dev.sebastiano.selection-context"),
                )
        assertEquals(0, exitCode)

        val commit = gitHead(workspace)
        val manifest = ManifestIO.read(IndexPathResolver(workspace).resolveManifest(commit))
        assertEquals(":ui", manifest.scope)
        assertEquals("gradle-parse", manifest.topology)

        val store =
            XodusCodeIndexStore.open(
                IndexPathResolver(workspace).resolveBaseStore(commit),
                readOnly = true,
            )
        try {
            val facts =
                store
                    .prefixScan("plugin:dev.sebastiano.selection-context:")
                    .map { it.second as PluginFactRecord }
                    .toList()
            assertTrue(facts.any { it.factKey.startsWith("selection-site:") })
        } finally {
            store.close()
        }
    }

    @Test
    fun `resolve resource reads published snapshot without project compatibility store`() {
        val workspace = createGitWorkspace()
        for (configuration in listOf("values", "values-night")) {
            val resource = workspace.resolve("ui/src/main/res/$configuration/strings.xml")
            Files.createDirectories(resource.parent)
            Files.writeString(
                resource,
                buildString {
                    appendLine("<resources>")
                    repeat(if (configuration == "values") 10_000 else 1) {
                        appendLine("<string name=\"title\">Hello</string>")
                    }
                    appendLine("</resources>")
                },
            )
        }
        val indexed =
            MainCommand()
                .test(
                    listOf(
                        "index",
                        "--project",
                        workspace.toString(),
                        "--build-system",
                        "gradle",
                        "--gradle-module",
                        ":ui",
                        "--no-auto-refresh",
                    )
                )
        assertEquals(0, indexed.statusCode, indexed.output)
        val resolver = IndexPathResolver(workspace)
        val projection = resolver.resolveBaseStore(gitHead(workspace))
        assertTrue(projection.toFile().deleteRecursively())
        assertTrue(Files.deleteIfExists(resolver.resolveManifest(gitHead(workspace))))

        val result =
            MainCommand()
                .test(
                    listOf(
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
                )

        assertEquals(0, result.statusCode, result.output)
        val rows =
            result.stdout
                .lineSequence()
                .filter(String::isNotBlank)
                .map { Json.parseToJsonElement(it).jsonObject }
                .toList()
        assertEquals(10_001, rows.size)
        assertEquals(
            "ui/src/main/res/values-night/strings.xml",
            rows.first().getValue("relativeFile").jsonPrimitive.content,
        )
        assertTrue(
            rows.drop(1).all {
                it.getValue("relativeFile").jsonPrimitive.content ==
                    "ui/src/main/res/values/strings.xml"
            }
        )
        assertTrue(rows.all { it.getValue("fqn").jsonPrimitive.content == "res:string:title" })
        assertEquals("2", rows.first().getValue("line").jsonPrimitive.content)
        assertEquals("10001", rows.last().getValue("line").jsonPrimitive.content)
        assertTrue(rows.all { it.getValue("column").jsonPrimitive.content == "1" })
        assertTrue(rows.all { it.getValue("language").jsonPrimitive.content == "xml" })
        assertTrue(
            rows.all {
                it.getValue("aliases").jsonArray.map { alias -> alias.jsonPrimitive.content } ==
                    listOf("@string/title")
            }
        )
        assertTrue(!Files.exists(projection), "Lookup recreated the compatibility store")
        assertTrue(!Files.exists(resolver.resolveManifest(gitHead(workspace))))
    }

    private fun createGitWorkspace(): java.nio.file.Path {
        val workspace = createTempDirectory("gradle-index-")
        tempDirs.add(workspace)
        val fixtureRoot = Path("src/test/resources/gradle-fixtures/multi-module")
        Files.walk(fixtureRoot).forEach { path ->
            val relative = fixtureRoot.relativize(path)
            val dest = workspace.resolve(relative)
            if (Files.isDirectory(path)) {
                Files.createDirectories(dest)
            } else {
                Files.createDirectories(dest.parent)
                Files.copy(path, dest)
            }
        }
        runGit(workspace, "init")
        runGit(workspace, "config", "user.email", "test@example.com")
        runGit(workspace, "config", "user.name", "Test User")
        runGit(workspace, "add", ".")
        runGit(workspace, "commit", "-m", "fixture")
        return workspace
    }

    private fun gitHead(workspace: java.nio.file.Path): String =
        ProcessBuilder("git", "-C", workspace.toString(), "rev-parse", "HEAD")
            .redirectErrorStream(true)
            .start()
            .inputStream
            .bufferedReader()
            .readText()
            .trim()

    private fun runGit(workspace: java.nio.file.Path, vararg args: String) {
        check(
            ProcessBuilder(
                    *listOf("git", "-C", workspace.toString(), "-c", "commit.gpgsign=false", *args)
                        .toTypedArray()
                )
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0
        )
    }
}
