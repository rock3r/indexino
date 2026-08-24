package dev.sebastiano.indexino.script

import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.IndexinoConfiguration
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.RuntimeAttachMode
import dev.sebastiano.indexino.model.ExperimentalIndexinoApi
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalIndexinoApi::class)
class IndexinoScriptHostTest {
    private val temporaryDirectories = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        IndexinoScriptHost.connectForTests = { workspace -> Indexino.connectBlocking(workspace) }
        temporaryDirectories.forEach { it.toFile().deleteRecursively() }
        temporaryDirectories.clear()
    }

    @Test
    fun `invalidates the compiled cache when script content changes`() {
        val workspace = createWorkspace()
        val cacheRoot = createTempDirectory("indexino-script-host-cache-")
        temporaryDirectories.add(cacheRoot)
        val script = workspace.resolve("check.indexino.kts")
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        try {
            indexWorkspace(workspace)
            useInProcessHostConnection()
            val host = IndexinoScriptHost.create()
            script.writeText("context.report(ScriptFinding.messageOnly(\"first\"))\n")
            val first = host.run(ScriptRequest.forFile(workspace, script))
            script.writeText("context.report(ScriptFinding.messageOnly(\"second\"))\n")
            val second = host.run(ScriptRequest.forFile(workspace, script))

            assertEquals(listOf(ScriptFinding.messageOnly("first")), first.findings)
            assertEquals(listOf(ScriptFinding.messageOnly("second")), second.findings)
            assertTrue(first.scriptDigest != second.scriptDigest)
        } finally {
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    fun `propagates managed parallel root failures`() {
        val workspace = createWorkspace()
        val cacheRoot = createTempDirectory("indexino-script-host-cache-")
        temporaryDirectories.add(cacheRoot)
        val script = workspace.resolve("check.indexino.kts")
        script.writeText(
            """
            context.managedParallel(listOf(1, 2)) { item ->
                if (item == 1) error("parallel failure")
            }
            """
                .trimIndent()
        )
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        try {
            indexWorkspace(workspace)
            useInProcessHostConnection()

            val failure =
                assertFailsWith<IndexinoScriptException> {
                    IndexinoScriptHost.create().run(ScriptRequest.forFile(workspace, script))
                }

            assertEquals(IndexinoScriptException.Kind.RUNTIME, failure.kind)
            assertEquals("parallel failure", failure.message)
        } finally {
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    fun `preserves input order for managed parallel findings`() {
        val workspace = createWorkspace()
        val cacheRoot = createTempDirectory("indexino-script-host-cache-")
        temporaryDirectories.add(cacheRoot)
        val script = workspace.resolve("check.indexino.kts")
        script.writeText(
            """
            context.managedParallel(listOf(1, 2)) { item ->
                if (item == 1) Thread.sleep(100)
                context.report(ScriptFinding.messageOnly(item.toString()))
            }
            """
                .trimIndent()
        )
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        try {
            indexWorkspace(workspace)
            useInProcessHostConnection()

            val report = IndexinoScriptHost.create().run(ScriptRequest.forFile(workspace, script))

            assertEquals(
                listOf(ScriptFinding.messageOnly("1"), ScriptFinding.messageOnly("2")),
                report.findings,
            )
        } finally {
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    fun `propagates script runtime failures`() {
        val workspace = createWorkspace()
        val cacheRoot = createTempDirectory("indexino-script-host-cache-")
        temporaryDirectories.add(cacheRoot)
        val script = workspace.resolve("check.indexino.kts")
        script.writeText("error(\"script failure\")\n")
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        try {
            indexWorkspace(workspace)
            useInProcessHostConnection()

            val failure =
                assertFailsWith<IndexinoScriptException> {
                    IndexinoScriptHost.create().run(ScriptRequest.forFile(workspace, script))
                }

            assertEquals(IndexinoScriptException.Kind.RUNTIME, failure.kind)
            assertEquals("script failure", failure.message)
        } finally {
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    fun `runs a non-suspending script against a pinned snapshot`() {
        val workspace = createWorkspace()
        val cacheRoot = createTempDirectory("indexino-script-host-cache-")
        temporaryDirectories.add(cacheRoot)
        val script = workspace.resolve("check.indexino.kts")
        script.writeText("context.report(ScriptFinding.messageOnly(\"script finding\"))\n")
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        try {
            indexWorkspace(workspace)
            useInProcessHostConnection()

            val report = IndexinoScriptHost.create().run(ScriptRequest.forFile(workspace, script))

            assertEquals(listOf(ScriptFinding.messageOnly("script finding")), report.findings)
            assertTrue(Regex("[0-9a-f]{64}").matches(report.scriptDigest))
        } finally {
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    private fun useInProcessHostConnection() {
        IndexinoScriptHost.connectForTests = { workspace ->
            Indexino.connectBlocking(
                IndexinoConfiguration.forWorkspace(workspace)
                    .withRuntimeAttach(RuntimeAttachMode.IN_PROCESS)
            )
        }
    }

    private fun indexWorkspace(workspace: Path) {
        Indexino.connectBlocking(
                IndexinoConfiguration.forWorkspace(workspace)
                    .withRuntimeAttach(RuntimeAttachMode.IN_PROCESS)
            )
            .use { indexino ->
                runBlocking {
                    indexino.refresh(RefreshRequest.forScope(IndexScope.gradle(":app"))).await()
                }
            }
    }

    private fun createWorkspace(): Path {
        val workspace = createTempDirectory("indexino-script-host-workspace-")
        temporaryDirectories.add(workspace)
        workspace.resolve("settings.gradle.kts").writeText("rootProject.name = \"script-host\"\n")
        workspace.resolve("app/src/main/kotlin/sample").createDirectories()
        workspace
            .resolve("app/build.gradle.kts")
            .writeText("plugins { kotlin(\"jvm\") version \"2.4.10\" }\n")
        workspace
            .resolve("app/src/main/kotlin/sample/Panel.kt")
            .writeText("package sample\nclass Panel\n")
        return workspace
    }
}
