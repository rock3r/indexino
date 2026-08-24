package dev.sebastiano.indexino.script

import dev.sebastiano.indexino.api.FreshnessPolicy
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.IndexinoConfiguration
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.RuntimeAttachMode
import dev.sebastiano.indexino.model.ArgumentKind
import dev.sebastiano.indexino.model.CallQuery
import dev.sebastiano.indexino.model.ExperimentalIndexinoApi
import dev.sebastiano.indexino.model.QueryOptions
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * Parity between the disposable script check and the same logic expressed against the public
 * snapshot API (compiled Kotlin).
 */
@OptIn(ExperimentalIndexinoApi::class)
class ScriptCompiledParityTest {
    private val temporaryDirectories = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        IndexinoScriptHost.connectForTests = { workspace -> Indexino.connectBlocking(workspace) }
        temporaryDirectories.forEach { it.toFile().deleteRecursively() }
        temporaryDirectories.clear()
    }

    @Test
    fun `scripted and compiled content-slot checks produce equivalent findings`() {
        val workspace = createComposePairWorkspace()
        val cacheRoot = createTempDirectory("indexino-script-parity-cache-")
        temporaryDirectories.add(cacheRoot)
        val script = workspace.resolve("compose-pair.indexino.kts")
        script.writeText(COMPOSE_PAIR_SCRIPT.trimIndent() + "\n")
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        try {
            Indexino.connectBlocking(
                    IndexinoConfiguration.forWorkspace(workspace)
                        .withRuntimeAttach(RuntimeAttachMode.IN_PROCESS)
                )
                .use { indexino ->
                    runBlocking {
                        indexino.refresh(RefreshRequest.forScope(IndexScope.gradle(":app"))).await()
                    }
                }
            IndexinoScriptHost.connectForTests = { path ->
                Indexino.connectBlocking(
                    IndexinoConfiguration.forWorkspace(path)
                        .withRuntimeAttach(RuntimeAttachMode.IN_PROCESS)
                )
            }

            val scripted =
                IndexinoScriptHost.create()
                    .run(ScriptRequest.forFile(workspace, script))
                    .findings
                    .map { finding -> finding.message to finding.range }
            val compiled =
                Indexino.connectBlocking(
                        IndexinoConfiguration.forWorkspace(workspace)
                            .withRuntimeAttach(RuntimeAttachMode.IN_PROCESS)
                    )
                    .use { indexino ->
                        runBlocking {
                            indexino.snapshot(FreshnessPolicy.PUBLISHED).use { snapshot ->
                                compiledContentSlotCheck(snapshot)
                            }
                        }
                    }

            assertEquals(compiled, scripted)
        } finally {
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    private suspend fun compiledContentSlotCheck(
        snapshot: dev.sebastiano.indexino.api.IndexSnapshot
    ): List<Pair<String, dev.sebastiano.indexino.model.SourceRange?>> {
        val findings = mutableListOf<Pair<String, dev.sebastiano.indexino.model.SourceRange?>>()
        val outerCalls = snapshot.findCalls(CallQuery.to("ComposableA"), QueryOptions.page(500))
        for (outer in outerCalls.items) {
            val content =
                outer.arguments.firstOrNull { argument ->
                    argument.resolvedName == "content" ||
                        argument.kind == ArgumentKind.TRAILING_LAMBDA ||
                        argument.kind == ArgumentKind.LAMBDA
                } ?: continue
            val containsB =
                content.nestedCallIds
                    .mapNotNull { id ->
                        snapshot
                            .findCalls(CallQuery.byId(id), QueryOptions.page(1))
                            .items
                            .singleOrNull()
                    }
                    .any { call -> call.calleeName == "ComposableB" }
            if (containsB) {
                findings += "ComposableA content contains ComposableB" to outer.range
            }
        }
        return findings.sortedWith(
            compareBy(
                { it.second?.start?.file?.displayPath.orEmpty() },
                { it.second?.start?.offset ?: -1 },
                { it.first },
            )
        )
    }

    private fun createComposePairWorkspace(): Path {
        val workspace = createTempDirectory("indexino-script-parity-workspace-")
        temporaryDirectories.add(workspace)
        workspace.resolve("settings.gradle.kts").writeText("rootProject.name = \"parity\"\n")
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
