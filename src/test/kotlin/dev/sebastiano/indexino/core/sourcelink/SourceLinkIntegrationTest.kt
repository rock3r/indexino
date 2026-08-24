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

    @Test
    fun `dirty checkout disables exact linking with loud diagnostics`() {
        val cacheDirectory = tempDirectory("indexino-source-link-dirty-cache-")
        val (providerWorkspace, consumerWorkspace) = createLinkedWorkspaces()
        writeSourceLinks(consumerWorkspace, verified = true)
        providerWorkspace
            .resolve("src/main/kotlin/com/example/lib/ProviderLib.kt")
            .toFile()
            .appendText("\n// dirty edit")
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
                        val result = page.items.first()
                        assertEquals(SourceLinkEvidence.MISMATCH, result.evidence)
                        assertTrue(result.evidence.isNavigationHintOnly())
                        assertTrue(result.diagnostics.any { it.code == "checkout.dirty" })
                        assertTrue(!result.evidence.allowsExactCrossRepositorySemantics())
                    }
                }
            }
        }
    }

    @Test
    fun `digest mismatch disables exact linking`() {
        val cacheDirectory = tempDirectory("indexino-source-link-digest-cache-")
        val (providerWorkspace, consumerWorkspace) = createLinkedWorkspaces()
        writeSourceLinks(
            consumerWorkspace,
            verified = true,
            publishedCompanion = "sha256:wrong-companion",
        )
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
                        val result = page.items.first()
                        assertEquals(SourceLinkEvidence.MISMATCH, result.evidence)
                        assertTrue(result.diagnostics.any { it.code == "digest.mismatch" })
                    }
                }
            }
        }
    }

    @Test
    fun `unresolved ref mismatch disables exact linking`() {
        val cacheDirectory = tempDirectory("indexino-source-link-ref-cache-")
        val (providerWorkspace, consumerWorkspace) = createLinkedWorkspaces()
        writeSourceLinks(consumerWorkspace, verified = false, ref = "v9.9.9")
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
                        val result = page.items.first()
                        assertEquals(SourceLinkEvidence.MISMATCH, result.evidence)
                        assertTrue(result.diagnostics.any { it.code == "ref.unresolved" })
                    }
                }
            }
        }
    }

    @Test
    fun `missing checkout surfaces source missing diagnostic`() {
        val cacheDirectory = tempDirectory("indexino-source-link-missing-cache-")
        val (providerWorkspace, consumerWorkspace) = createLinkedWorkspaces()
        writeSourceLinks(consumerWorkspace, verified = true, checkout = "missing-provider")
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
                        val result = page.items.first()
                        assertEquals(SourceLinkEvidence.MISMATCH, result.evidence)
                        assertTrue(result.diagnostics.any { it.code == "source.missing" })
                    }
                }
            }
        }
    }

    @Test
    fun `dependency substitution is recorded on linked results`() {
        val cacheDirectory = tempDirectory("indexino-source-link-substitution-cache-")
        val (providerWorkspace, consumerWorkspace) = createLinkedWorkspaces()
        writeSourceLinks(
            consumerWorkspace,
            verified = true,
            substitution = "com.example:lib:1.0.0 -> com.example:lib-local:1.0.0",
        )
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
                        assertEquals(
                            "com.example:lib:1.0.0 -> com.example:lib-local:1.0.0",
                            page.items.first().component.substitution,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `linked source generation change invalidates link generation`() {
        val cacheDirectory = tempDirectory("indexino-source-link-invalidation-cache-")
        val (providerWorkspace, consumerWorkspace) = createLinkedWorkspaces()
        writeSourceLinks(consumerWorkspace, verified = true)
        val request = RefreshRequest.forScope(IndexScope.gradle(":").includingDependencies())
        withCache(cacheDirectory) {
            Indexino.connectBlocking(providerWorkspace).use { provider ->
                runBlocking { provider.refresh(request).await() }
            }
            Indexino.connectBlocking(consumerWorkspace).use { consumer ->
                runBlocking { consumer.refresh(request).await() }
            }
            val firstLinkGeneration = readConsumerLinkGeneration(cacheDirectory, consumerWorkspace)
            assertNotNull(firstLinkGeneration)

            providerWorkspace
                .resolve("src/main/kotlin/com/example/lib/ProviderLib.kt")
                .toFile()
                .appendText("\nfun extraFeature() = Unit")
            runGit(providerWorkspace, "add", ".")
            runGit(providerWorkspace, "commit", "-m", "provider change")

            Indexino.connectBlocking(providerWorkspace).use { provider ->
                runBlocking { provider.refresh(request).await() }
            }
            Indexino.connectBlocking(consumerWorkspace).use { consumer ->
                runBlocking { consumer.refresh(request).await() }
            }
            val secondLinkGeneration = readConsumerLinkGeneration(cacheDirectory, consumerWorkspace)
            assertNotNull(secondLinkGeneration)
            assertTrue(firstLinkGeneration != secondLinkGeneration)
        }
    }

    @Test
    fun `chained jvm to native skiko skia link resolves through federation`() {
        val cacheDirectory = tempDirectory("indexino-source-link-skiko-cache-")
        val (skiaWorkspace, skikoWorkspace, consumerWorkspace) = createSkikoChainWorkspaces()
        val request = RefreshRequest.forScope(IndexScope.gradle(":").includingDependencies())
        withCache(cacheDirectory) {
            Indexino.connectBlocking(skiaWorkspace).use { skia ->
                runBlocking { skia.refresh(request).await() }
            }
            Indexino.connectBlocking(skikoWorkspace).use { skiko ->
                runBlocking { skiko.refresh(request).await() }
            }
            Indexino.connectBlocking(consumerWorkspace).use { consumer ->
                runBlocking { consumer.refresh(request).await() }
                runBlocking {
                    consumer.snapshot().use { snapshot ->
                        val page =
                            snapshot.findLinkedSources(
                                LinkedSourceQuery.forComponentSymbol(
                                    symbolName = "SkikoBridge",
                                    componentCoordinate =
                                        ResolvedComponentCoordinate.of(
                                            "org.jetbrains.skiko:skiko:0.8.0"
                                        ),
                                ),
                                QueryOptions.page(10),
                            )
                        assertTrue(page.items.isNotEmpty())
                        val result = page.items.first()
                        assertEquals(SourceLinkEvidence.VERIFIED, result.evidence)
                        assertTrue(result.symbolName.contains("SkikoBridge"))
                        assertEquals("jvm", result.component.variant)
                    }
                }
            }
            assertNotNull(readConsumerLinkGeneration(cacheDirectory, consumerWorkspace))
        }
    }

    private fun readConsumerLinkGeneration(cacheDirectory: Path, consumerWorkspace: Path): String? {
        val previous = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheDirectory.toString())
        return try {
            WorkspaceGenerationManifestStore(
                    InProcessCacheLayout.cacheRoot(),
                    InProcessCacheLayout.workspaceId(consumerWorkspace),
                )
                .current()
                ?.linkGeneration
        } finally {
            if (previous == null) {
                System.clearProperty("indexino.cache.dir")
            } else {
                System.setProperty("indexino.cache.dir", previous)
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
        ref: String = "v1.0.0",
        checkout: String = "provider",
        substitution: String? = null,
        publishedCompanion: String? = null,
    ) {
        val configDir = consumerWorkspace.resolve(".indexino")
        Files.createDirectories(configDir)
        val companionDigest =
            publishedCompanion
                ?: if (verified) {
                    "sha256:provider-lib-1.0.0"
                } else {
                    null
                }
        val companionLine = companionDigest?.let { "publishedSourceCompanion = \"$it\"" }.orEmpty()
        val declaredLine = if (declaredOnly) "declaredOnly = true" else ""
        val substitutionLine = substitution?.let { "substitution = \"$it\"" }.orEmpty()
        Files.writeString(
            configDir.resolve("source-links.toml"),
            """
            [[sourceLink]]
            component = "com.example:lib:1.0.0"
            binarySha256 = "sha256:provider-lib-1.0.0"
            checkout = "$checkout"
            linkedWorkspace = "provider"
            ref = "$ref"
            sourceRoots = ["src/main/kotlin"]
            $companionLine
            $declaredLine
            $substitutionLine
            """
                .trimIndent(),
        )
    }

    private fun createSkikoChainWorkspaces(): Triple<Path, Path, Path> {
        val root = tempDirectory("indexino-skiko-chain-workspaces-")
        val skia = root.resolve("skia")
        val skiko = root.resolve("skiko")
        val consumer = root.resolve("consumer")
        copyFixture("skiko-chain/skia", skia)
        copyFixture("skiko-chain/skiko", skiko)
        copyFixture("skiko-chain/consumer", consumer)
        Files.createSymbolicLink(skiko.resolve("skia"), skia)
        Files.createSymbolicLink(consumer.resolve("skiko"), skiko)
        writeSkikoSourceLinks(skiko)
        initializeGitRepository(skia)
        runGit(skia, "tag", "m116-0.1.0")
        initializeGitRepository(skiko)
        runGit(skiko, "tag", "v0.8.0")
        writeSkikoConsumerSourceLinks(consumer)
        initializeGitRepository(consumer)
        return Triple(skia, skiko, consumer)
    }

    private fun writeSkikoSourceLinks(skikoWorkspace: Path) {
        val configDir = skikoWorkspace.resolve(".indexino")
        Files.createDirectories(configDir)
        Files.writeString(
            configDir.resolve("source-links.toml"),
            """
            [[sourceLink]]
            component = "org.jetbrains.skia:skia:0.1.0"
            binarySha256 = "sha256:skia-native-0.1.0"
            checkout = "skia"
            linkedWorkspace = "skia"
            ref = "m116-0.1.0"
            variant = "native"
            sourceRoots = ["src/main/kotlin", "native/src"]
            publishedSourceCompanion = "sha256:skia-native-0.1.0"
            """
                .trimIndent(),
        )
    }

    private fun writeSkikoConsumerSourceLinks(consumerWorkspace: Path) {
        val configDir = consumerWorkspace.resolve(".indexino")
        Files.createDirectories(configDir)
        Files.writeString(
            configDir.resolve("source-links.toml"),
            """
            [[sourceLink]]
            component = "org.jetbrains.skiko:skiko:0.8.0"
            binarySha256 = "sha256:skiko-jvm-0.8.0"
            checkout = "skiko"
            linkedWorkspace = "skiko"
            ref = "v0.8.0"
            variant = "jvm"
            sourceRoots = ["src/main/kotlin"]
            publishedSourceCompanion = "sha256:skiko-jvm-0.8.0"
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
