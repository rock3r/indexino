package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.SymbolQuery
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * End-to-end publication contract for the segment storage format (#136): published generations
 * install immutable MemorySegment packs instead of expanded Xodus `materialized/` environments, and
 * cold in-process reopens read them without reconstruction.
 */
class SegmentPublicationIntegrationTest {
    init {
        Indexino.defaultRuntimeAttachModeForTests = RuntimeAttachMode.IN_PROCESS
    }

    private val tempDirs = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        val prior = priorCacheProperty
        if (prior == null) System.clearProperty("indexino.cache.dir")
        else System.setProperty("indexino.cache.dir", prior)
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `publication installs segment packs without a materialized Xodus copy`() {
        val workspace = createGitWorkspace()
        val cache = withCache()
        Indexino.connectBlocking(workspace).use { index ->
            val result = runBlocking {
                index.refresh(RefreshRequest.forScope(IndexScope.gradle(":ui"))).await()
            }
            val generation = result.revision.let { currentGeneration(cache) }
            val generationRoot =
                cache
                    .resolve("workspaces")
                    .resolve(workspaceId(workspace))
                    .resolve("generations")
                    .resolve(generation)
            assertTrue(
                Files.isRegularFile(generationRoot.resolve("manifest.json")),
                "manifest missing",
            )
            val manifest = Files.readString(generationRoot.resolve("manifest.json"))
            assertTrue(
                manifest.contains("\"storageFormatVersion\":2"),
                "manifest is not segment format: $manifest",
            )
            assertFalse(
                Files.isDirectory(generationRoot.resolve("materialized")),
                "materialized Xodus environment still published",
            )
            val packKey =
                manifest.substringAfter("\"packKeys\":[").substringBefore(']').trim().trim('"')
            val pack = packFile(cache, packKey)
            assertTrue(Files.isRegularFile(pack), "pack missing for key $packKey")
            val magic = ByteArray(8)
            Files.newInputStream(pack).use { check(it.read(magic) == magic.size) }
            assertEquals("IDXSEGv1", magic.decodeToString(), "pack is not a segment file")
        }
    }

    @Test
    fun `cold in-process reopen queries the published segment generation`() {
        val workspace = createGitWorkspace()
        val cache = withCache()
        val generation =
            Indexino.connectBlocking(workspace).use { index ->
                val result = runBlocking {
                    index.refresh(RefreshRequest.forScope(IndexScope.gradle(":ui"))).await()
                }
                runBlocking {
                    index.snapshot().use { snapshot ->
                        assertEquals(1, symbolCount(snapshot, "Panel"))
                    }
                }
                result.revision.let { currentGeneration(cache) }
            }

        Indexino.connectBlocking(workspace).use { index ->
            runBlocking {
                index.snapshot().use { snapshot ->
                    assertEquals(generation, snapshot.generation.value)
                    assertEquals(1, symbolCount(snapshot, "Panel"))
                    // The Java fixture declares the class and its constructor as two symbols.
                    assertEquals(2, symbolCount(snapshot, "LegacyPanel"))
                }
            }
        }
    }

    @Test
    fun `overlay refresh publishes segment deltas without refs or expanded overlay directories`() {
        val workspace = createGitWorkspace()
        val cache = withCache()
        Indexino.connectBlocking(workspace).use { index ->
            runBlocking { index.refresh(RefreshRequest.forScope(IndexScope.gradle(":ui"))).await() }
        }
        val source = workspace.resolve("ui/src/main/kotlin/Panel.kt")
        Files.writeString(source, Files.readString(source) + "\nclass EditedPanel {}\n")

        Indexino.connectBlocking(workspace).use { index ->
            val result = runBlocking {
                index.refresh(RefreshRequest.forScope(IndexScope.gradle(":ui"))).await()
            }
            val generation = result.revision.let { currentGeneration(cache) }
            val workspaceRoot = cache.resolve("workspaces").resolve(workspaceId(workspace))
            val generationRoot = workspaceRoot.resolve("generations").resolve(generation)
            val manifest = Files.readString(generationRoot.resolve("manifest.json"))
            assertTrue(
                manifest.contains("\"representation\":\"overlay\""),
                "not an overlay: $manifest",
            )
            assertTrue(
                manifest.contains("\"storageFormatVersion\":2"),
                "overlay manifest is not segment format: $manifest",
            )
            assertFalse(
                Files.isDirectory(generationRoot.resolve("overlay-delta")),
                "expanded overlay-delta directory still published",
            )
            assertFalse(
                Files.isDirectory(workspaceRoot.resolve("refs")),
                "client refs tree still materialized",
            )
            runBlocking {
                index.snapshot().use { snapshot ->
                    assertEquals(1, symbolCount(snapshot, "EditedPanel"))
                    assertEquals(1, symbolCount(snapshot, "Panel"))
                }
            }
        }
    }

    @Test
    fun `legacy zip-pack manifests are not restorable and rebuild instead`() {
        val workspace = createGitWorkspace()
        val cache = withCache()
        Indexino.connectBlocking(workspace).use { index ->
            runBlocking { index.refresh(RefreshRequest.forScope(IndexScope.gradle(":ui"))).await() }
        }
        // Downgrade the current manifest to the legacy zip-pack storage format.
        val workspaceRoot = cache.resolve("workspaces").resolve(workspaceId(workspace))
        val generation = currentGeneration(cache)
        val manifestPath =
            workspaceRoot.resolve("generations").resolve(generation).resolve("manifest.json")
        Files.writeString(
            manifestPath,
            Files.readString(manifestPath)
                .replace("\"storageFormatVersion\":2", "\"storageFormatVersion\":1"),
        )

        Indexino.connectBlocking(workspace).use { index ->
            runBlocking { index.refresh(RefreshRequest.forScope(IndexScope.gradle(":ui"))).await() }
        }
        // The rebuild upgrades the manifest in place: the generation id is content-derived, but
        // the legacy zip-pack representation must never be served again.
        val rebuilt = currentGeneration(cache)
        val rebuiltManifest =
            Files.readString(
                workspaceRoot.resolve("generations").resolve(rebuilt).resolve("manifest.json")
            )
        assertTrue(
            rebuiltManifest.contains("\"storageFormatVersion\":2"),
            "rebuilt manifest is not segment format",
        )
        // A fresh client restores the upgraded generation and serves queries from it.
        Indexino.connectBlocking(workspace).use { index ->
            runBlocking {
                index.snapshot().use { snapshot ->
                    assertEquals(rebuilt, snapshot.generation.value)
                    assertEquals(1, symbolCount(snapshot, "Panel"))
                }
            }
        }
    }

    @Test
    fun `legacy overlay generation rebuilds with current facts on the next refresh`() {
        val workspace = createGitWorkspace()
        val cache = withCache()
        val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
        Indexino.connectBlocking(workspace).use { index ->
            runBlocking { index.refresh(request).await() }
        }
        // Publish an overlay generation carrying an edit, then simulate the workspace having
        // been produced by an older build: its current manifest is storage format 1.
        val source = workspace.resolve("ui/src/main/kotlin/Panel.kt")
        Files.writeString(source, Files.readString(source) + "\nclass LegacyOverlayEdit {}\n")
        Indexino.connectBlocking(workspace).use { index ->
            runBlocking { index.refresh(request).await() }
        }
        val workspaceRoot = cache.resolve("workspaces").resolve(workspaceId(workspace))
        val overlayGeneration = currentGeneration(cache)
        val manifestPath =
            workspaceRoot.resolve("generations").resolve(overlayGeneration).resolve("manifest.json")
        val overlayManifest = Files.readString(manifestPath)
        assertTrue(
            overlayManifest.contains("\"representation\":\"overlay\""),
            "expected an overlay: $overlayManifest",
        )
        Files.writeString(
            manifestPath,
            overlayManifest.replace("\"storageFormatVersion\":2", "\"storageFormatVersion\":1"),
        )

        // Sources are unchanged; the legacy generation must not be reused, forked from or
        // repointed at. The refresh rebuilds and publishes current-format facts.
        Indexino.connectBlocking(workspace).use { index ->
            runBlocking { index.refresh(request).await() }
            runBlocking {
                index.snapshot().use { snapshot ->
                    assertEquals(1, symbolCount(snapshot, "LegacyOverlayEdit"))
                    assertEquals(1, symbolCount(snapshot, "Panel"))
                }
            }
        }
        val rebuiltManifest =
            Files.readString(
                workspaceRoot
                    .resolve("generations")
                    .resolve(currentGeneration(cache))
                    .resolve("manifest.json")
            )
        assertTrue(
            rebuiltManifest.contains("\"storageFormatVersion\":2"),
            "rebuilt manifest is not segment format",
        )
    }

    @Test
    fun `link generation change repoints the overlay generation without republishing stale facts`() {
        val root = createTempDirectory("indexino-link-repoint-")
        tempDirs.add(root)
        val provider = root.resolve("provider")
        val consumer = root.resolve("consumer")
        copyFixture("provider-lib", provider)
        copyFixture("consumer-app", consumer)
        initGit(provider)
        runGit(provider, "tag", "v1.0.0")
        initGit(consumer)
        val cache = withCache()
        val request = RefreshRequest.forScope(IndexScope.gradle(":").includingDependencies())
        Indexino.connectBlocking(provider).use { index ->
            runBlocking { index.refresh(request).await() }
        }
        writeSourceLinks(consumer, ref = "v1.0.0")
        Indexino.connectBlocking(consumer).use { index ->
            runBlocking { index.refresh(request).await() }
        }
        val consumerRoot = cache.resolve("workspaces").resolve(workspaceId(consumer))
        val firstLink = currentLinkGeneration(consumerRoot)
        assertNotNull(firstLink, "link generation missing after first refresh")

        // Publish an overlay generation: edit a consumer source.
        val consumerSource = consumer.resolve("src/main/kotlin/com/example/app/ConsumerApp.kt")
        Files.writeString(
            consumerSource,
            Files.readString(consumerSource) + "\nclass LinkOverlayPanel {}\n",
        )
        Indexino.connectBlocking(consumer).use { index ->
            runBlocking { index.refresh(request).await() }
            runBlocking {
                index.snapshot().use { snapshot ->
                    assertEquals(1, symbolCount(snapshot, "LinkOverlayPanel"))
                }
            }
        }

        // Change only the link generation: the refresh must repoint the published generation
        // instead of republishing the pre-edit staging base.
        writeSourceLinks(consumer, ref = "v2.0.0")
        Indexino.connectBlocking(consumer).use { index ->
            runBlocking { index.refresh(request).await() }
            val secondLinkValue = currentLinkGeneration(consumerRoot)
            assertNotNull(secondLinkValue)
            assertTrue(firstLink != secondLinkValue, "link generation did not change")
            runBlocking {
                index.snapshot().use { snapshot ->
                    assertEquals(1, symbolCount(snapshot, "LinkOverlayPanel"))
                    assertEquals(secondLinkValue, snapshot.linkGeneration?.value)
                }
            }
        }
    }

    private fun currentLinkGeneration(workspaceRoot: Path): String? {
        val generation = Files.readString(workspaceRoot.resolve("current")).trim()
        val manifest =
            Files.readString(
                workspaceRoot.resolve("generations").resolve(generation).resolve("manifest.json")
            )
        return Regex("\"linkGeneration\":\"([^\"]+)\"").find(manifest)?.groupValues?.get(1)
    }

    private fun writeSourceLinks(consumer: Path, ref: String) {
        val configDir = consumer.resolve(".indexino")
        Files.createDirectories(configDir)
        Files.writeString(
            configDir.resolve("source-links.toml"),
            """
            [[sourceLink]]
            component = "com.example:lib:1.0.0"
            binarySha256 = "sha256:provider-lib-1.0.0"
            checkout = "../provider"
            linkedWorkspace = "../provider"
            ref = "$ref"
            sourceRoots = ["src/main/kotlin"]
            """
                .trimIndent(),
        )
    }

    private fun copyFixture(name: String, destination: Path) {
        val fixtureRoot = Path("src/test/resources/fixtures/source-link/$name")
        Files.walk(fixtureRoot).use { paths ->
            paths.forEach { path ->
                val target = destination.resolve(fixtureRoot.relativize(path))
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(path, target)
                }
            }
        }
    }

    private fun initGit(workspace: Path) {
        runGit(workspace, "init")
        runGit(workspace, "config", "user.email", "test@example.com")
        runGit(workspace, "config", "user.name", "Test User")
        runGit(workspace, "add", ".")
        runGit(workspace, "commit", "-m", "fixture")
    }

    private fun withCache(): Path {
        val cache = createTempDirectory("indexino-segment-cache-")
        tempDirs.add(cache)
        priorCacheProperty = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cache.toString())
        return cache
    }

    private var priorCacheProperty: String? = null

    private fun currentGeneration(cache: Path): String {
        val workspaceRoot = latestWorkspaceRoot(cache)
        return Files.readString(workspaceRoot.resolve("current")).trim()
    }

    private fun latestWorkspaceRoot(cache: Path): Path {
        val workspaces = cache.resolve("workspaces")
        val roots =
            Files.list(workspaces).use { stream -> stream.filter(Files::isDirectory).toList() }
        check(roots.size == 1) { "Expected exactly one workspace, found ${roots.size}" }
        return roots.single()
    }

    private fun workspaceId(workspace: Path): String = InProcessCacheLayout.workspaceId(workspace)

    private fun packFile(cache: Path, packKey: String): Path =
        cache
            .resolve("chunks")
            .resolve(packKey.take(2))
            .resolve(packKey.substring(2, 4))
            .resolve(packKey)

    private fun symbolCount(snapshot: IndexSnapshot, name: String): Int = runBlocking {
        snapshot.findSymbols(SymbolQuery.named(name), QueryOptions.page(limit = 10)).items.size
    }

    private fun createGitWorkspace(): Path {
        val fixtureRoot = Path("src/test/resources/gradle-fixtures/multi-module")
        val workspace = createTempDirectory("indexino-segment-")
        tempDirs.add(workspace)
        Files.walk(fixtureRoot).use { paths ->
            paths.forEach { path ->
                val destination = workspace.resolve(fixtureRoot.relativize(path))
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(path, destination)
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
        val process =
            ProcessBuilder(
                    *listOf("git", "-C", workspace.toString(), "-c", "commit.gpgsign=false", *args)
                        .toTypedArray()
                )
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }
}
