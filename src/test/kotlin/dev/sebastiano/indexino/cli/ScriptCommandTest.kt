package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.RuntimeAttachMode
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class ScriptCommandTest {
    init {
        Indexino.defaultRuntimeAttachModeForTests = RuntimeAttachMode.IN_PROCESS
    }

    private val temporaryDirectories = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        temporaryDirectories.forEach { it.toFile().deleteRecursively() }
        temporaryDirectories.clear()
    }

    @Test
    fun `runs an optional script host through the CLI adapter`() {
        val workspace = createWorkspace()
        val cacheRoot = createTempDirectory("indexino-script-command-cache-")
        temporaryDirectories.add(cacheRoot)
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        try {
            Indexino.connectBlocking(workspace).use { indexino ->
                runBlocking {
                    indexino.refresh(RefreshRequest.forScope(IndexScope.gradle(":app"))).await()
                }
            }
            val script = workspace.resolve("check.indexino.kts")
            script.writeText("context.report(ScriptFinding.messageOnly(\"CLI finding\"))\n")

            val report = ScriptCommand().runScript(workspace, script)
            val findings = report.javaClass.getMethod("getFindings").invoke(report) as List<*>

            assertEquals(1, findings.size)
            assertEquals(
                "CLI finding",
                findings.single()?.javaClass?.getMethod("getMessage")?.invoke(findings.single()),
            )
        } finally {
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    private fun createWorkspace(): Path {
        val workspace = createTempDirectory("indexino-script-command-workspace-")
        temporaryDirectories.add(workspace)
        workspace
            .resolve("settings.gradle.kts")
            .writeText("rootProject.name = \"script-command\"\n")
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
