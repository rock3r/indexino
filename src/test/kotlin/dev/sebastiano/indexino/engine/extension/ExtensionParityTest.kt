package dev.sebastiano.indexino.engine.extension

import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.IndexinoConfiguration
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.RuntimeAttachMode
import dev.sebastiano.indexino.engine.PluginRegistry
import dev.sebastiano.indexino.model.CheckRequest
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.model.QueryOptions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/** Parity between in-process and out-of-process selection-context checks. */
class ExtensionParityTest {
    private val tempDirs = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        DynamicPluginCatalog.clearForTests()
        ExtensionHostRegistry.clearForTests()
        System.clearProperty("indexino.closedWorld")
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `out of process extension check matches in process selection context findings`() {
        val workspace = createGitWorkspace()
        val cacheDirectory = Path.of("/tmp/indexino-ext-${System.nanoTime()}")
        tempDirs.add(cacheDirectory)
        val previousCache = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheDirectory.toString())
        val selectionPlugin = PluginId.of("dev.sebastiano.selection-context")
        val request = CheckRequest.of(selectionPlugin, "interactive-in-selection")
        val pluginJar = selectionContextJar()
        val descriptor =
            checkNotNull(
                PluginRegistry.load(ExtensionParityTest::class.java.classLoader)
                    .descriptor(selectionPlugin)
            )
        DynamicPluginCatalog.register(selectionPlugin, pluginJar, descriptor)
        configureWorkerLaunch()
        try {
            Indexino.connectBlocking(
                    IndexinoConfiguration.forWorkspace(workspace)
                        .withRuntimeAttach(RuntimeAttachMode.IN_PROCESS)
                )
                .use { indexino ->
                    runBlocking {
                        indexino
                            .refresh(
                                RefreshRequest.forScope(IndexScope.gradle(":ui"))
                                    .withPlugin(selectionPlugin)
                            )
                            .await()
                    }
                    val inProcess = runBlocking {
                        indexino.snapshot().use { snapshot ->
                            snapshot.runCheck(request, QueryOptions.page(limit = 100)).items
                        }
                    }
                    System.setProperty("indexino.closedWorld", "true")
                    val outOfProcess = runBlocking {
                        indexino.snapshot().use { snapshot ->
                            snapshot.runCheck(request, QueryOptions.page(limit = 100)).items
                        }
                    }
                    val inProcessKeys =
                        inProcess
                            .map { finding -> finding.message to finding.range?.start?.file?.path }
                            .sortedWith(compareBy({ it.first }, { it.second }))
                    val outOfProcessKeys =
                        outOfProcess
                            .map { finding -> finding.message to finding.range?.start?.file?.path }
                            .sortedWith(compareBy({ it.first }, { it.second }))
                    assertEquals(inProcessKeys, outOfProcessKeys)
                }
        } finally {
            if (previousCache == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCache)
        }
    }

    private fun configureWorkerLaunch() {
        ExtensionJvmLauncher.workerClasspath = System.getProperty("java.class.path")
        ExtensionJvmLauncher.javaExecutable =
            ProcessHandle.current().info().command().orElseThrow {
                IllegalStateException("Current Java executable is unavailable")
            }
    }

    private fun selectionContextJar(): Path {
        val libs =
            Path.of("indexino-selection-context/build/libs").also { dir ->
                require(dir.toFile().isDirectory) {
                    "Build indexino-selection-context first: ./gradlew :indexino-selection-context:jar"
                }
            }
        return libs.listDirectoryEntries("indexino-selection-context-*.jar").single {
            !it.fileName.toString().contains("-sources") &&
                !it.fileName.toString().contains("-javadoc")
        }
    }

    private fun createGitWorkspace(): Path {
        val fixtureRoot = Path.of("src/test/resources/gradle-fixtures/multi-module")
        val workspace = createTempDirectory("indexino-extension-parity-workspace-")
        tempDirs.add(workspace)
        Files.walk(fixtureRoot).use { paths ->
            paths.forEach { source ->
                val target = workspace.resolve(fixtureRoot.relativize(source))
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(source, target)
                }
            }
        }
        runGit(workspace, "init")
        runGit(workspace, "config", "user.email", "test@example.com")
        runGit(workspace, "config", "user.name", "Test User")
        runGit(workspace, "add", ".")
        runGit(workspace, "commit", "-m", "fixture")
        return workspace
    }

    private fun runGit(workspace: Path, vararg args: String) {
        ProcessBuilder(
                listOf("git", "-C", workspace.toString(), "-c", "commit.gpgsign=false") + args
            )
            .redirectErrorStream(true)
            .start()
            .waitFor()
    }
}
