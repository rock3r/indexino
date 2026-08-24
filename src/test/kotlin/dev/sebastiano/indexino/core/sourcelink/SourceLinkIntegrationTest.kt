package dev.sebastiano.indexino.core.sourcelink

import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifestStore
import dev.sebastiano.indexino.model.LinkedSourceQuery
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.ResolvedComponentCoordinate
import dev.sebastiano.indexino.model.SourceLinkEvidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.SAME_THREAD)
class SourceLinkIntegrationTest {
    init {
        Indexino.defaultRuntimeAttachModeForTests =
            dev.sebastiano.indexino.api.RuntimeAttachMode.IN_PROCESS
    }

    private val tempDirs = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun `verified linked source participates in exact cross repository results`() {
        val cacheDirectory = tempDirectory("indexino-source-link-cache-")
        val (providerWorkspace, consumerWorkspace) = createLinkedWorkspaces()
        writeSourceLinks(consumerWorkspace, verified = true)
        val request = RefreshRequest.forScope(IndexScope.gradle(":").includingDependencies())

        withCache(cacheDirectory) {
            Indexino.connectBlocking(providerWorkspace).use { provider ->
                runBlocking { provider.refresh(request).await() }
            }
            Indexino.connectBlocking(consumerWorkspace).use { consumer ->
                runBlocking { consumer.refresh(request).await() }
                runBlocking {
                    consumer.snapshot().use { snapshot ->
                        val page =
                            snapshot.findLinkedSources(
                                LinkedSourceQuery.forComponentSymbol(
                                    symbolName = "ProviderLib",
                                    componentCoordinate =
                                        ResolvedComponentCoordinate.of("com.example:lib:1.0.0"),
                                ),
                                QueryOptions.page(10),
                            )
                        assertTrue(page.items.isNotEmpty())
                        val result = page.items.first()
                        assertEquals(SourceLinkEvidence.VERIFIED, result.evidence)
                        assertTrue(result.symbolName.contains("ProviderLib"))
                    }
                }
            }

            val consumerManifest =
                checkNotNull(
                    WorkspaceGenerationManifestStore(
                            InProcessCacheLayout.cacheRoot(),
                            InProcessCacheLayout.workspaceId(consumerWorkspace),
                        )
                        .current()
                )
            assertNotNull(consumerManifest.linkGeneration)
        }
    }

    @Test
    fun `declared link remains navigation hint and mismatch disables exact linking`() {
        val cacheDirectory = tempDirectory("indexino-source-link-hints-cache-")
        val (providerWorkspace, consumerWorkspace) = createLinkedWorkspaces()
        writeSourceLinks(consumerWorkspace, verified = false, declaredOnly = true)
        val request = RefreshRequest.forScope(IndexScope.gradle(":").includingDependencies())
        withCache(cacheDirectory) {
            Indexino.connectBlocking(providerWorkspace).use { provider ->
                runBlocking { provider.refresh(request).await() }
            }
            Indexino.connectBlocking(consumerWorkspace).use { consumer ->
                runBlocking { consumer.refresh(request).await() }
                runBlocking {
                    consumer.snapshot().use { snapshot ->
                        val page =
                            snapshot.findLinkedSources(
                                LinkedSourceQuery.forSymbol("ProviderLib"),
                                QueryOptions.page(10),
                            )
                        assertTrue(page.items.isNotEmpty())
                        assertEquals(SourceLinkEvidence.DECLARED, page.items.first().evidence)
                        assertTrue(page.items.first().evidence.isNavigationHintOnly())
                    }
                }
            }
        }
    }

    private fun createLinkedWorkspaces(): Pair<Path, Path> {
        val root = tempDirectory("indexino-source-link-workspaces-")
        val provider = root.resolve("provider")
        val consumer = root.resolve("consumer")
        copyFixture("provider-lib", provider)
        copyFixture("consumer-app", consumer)
        Files.createSymbolicLink(consumer.resolve("provider"), provider)
        initializeGitRepository(provider)
        runGit(provider, "tag", "v1.0.0")
        initializeGitRepository(consumer)
        return provider to consumer
    }

    private fun writeSourceLinks(
        consumerWorkspace: Path,
        verified: Boolean,
        declaredOnly: Boolean = false,
    ) {
        val configDir = consumerWorkspace.resolve(".indexino")
        Files.createDirectories(configDir)
        val companionLine =
            if (verified) {
                "publishedSourceCompanion = \"sha256:provider-lib-1.0.0\""
            } else {
                ""
            }
        val declaredLine = if (declaredOnly) "declaredOnly = true" else ""
        Files.writeString(
            configDir.resolve("source-links.toml"),
            """
            [[sourceLink]]
            component = "com.example:lib:1.0.0"
            binarySha256 = "sha256:provider-lib-1.0.0"
            checkout = "provider"
            linkedWorkspace = "provider"
            ref = "v1.0.0"
            sourceRoots = ["src/main/kotlin"]
            $companionLine
            $declaredLine
            """
                .trimIndent(),
        )
    }

    private fun copyFixture(name: String, destination: Path) {
        val source =
            Path.of("src/test/resources/fixtures/source-link/$name").toAbsolutePath().normalize()
        Files.walk(source).forEach { path ->
            val relative = source.relativize(path)
            val target = destination.resolve(relative.toString())
            if (Files.isDirectory(path)) {
                Files.createDirectories(target)
            } else {
                Files.createDirectories(target.parent)
                Files.copy(path, target)
            }
        }
    }

    private fun withCache(cacheDirectory: Path, block: () -> Unit) {
        val previous = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheDirectory.toString())
        try {
            block()
        } finally {
            if (previous == null) {
                System.clearProperty("indexino.cache.dir")
            } else {
                System.setProperty("indexino.cache.dir", previous)
            }
        }
    }

    private fun tempDirectory(prefix: String): Path =
        createTempDirectory(prefix).also(tempDirs::add)

    private fun initializeGitRepository(directory: Path) {
        runGit(directory, "init")
        runGit(directory, "config", "user.email", "test@indexino.invalid")
        runGit(directory, "config", "user.name", "Indexino Test")
        runGit(directory, "add", ".")
        runGit(directory, "commit", "-m", "init")
    }

    private fun runGit(directory: Path, vararg args: String) {
        val process =
            ProcessBuilder(listOf("git", "-C", directory.toString(), *args))
                .redirectErrorStream(true)
                .start()
        check(process.waitFor() == 0) { process.inputStream.bufferedReader().readText() }
    }
}
