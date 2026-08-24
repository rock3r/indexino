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
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Motivating disposable query from issue #43 / PUBLIC-API-DESIGN § “Disposable content-slot query”:
 * find usages of composable A whose content-slot subtree contains usages of composable B, using
 * only the public read-only snapshot/script DSL.
 */
@OptIn(ExperimentalIndexinoApi::class)
class ComposePairScriptE2ETest {
    private val temporaryDirectories = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        IndexinoScriptHost.connectForTests = { workspace -> Indexino.connectBlocking(workspace) }
        temporaryDirectories.forEach { it.toFile().deleteRecursively() }
        temporaryDirectories.clear()
    }

    @Test
    fun `finds ComposableA content slots that contain ComposableB via public script DSL`() {
        val workspace = createComposePairWorkspace()
        val cacheRoot = createTempDirectory("indexino-compose-pair-cache-")
        temporaryDirectories.add(cacheRoot)
        val script =
            workspace.resolve("compose-pair.indexino.kts").also { path ->
                path.writeText(COMPOSE_PAIR_SCRIPT.trimIndent() + "\n")
            }
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        try {
            indexWorkspace(workspace)
            useInProcessHostConnection()

            val report = IndexinoScriptHost.create().run(ScriptRequest.forFile(workspace, script))

            assertEquals(1, report.findings.size)
            val finding = report.findings.single()
            assertEquals("ComposableA content contains ComposableB", finding.message)
            assertTrue(finding.range != null)
            assertTrue(script.readText().contains("CallQuery.to(\"ComposableA\")"))
            assertTrue(!script.readText().contains("dev.sebastiano.indexino.engine"))
            assertTrue(!script.readText().contains("psi", ignoreCase = true))
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

    private fun createComposePairWorkspace(): Path {
        val workspace = createTempDirectory("indexino-compose-pair-workspace-")
        temporaryDirectories.add(workspace)
        workspace.resolve("settings.gradle.kts").writeText("rootProject.name = \"compose-pair\"\n")
        workspace.resolve("app/src/main/kotlin/sample").createDirectories()
        workspace
            .resolve("app/build.gradle.kts")
            .writeText("plugins { kotlin(\"jvm\") version \"2.4.10\" }\n")
        workspace
            .resolve("app/src/main/kotlin/sample/ComposePair.kt")
            .writeText(
                """
                package sample

                fun ComposableA(content: () -> Unit) = content()
                fun ComposableB() {}
                fun Screen() {
                    ComposableA {
                        ComposableB()
                    }
                    ComposableA {
                        // no B
                    }
                }
                """
                    .trimIndent() + "\n"
            )
        return workspace
    }

    private companion object {
        private const val COMPOSE_PAIR_SCRIPT =
            """
            val outerCalls = context.calls.find(
                CallQuery.to("ComposableA"),
                QueryOptions.page(500),
            )

            for (outer in outerCalls.items) {
                val content = outer.arguments.firstOrNull { argument ->
                    // Dogfood (#43): trailing lambdas often land as LAMBDA with a null
                    // resolvedName until parameter names are available on the call fact.
                    argument.resolvedName == "content" ||
                        argument.kind == ArgumentKind.TRAILING_LAMBDA ||
                        argument.kind == ArgumentKind.LAMBDA
                } ?: continue

                val containsB = content.nestedCallIds
                    .mapNotNull { id -> context.calls.byId(id) }
                    .any { call -> call.calleeName == "ComposableB" }

                if (containsB) {
                    context.report(
                        ScriptFinding.at(outer.range)
                            .message("ComposableA content contains ComposableB")
                    )
                }
            }
            """
    }
}
