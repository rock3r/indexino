package dev.sebastiano.indexino.script

import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.IndexinoConfiguration
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.RuntimeAttachMode
import dev.sebastiano.indexino.model.ExperimentalIndexinoApi
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
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
class IndexinoScriptHostHardeningTest {
    private val temporaryDirectories = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        IndexinoScriptHost.connectForTests = { workspace -> Indexino.connectBlocking(workspace) }
        temporaryDirectories.forEach { it.toFile().deleteRecursively() }
        temporaryDirectories.clear()
    }

    @Test
    fun `emits actionable diagnostics for compilation failures`() {
        val workspace = createWorkspace()
        val script = workspace.resolve("broken.indexino.kts")
        script.writeText("val x: Int = \"not-an-int\"\n")
        withIndexedWorkspace(workspace) {
            val failure =
                assertFailsWith<IndexinoScriptException> {
                    IndexinoScriptHost.create().run(ScriptRequest.forFile(workspace, script))
                }
            assertEquals(IndexinoScriptException.Kind.COMPILATION, failure.kind)
            assertTrue(failure.diagnostics.isNotEmpty())
            assertTrue(
                failure.message.orEmpty().contains("broken.indexino.kts") ||
                    failure.diagnostics.any { it.contains("broken.indexino.kts") }
            )
            assertTrue(
                failure.diagnostics.any { diagnostic ->
                    diagnostic.contains("Int", ignoreCase = true) ||
                        diagnostic.contains("String", ignoreCase = true) ||
                        diagnostic.contains("type", ignoreCase = true)
                }
            )
        }
    }

    @Test
    fun `rejects imports outside the allowed dependency set`() {
        val workspace = createWorkspace()
        val script = workspace.resolve("bad-import.indexino.kts")
        script.writeText(
            """
            import dev.sebastiano.indexino.engine.WorkspaceRuntime
            context.report(ScriptFinding.messageOnly("should not run"))
            """
                .trimIndent() + "\n"
        )
        withIndexedWorkspace(workspace) {
            val failure =
                assertFailsWith<IndexinoScriptException> {
                    IndexinoScriptHost.create().run(ScriptRequest.forFile(workspace, script))
                }
            assertEquals(IndexinoScriptException.Kind.COMPILATION, failure.kind)
            assertTrue(
                failure.diagnostics.any { diagnostic ->
                    diagnostic.contains("WorkspaceRuntime") ||
                        diagnostic.contains("unresolved", ignoreCase = true) ||
                        diagnostic.contains("engine")
                }
            )
        }
    }

    @Test
    fun `rejects fully qualified references to forbidden packages`() {
        val workspace = createWorkspace()
        val script = workspace.resolve("bad-fqn.indexino.kts")
        script.writeText(
            """
            val ignored = dev.sebastiano.indexino.engine.WorkspaceRuntime::class
            context.report(ScriptFinding.messageOnly("should not run"))
            """
                .trimIndent() + "\n"
        )
        withIndexedWorkspace(workspace) {
            val failure =
                assertFailsWith<IndexinoScriptException> {
                    IndexinoScriptHost.create().run(ScriptRequest.forFile(workspace, script))
                }
            assertEquals(IndexinoScriptException.Kind.COMPILATION, failure.kind)
            assertTrue(failure.diagnostics.any { it.contains("engine") })
        }
    }

    @Test
    fun `maps runtime failures to actionable script diagnostics`() {
        val workspace = createWorkspace()
        val script = workspace.resolve("runtime.indexino.kts")
        script.writeText("error(\"boom-at-runtime\")\n")
        withIndexedWorkspace(workspace) {
            val failure =
                assertFailsWith<IndexinoScriptException> {
                    IndexinoScriptHost.create().run(ScriptRequest.forFile(workspace, script))
                }
            assertEquals(IndexinoScriptException.Kind.RUNTIME, failure.kind)
            assertTrue(failure.message.orEmpty().contains("boom-at-runtime"))
        }
    }

    @Test
    fun `enforces script time limits`() {
        val workspace = createWorkspace()
        val script = workspace.resolve("slow.indexino.kts")
        script.writeText("Thread.sleep(5_000L)\n")
        withIndexedWorkspace(workspace) {
            val failure =
                assertFailsWith<IndexinoScriptException> {
                    IndexinoScriptHost.create()
                        .run(
                            ScriptRequest.forFile(workspace, script)
                                .withTimeout(Duration.ofMillis(200))
                        )
                }
            assertEquals(IndexinoScriptException.Kind.TIMEOUT, failure.kind)
        }
    }

    @Test
    fun `honours cooperative cancellation`() {
        val workspace = createWorkspace()
        val script = workspace.resolve("cancel.indexino.kts")
        script.writeText("Thread.sleep(5_000L)\n")
        val cancelled = AtomicBoolean(false)
        withIndexedWorkspace(workspace) {
            val host = IndexinoScriptHost.create()
            val request =
                ScriptRequest.forFile(workspace, script)
                    .withTimeout(Duration.ofSeconds(10))
                    .withCancellation(cancelled)
            Thread {
                    Thread.sleep(100)
                    cancelled.set(true)
                }
                .start()
            val failure = assertFailsWith<IndexinoScriptException> { host.run(request) }
            assertEquals(IndexinoScriptException.Kind.CANCELLED, failure.kind)
        }
    }

    @Test
    fun `returns findings in deterministic order`() {
        val workspace = createWorkspace()
        val script = workspace.resolve("order.indexino.kts")
        script.writeText(
            """
            context.report(ScriptFinding.messageOnly("b"))
            context.report(ScriptFinding.messageOnly("a"))
            context.report(ScriptFinding.messageOnly("c"))
            """
                .trimIndent() + "\n"
        )
        withIndexedWorkspace(workspace) {
            val report = IndexinoScriptHost.create().run(ScriptRequest.forFile(workspace, script))
            assertEquals(listOf("a", "b", "c"), report.findings.map { it.message })
        }
    }

    @Test
    fun `recompiles when the host API version key changes without pinning a corrupt entry`() {
        val workspace = createWorkspace()
        val script = workspace.resolve("cache.indexino.kts")
        script.writeText("context.report(ScriptFinding.messageOnly(\"cached\"))\n")
        withIndexedWorkspace(workspace) {
            val host = IndexinoScriptHost.create()
            val first = host.run(ScriptRequest.forFile(workspace, script))
            assertEquals(listOf("cached"), first.findings.map { it.message })

            IndexinoScriptHost.overrideHostApiVersionForTests = "force-miss"
            try {
                script.writeText("context.report(ScriptFinding.messageOnly(\"recompiled\"))\n")
                val second = host.run(ScriptRequest.forFile(workspace, script))
                assertEquals(listOf("recompiled"), second.findings.map { it.message })
            } finally {
                IndexinoScriptHost.overrideHostApiVersionForTests = null
            }
        }
    }

    @Test
    fun `refuses new runs while an uncooperative timed-out evaluation is abandoned`() {
        val workspace = createWorkspace()
        val script = workspace.resolve("spin.indexino.kts")
        script.writeText(
            """
            var counter = 0L
            while (true) {
                counter += 1L
            }
            """
                .trimIndent() + "\n"
        )
        withIndexedWorkspace(workspace) {
            val host = IndexinoScriptHost.create()
            // Compilation is outside the evaluation time budget; a short timeout must still
            // abandon a non-cooperative evaluation loop on cold CI hosts.
            val failure =
                assertFailsWith<IndexinoScriptException> {
                    host.run(
                        ScriptRequest.forFile(workspace, script).withTimeout(Duration.ofMillis(200))
                    )
                }
            assertEquals(IndexinoScriptException.Kind.TIMEOUT, failure.kind)
            assertTrue(
                failure.message.orEmpty().contains("abandoned"),
                "expected abandoned timeout, got: ${failure.message}",
            )

            val refused =
                assertFailsWith<IndexinoScriptException> {
                    host.run(
                        ScriptRequest.forFile(workspace, script).withTimeout(Duration.ofMillis(200))
                    )
                }
            assertEquals(IndexinoScriptException.Kind.INVALID_REQUEST, refused.kind)
        }
    }

    @Test
    fun `compilation failures do not leave a poisoned compiled-script cache entry`() {
        val workspace = createWorkspace()
        val script = workspace.resolve("poison.indexino.kts")
        withIndexedWorkspace(workspace) {
            val host = IndexinoScriptHost.create()
            script.writeText("val x: Int = \"bad\"\n")
            assertFailsWith<IndexinoScriptException> {
                host.run(ScriptRequest.forFile(workspace, script))
            }
            script.writeText("context.report(ScriptFinding.messageOnly(\"recovered\"))\n")
            val report = host.run(ScriptRequest.forFile(workspace, script))
            assertEquals(listOf("recovered"), report.findings.map { it.message })
        }
    }

    private fun withIndexedWorkspace(workspace: Path, block: () -> Unit) {
        val cacheRoot = createTempDirectory("indexino-script-harden-cache-")
        temporaryDirectories.add(cacheRoot)
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        try {
            indexWorkspace(workspace)
            useInProcessHostConnection()
            block()
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
        val workspace = createTempDirectory("indexino-script-harden-workspace-")
        temporaryDirectories.add(workspace)
        workspace.resolve("settings.gradle.kts").writeText("rootProject.name = \"script-harden\"\n")
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
