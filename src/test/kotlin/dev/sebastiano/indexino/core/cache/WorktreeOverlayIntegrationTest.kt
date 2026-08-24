package dev.sebastiano.indexino.core.cache

import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.cli.CacheMaintenance
import dev.sebastiano.indexino.core.BASIC_FACT_SCHEMA_VERSION
import dev.sebastiano.indexino.core.store.WorktreeOverlayIndexStore
import dev.sebastiano.indexino.model.CallQuery
import dev.sebastiano.indexino.model.CheckRequest
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.ReferenceQuery
import dev.sebastiano.indexino.model.ResourceQuery
import dev.sebastiano.indexino.model.SymbolQuery
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.SAME_THREAD)
class WorktreeOverlayIntegrationTest {
    init {
        Indexino.defaultRuntimeAttachModeForTests =
            dev.sebastiano.indexino.api.RuntimeAttachMode.IN_PROCESS
    }

    private val tempDirs = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun `compatible sibling worktree with no changes runs zero analyzers and bounded metadata`() {
        val cacheDirectory = createTempDirectory("indexino-overlay-cache-")
        tempDirs.add(cacheDirectory)
        val (mainWorkspace, forkWorkspace) = createLinkedWorktrees()
        val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
        withCache(cacheDirectory) {
            indexMainAndFork(mainWorkspace, forkWorkspace, request)

            val mainWorkspaceId = InProcessCacheLayout.workspaceId(mainWorkspace)
            val forkWorkspaceId = InProcessCacheLayout.workspaceId(forkWorkspace)
            assertFalse(mainWorkspaceId == forkWorkspaceId)

            val forkManifest =
                checkNotNull(
                    WorkspaceGenerationManifestStore(
                            canonicalCacheRoot(cacheDirectory),
                            forkWorkspaceId,
                        )
                        .current()
                )
            assertEquals(mainWorkspaceId, forkManifest.baseWorkspaceId)
            assertTrue(forkManifest.overlayPackKeys.isEmpty())

            val cacheRoot = canonicalCacheRoot(cacheDirectory)
            val forkBytes = workspaceTreeBytes(cacheRoot, forkWorkspaceId)
            val mainBytes = workspaceTreeBytes(cacheRoot, mainWorkspaceId)
            assertTrue(forkBytes <= WorktreeOverlayPolicy.METADATA_BUDGET_BYTES)
            assertTrue(forkBytes < mainBytes / 4)
        }
    }

    @Test
    fun `small fork delta stores and analyzes only changed files plus closure`() {
        val cacheDirectory = createTempDirectory("indexino-overlay-delta-cache-")
        tempDirs.add(cacheDirectory)
        val (mainWorkspace, forkWorkspace) = createLinkedWorktrees()
        val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
        val forkSource = forkWorkspace.resolve("ui/src/main/kotlin/Panel.kt")
        withCache(cacheDirectory) {
            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking { main.refresh(request).await() }
            }

            Files.writeString(
                forkSource,
                Files.readString(forkSource).replace("Panel", "ForkedPanel"),
            )

            val fork = Indexino.connectBlocking(forkWorkspace)
            try {
                val result = runBlocking { fork.refresh(request).await() }
                assertTrue(result.changes.changedFileCount >= 1)
                assertTrue(result.changes.changedFileCount <= 5)
            } finally {
                fork.close()
            }

            val forkManifest =
                checkNotNull(
                    WorkspaceGenerationManifestStore(
                            canonicalCacheRoot(cacheDirectory),
                            InProcessCacheLayout.workspaceId(forkWorkspace),
                        )
                        .current()
                )
            assertTrue(forkManifest.overlayPackKeys.isNotEmpty())
            assertTrue(!forkManifest.baseGeneration.isNullOrBlank())
        }
    }

    @Test
    fun `base plus overlay queries match a clean materialized rebuild`() {
        val cacheDirectory = createTempDirectory("indexino-overlay-equivalence-cache-")
        tempDirs.add(cacheDirectory)
        val (mainWorkspace, forkWorkspace) = createLinkedWorktrees()
        val rebuildWorkspace = createGitWorkspaceCopy(mainWorkspace)
        val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
        val forkSource = forkWorkspace.resolve("ui/src/main/kotlin/Panel.kt")
        Files.writeString(
            forkSource,
            Files.readString(forkSource).replace("ActionButton", "ForkActionButton"),
        )
        Files.writeString(
            rebuildWorkspace.resolve("ui/src/main/kotlin/Panel.kt"),
            Files.readString(forkSource),
        )

        lateinit var overlaySymbols: List<String>
        lateinit var rebuiltSymbols: List<String>
        withCache(cacheDirectory) {
            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking { main.refresh(request).await() }
            }
            Indexino.connectBlocking(forkWorkspace).use { fork ->
                runBlocking { fork.refresh(request).await() }
                overlaySymbols = querySymbolNames(fork)
            }
        }

        val rebuildCache = createTempDirectory("indexino-overlay-rebuild-cache-")
        tempDirs.add(rebuildCache)
        withCache(rebuildCache) {
            Indexino.connectBlocking(rebuildWorkspace).use { rebuilt ->
                runBlocking { rebuilt.refresh(request).await() }
                rebuiltSymbols = querySymbolNames(rebuilt)
            }
        }

        assertEquals(rebuiltSymbols.sorted(), overlaySymbols.sorted())
    }

    @Test
    fun `fork deleting a file tombstones base symbols from overlay queries`() {
        val cacheDirectory = createTempDirectory("indexino-overlay-delete-cache-")
        tempDirs.add(cacheDirectory)
        val (mainWorkspace, forkWorkspace) = createLinkedWorktrees()
        val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
        val forkSource = forkWorkspace.resolve("ui/src/main/kotlin/Panel.kt")
        withCache(cacheDirectory) {
            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking { main.refresh(request).await() }
            }
            Files.delete(forkSource)
            Indexino.connectBlocking(forkWorkspace).use { fork ->
                runBlocking { fork.refresh(request).await() }
                runBlocking {
                    fork.snapshot().use { snapshot ->
                        assertTrue(
                            snapshot
                                .findSymbols(
                                    SymbolQuery.named("Panel"),
                                    QueryOptions.page(limit = 10),
                                )
                                .items
                                .isEmpty()
                        )
                        assertTrue(
                            snapshot
                                .findSymbols(
                                    SymbolQuery.named("ActionButton"),
                                    QueryOptions.page(limit = 10),
                                )
                                .items
                                .isEmpty()
                        )
                    }
                }
            }

            val forkManifest =
                checkNotNull(
                    WorkspaceGenerationManifestStore(
                            canonicalCacheRoot(cacheDirectory),
                            InProcessCacheLayout.workspaceId(forkWorkspace),
                        )
                        .current()
                )
            assertTrue(
                forkManifest.tombstonePrefixes.contains(
                    WorktreeOverlayIndexStore.tombstonePrefixForRelativeFile(
                        "ui/src/main/kotlin/Panel.kt"
                    )
                )
            )
        }
    }

    @Test
    fun `fork falls back to materialized generation when base chain is at max depth`() {
        val cacheDirectory = createTempDirectory("indexino-overlay-depth-cache-")
        tempDirs.add(cacheDirectory)
        val (mainWorkspace, forkWorkspace) = createLinkedWorktrees()
        val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
        val forkSource = forkWorkspace.resolve("ui/src/main/kotlin/Panel.kt")
        withCache(cacheDirectory) {
            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking { main.refresh(request).await() }
            }
            val cacheRoot = canonicalCacheRoot(cacheDirectory)
            val mainWorkspaceId = InProcessCacheLayout.workspaceId(mainWorkspace)
            val mainStore = WorkspaceGenerationManifestStore(cacheRoot, mainWorkspaceId)
            val mainManifest = checkNotNull(mainStore.current())
            mainStore.publish(
                mainManifest.copy(
                    representation = WorktreeOverlayPolicy.REPRESENTATION_OVERLAY,
                    overlayChainDepth = WorktreeOverlayPolicy.MAX_CHAIN_DEPTH,
                )
            )

            Files.writeString(
                forkSource,
                Files.readString(forkSource).replace("ActionButton", "DepthFallbackButton"),
            )
            Indexino.connectBlocking(forkWorkspace).use { fork ->
                runBlocking { fork.refresh(request).await() }
            }

            val forkManifest =
                checkNotNull(
                    WorkspaceGenerationManifestStore(
                            cacheRoot,
                            InProcessCacheLayout.workspaceId(forkWorkspace),
                        )
                        .current()
                )
            assertEquals(
                WorktreeOverlayPolicy.REPRESENTATION_MATERIALIZED,
                forkManifest.representation,
            )
            assertNull(forkManifest.baseWorkspaceId)
            assertTrue(forkManifest.packKeys.isNotEmpty())
        }
    }

    @Test
    fun `forget fork workspace retains base packs referenced by main`() {
        val cacheDirectory = createTempDirectory("indexino-overlay-gc-cache-")
        tempDirs.add(cacheDirectory)
        val (mainWorkspace, forkWorkspace) = createLinkedWorktrees()
        val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
        val forkSource = forkWorkspace.resolve("ui/src/main/kotlin/Panel.kt")
        withCache(cacheDirectory) {
            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking { main.refresh(request).await() }
            }
            Files.writeString(
                forkSource,
                Files.readString(forkSource).replace("ActionButton", "GcForkButton"),
            )
            Indexino.connectBlocking(forkWorkspace).use { fork ->
                runBlocking { fork.refresh(request).await() }
            }

            val cacheRoot = canonicalCacheRoot(cacheDirectory)
            val mainPackKey =
                checkNotNull(
                        WorkspaceGenerationManifestStore(
                                cacheRoot,
                                InProcessCacheLayout.workspaceId(mainWorkspace),
                            )
                            .current()
                    )
                    .packKeys
                    .single()
            val forkManifest =
                checkNotNull(
                    WorkspaceGenerationManifestStore(
                            cacheRoot,
                            InProcessCacheLayout.workspaceId(forkWorkspace),
                        )
                        .current()
                )
            val forkOnlyPackKeys = forkManifest.overlayPackKeys.filter { it != mainPackKey }.toSet()
            assertTrue(forkOnlyPackKeys.isNotEmpty())

            CacheMaintenance.forget(cacheRoot, forkWorkspace)
            CacheMaintenance.gc(cacheRoot)

            assertTrue(Files.exists(packPath(cacheRoot, mainPackKey)))
            forkOnlyPackKeys.forEach { packKey ->
                assertFalse(Files.exists(packPath(cacheRoot, packKey)))
            }
        }
    }

    @Test
    fun `base plus overlay reference and call queries match a clean materialized rebuild`() {
        val cacheDirectory = createTempDirectory("indexino-overlay-ref-call-cache-")
        tempDirs.add(cacheDirectory)
        val (mainWorkspace, forkWorkspace) = createLinkedWorktrees()
        val rebuildWorkspace = createGitWorkspaceCopy(mainWorkspace)
        val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
        val forkSource = forkWorkspace.resolve("ui/src/main/kotlin/Panel.kt")
        Files.writeString(
            forkSource,
            Files.readString(forkSource).replace("ActionButton", "ForkActionButton"),
        )
        Files.writeString(
            rebuildWorkspace.resolve("ui/src/main/kotlin/Panel.kt"),
            Files.readString(forkSource),
        )

        lateinit var overlayReferenceFiles: List<String>
        lateinit var rebuiltReferenceFiles: List<String>
        lateinit var overlayCallNames: List<String>
        lateinit var rebuiltCallNames: List<String>
        withCache(cacheDirectory) {
            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking { main.refresh(request).await() }
            }
            Indexino.connectBlocking(forkWorkspace).use { fork ->
                runBlocking { fork.refresh(request).await() }
                runBlocking {
                    fork.snapshot().use { snapshot ->
                        val actionButton =
                            snapshot
                                .findSymbols(
                                    SymbolQuery.named("ForkActionButton"),
                                    QueryOptions.page(limit = 1),
                                )
                                .items
                                .single()
                        overlayReferenceFiles =
                            snapshot
                                .findReferences(
                                    ReferenceQuery.to(actionButton.id),
                                    QueryOptions.page(limit = 10),
                                )
                                .items
                                .map { it.location.file.path }
                        overlayCallNames =
                            snapshot
                                .findCalls(CallQuery.to("Panel"), QueryOptions.page(limit = 10))
                                .items
                                .map { it.calleeName }
                    }
                }
            }
        }

        val rebuildCache = createTempDirectory("indexino-overlay-ref-call-rebuild-")
        tempDirs.add(rebuildCache)
        withCache(rebuildCache) {
            Indexino.connectBlocking(rebuildWorkspace).use { rebuilt ->
                runBlocking { rebuilt.refresh(request).await() }
                runBlocking {
                    rebuilt.snapshot().use { snapshot ->
                        val actionButton =
                            snapshot
                                .findSymbols(
                                    SymbolQuery.named("ForkActionButton"),
                                    QueryOptions.page(limit = 1),
                                )
                                .items
                                .single()
                        rebuiltReferenceFiles =
                            snapshot
                                .findReferences(
                                    ReferenceQuery.to(actionButton.id),
                                    QueryOptions.page(limit = 10),
                                )
                                .items
                                .map { it.location.file.path }
                        rebuiltCallNames =
                            snapshot
                                .findCalls(CallQuery.to("Panel"), QueryOptions.page(limit = 10))
                                .items
                                .map { it.calleeName }
                    }
                }
            }
        }

        assertEquals(rebuiltReferenceFiles.sorted(), overlayReferenceFiles.sorted())
        assertEquals(rebuiltCallNames.sorted(), overlayCallNames.sorted())
    }

    private fun packPath(cacheRoot: java.nio.file.Path, key: String): java.nio.file.Path =
        cacheRoot.resolve("chunks").resolve(key.take(2)).resolve(key.substring(2, 4)).resolve(key)

    @Test
    fun `base plus overlay resource queries match a clean materialized rebuild`() {
        val cacheDirectory = createTempDirectory("indexino-overlay-resource-cache-")
        tempDirs.add(cacheDirectory)
        val (mainWorkspace, forkWorkspace) = createLinkedWorktrees()
        val rebuildWorkspace = createGitWorkspaceCopy(mainWorkspace)
        val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
        val forkStrings = forkWorkspace.resolve("ui/src/main/res/values/strings.xml")
        val forkLayout = forkWorkspace.resolve("ui/src/main/res/layout/main.xml")
        Files.createDirectories(forkStrings.parent)
        Files.writeString(
            forkStrings,
            """
            <resources>
                <string name="overlay_title">fork overlay</string>
            </resources>
            """
                .trimIndent(),
        )
        Files.writeString(
            forkLayout,
            """
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent" />
            """
                .trimIndent(),
        )
        Files.createDirectories(rebuildWorkspace.resolve("ui/src/main/res/values"))
        Files.writeString(
            rebuildWorkspace.resolve("ui/src/main/res/values/strings.xml"),
            Files.readString(forkStrings),
        )
        Files.writeString(
            rebuildWorkspace.resolve("ui/src/main/res/layout/main.xml"),
            Files.readString(forkLayout),
        )

        lateinit var overlayResources: List<String>
        lateinit var rebuiltResources: List<String>
        withCache(cacheDirectory) {
            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking { main.refresh(request).await() }
            }
            Indexino.connectBlocking(forkWorkspace).use { fork ->
                runBlocking { fork.refresh(request).await() }
                overlayResources = queryResourceKeys(fork)
            }
        }

        val rebuildCache = createTempDirectory("indexino-overlay-resource-rebuild-")
        tempDirs.add(rebuildCache)
        withCache(rebuildCache) {
            Indexino.connectBlocking(rebuildWorkspace).use { rebuilt ->
                runBlocking { rebuilt.refresh(request).await() }
                rebuiltResources = queryResourceKeys(rebuilt)
            }
        }

        assertEquals(rebuiltResources, overlayResources)
    }

    @Test
    fun `base plus overlay plugin findings match a clean materialized rebuild`() {
        val cacheDirectory = createTempDirectory("indexino-overlay-plugin-cache-")
        tempDirs.add(cacheDirectory)
        val (mainWorkspace, forkWorkspace) = createLinkedWorktrees()
        val rebuildWorkspace = createGitWorkspaceCopy(mainWorkspace)
        val selectionPlugin = PluginId.of("dev.sebastiano.selection-context")
        val request = RefreshRequest.forScope(IndexScope.gradle(":ui")).withPlugin(selectionPlugin)
        val forkSource = forkWorkspace.resolve("ui/src/main/kotlin/Panel.kt")
        Files.writeString(
            forkSource,
            Files.readString(forkSource).replace("ActionButton", "OverlayActionButton"),
        )
        Files.writeString(
            rebuildWorkspace.resolve("ui/src/main/kotlin/Panel.kt"),
            Files.readString(forkSource),
        )

        lateinit var overlayFindings: List<String>
        lateinit var rebuiltFindings: List<String>
        withCache(cacheDirectory) {
            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking { main.refresh(request).await() }
            }
            Indexino.connectBlocking(forkWorkspace).use { fork ->
                runBlocking { fork.refresh(request).await() }
                overlayFindings = queryPluginFindingMessages(fork, selectionPlugin)
            }
        }

        val rebuildCache = createTempDirectory("indexino-overlay-plugin-rebuild-")
        tempDirs.add(rebuildCache)
        withCache(rebuildCache) {
            Indexino.connectBlocking(rebuildWorkspace).use { rebuilt ->
                runBlocking { rebuilt.refresh(request).await() }
                rebuiltFindings = queryPluginFindingMessages(rebuilt, selectionPlugin)
            }
        }

        assertEquals(rebuiltFindings.sorted(), overlayFindings.sorted())
    }

    @Test
    fun `main and fork refreshes publish independent overlay generations`() {
        val cacheDirectory = createTempDirectory("indexino-overlay-concurrent-cache-")
        tempDirs.add(cacheDirectory)
        val (mainWorkspace, forkWorkspace) = createLinkedWorktrees()
        val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
        withCache(cacheDirectory) {
            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking { main.refresh(request).await() }
            }

            Files.writeString(
                mainWorkspace.resolve("ui/src/main/kotlin/Panel.kt"),
                Files.readString(mainWorkspace.resolve("ui/src/main/kotlin/Panel.kt"))
                    .replace("Panel", "ConcurrentMainPanel"),
            )
            Files.writeString(
                forkWorkspace.resolve("ui/src/main/kotlin/Panel.kt"),
                Files.readString(forkWorkspace.resolve("ui/src/main/kotlin/Panel.kt"))
                    .replace("ActionButton", "ConcurrentForkButton"),
            )

            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking { main.refresh(request).await() }
            }
            Indexino.connectBlocking(forkWorkspace).use { fork ->
                runBlocking { fork.refresh(request).await() }
            }

            val cacheRoot = canonicalCacheRoot(cacheDirectory)
            val mainManifest =
                checkNotNull(
                    WorkspaceGenerationManifestStore(
                            cacheRoot,
                            InProcessCacheLayout.workspaceId(mainWorkspace),
                        )
                        .current()
                )
            val forkManifest =
                checkNotNull(
                    WorkspaceGenerationManifestStore(
                            cacheRoot,
                            InProcessCacheLayout.workspaceId(forkWorkspace),
                        )
                        .current()
                )
            assertNotEquals(mainManifest.generation, forkManifest.generation)
            assertEquals(WorktreeOverlayPolicy.REPRESENTATION_OVERLAY, forkManifest.representation)

            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking {
                    main.snapshot().use { snapshot ->
                        assertTrue(
                            snapshot
                                .findSymbols(
                                    SymbolQuery.named("ConcurrentMainPanel"),
                                    QueryOptions.page(limit = 1),
                                )
                                .items
                                .isNotEmpty()
                        )
                    }
                }
            }
            Indexino.connectBlocking(forkWorkspace).use { fork ->
                runBlocking {
                    fork.snapshot().use { snapshot ->
                        assertTrue(
                            snapshot
                                .findSymbols(
                                    SymbolQuery.named("ConcurrentForkButton"),
                                    QueryOptions.page(limit = 1),
                                )
                                .items
                                .isNotEmpty()
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `failed fork refresh keeps the previous overlay generation current`() {
        val cacheDirectory = createTempDirectory("indexino-overlay-lkg-cache-")
        tempDirs.add(cacheDirectory)
        val (mainWorkspace, forkWorkspace) = createLinkedWorktrees()
        val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
        val forkSource = forkWorkspace.resolve("ui/src/main/kotlin/Panel.kt")
        withCache(cacheDirectory) {
            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking { main.refresh(request).await() }
            }
            Files.writeString(
                forkSource,
                Files.readString(forkSource).replace("ActionButton", "LkgForkButton"),
            )
            Indexino.connectBlocking(forkWorkspace).use { fork ->
                runBlocking { fork.refresh(request).await() }
            }

            val cacheRoot = canonicalCacheRoot(cacheDirectory)
            val forkWorkspaceId = InProcessCacheLayout.workspaceId(forkWorkspace)
            val store = WorkspaceGenerationManifestStore(cacheRoot, forkWorkspaceId)
            val lkg = checkNotNull(store.current())
            val lkgSymbols =
                Indexino.connectBlocking(forkWorkspace).use { fork ->
                    runBlocking {
                        fork.snapshot().use { snapshot ->
                            snapshot
                                .findSymbols(
                                    SymbolQuery.named("LkgForkButton"),
                                    QueryOptions.page(limit = 1),
                                )
                                .items
                                .map { it.name }
                        }
                    }
                }

            val failedRefresh = runCatching {
                Indexino.connectBlocking(forkWorkspace).use { fork ->
                    runBlocking {
                        fork
                            .refresh(RefreshRequest.forScope(IndexScope.gradle(":missing-module")))
                            .await()
                    }
                }
            }
            assertTrue(failedRefresh.isFailure, failedRefresh.exceptionOrNull()?.message)
            assertEquals(lkg.generation, store.current()?.generation)

            Indexino.connectBlocking(forkWorkspace).use { fork ->
                runBlocking {
                    fork.snapshot().use { snapshot ->
                        assertEquals(
                            lkgSymbols,
                            snapshot
                                .findSymbols(
                                    SymbolQuery.named("LkgForkButton"),
                                    QueryOptions.page(limit = 1),
                                )
                                .items
                                .map { it.name },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `fork falls back to materialized generation when base schema version is stale`() {
        val cacheDirectory = createTempDirectory("indexino-overlay-schema-cache-")
        tempDirs.add(cacheDirectory)
        val (mainWorkspace, forkWorkspace) = createLinkedWorktrees()
        val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
        val forkSource = forkWorkspace.resolve("ui/src/main/kotlin/Panel.kt")
        withCache(cacheDirectory) {
            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking { main.refresh(request).await() }
            }
            val cacheRoot = canonicalCacheRoot(cacheDirectory)
            val mainWorkspaceId = InProcessCacheLayout.workspaceId(mainWorkspace)
            val mainStore = WorkspaceGenerationManifestStore(cacheRoot, mainWorkspaceId)
            val mainManifest = checkNotNull(mainStore.current())
            val staleSchemaVersion = BASIC_FACT_SCHEMA_VERSION - 1
            mainStore.publish(
                mainManifest.copy(
                    basicFactSchemaVersion = staleSchemaVersion,
                    compatibilityManifest =
                        checkNotNull(mainManifest.compatibilityManifest)
                            .copy(basicFactSchemaVersion = staleSchemaVersion),
                )
            )

            Files.writeString(
                forkSource,
                Files.readString(forkSource).replace("ActionButton", "SchemaFallbackButton"),
            )
            Indexino.connectBlocking(forkWorkspace).use { fork ->
                runBlocking { fork.refresh(request).await() }
            }

            val forkManifest =
                checkNotNull(
                    WorkspaceGenerationManifestStore(
                            cacheRoot,
                            InProcessCacheLayout.workspaceId(forkWorkspace),
                        )
                        .current()
                )
            assertEquals(
                WorktreeOverlayPolicy.REPRESENTATION_MATERIALIZED,
                forkManifest.representation,
            )
            assertNull(forkManifest.baseWorkspaceId)
            assertTrue(forkManifest.packKeys.isNotEmpty())
        }
    }

    @Test
    fun `fork rename tombstones the old path and indexes the new file in overlay`() {
        val cacheDirectory = createTempDirectory("indexino-overlay-rename-cache-")
        tempDirs.add(cacheDirectory)
        val (mainWorkspace, forkWorkspace) = createLinkedWorktrees()
        val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
        val oldPath = forkWorkspace.resolve("ui/src/main/kotlin/Panel.kt")
        val newPath = forkWorkspace.resolve("ui/src/main/kotlin/ForkPanel.kt")
        withCache(cacheDirectory) {
            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking { main.refresh(request).await() }
            }
            Files.move(oldPath, newPath)
            Files.writeString(
                newPath,
                Files.readString(newPath).replace("Panel", "RenamedForkPanel"),
            )
            Indexino.connectBlocking(forkWorkspace).use { fork ->
                runBlocking { fork.refresh(request).await() }
                runBlocking {
                    fork.snapshot().use { snapshot ->
                        assertTrue(
                            snapshot
                                .findSymbols(
                                    SymbolQuery.named("RenamedForkPanel"),
                                    QueryOptions.page(limit = 1),
                                )
                                .items
                                .isNotEmpty()
                        )
                        assertTrue(
                            snapshot
                                .findSymbols(
                                    SymbolQuery.named("Panel"),
                                    QueryOptions.page(limit = 10),
                                )
                                .items
                                .none { it.location.file.path.endsWith("Panel.kt") }
                        )
                    }
                }
            }

            val forkManifest =
                checkNotNull(
                    WorkspaceGenerationManifestStore(
                            canonicalCacheRoot(cacheDirectory),
                            InProcessCacheLayout.workspaceId(forkWorkspace),
                        )
                        .current()
                )
            assertTrue(
                forkManifest.tombstonePrefixes.contains(
                    WorktreeOverlayIndexStore.tombstonePrefixForRelativeFile(
                        "ui/src/main/kotlin/Panel.kt"
                    )
                )
            )
        }
    }

    @Test
    fun `forgetting a fork overlay workspace leaves the main base generation queryable`() {
        val cacheDirectory = createTempDirectory("indexino-overlay-forget-main-cache-")
        tempDirs.add(cacheDirectory)
        val (mainWorkspace, forkWorkspace) = createLinkedWorktrees()
        val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
        withCache(cacheDirectory) {
            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking { main.refresh(request).await() }
            }
            Indexino.connectBlocking(forkWorkspace).use { fork ->
                runBlocking { fork.refresh(request).await() }
            }

            val cacheRoot = canonicalCacheRoot(cacheDirectory)
            CacheMaintenance.forget(cacheRoot, forkWorkspace)

            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking {
                    main.snapshot().use { snapshot ->
                        assertTrue(
                            snapshot
                                .findSymbols(
                                    SymbolQuery.named("Panel"),
                                    QueryOptions.page(limit = 1),
                                )
                                .items
                                .isNotEmpty()
                        )
                    }
                }
            }
            assertFalse(
                Files.isDirectory(
                    cacheRoot
                        .resolve("workspaces")
                        .resolve(InProcessCacheLayout.workspaceId(forkWorkspace))
                )
            )
            assertTrue(
                Files.isDirectory(
                    cacheRoot
                        .resolve("workspaces")
                        .resolve(InProcessCacheLayout.workspaceId(mainWorkspace))
                )
            )
        }
    }

    private fun queryResourceKeys(indexino: Indexino): List<String> = runBlocking {
        indexino.snapshot().use { snapshot ->
            snapshot
                .findResources(ResourceQuery.all(), QueryOptions.page(limit = 100))
                .items
                .map { "${it.id.type}:${it.id.name}:${it.id.packageName}" }
                .sorted()
        }
    }

    private fun queryPluginFindingMessages(indexino: Indexino, plugin: PluginId): List<String> =
        runBlocking {
            indexino.snapshot().use { snapshot ->
                snapshot
                    .runCheck(
                        CheckRequest.of(plugin, "interactive-in-selection"),
                        QueryOptions.page(limit = 10),
                    )
                    .items
                    .map { it.message }
            }
        }

    private fun indexMainAndFork(
        mainWorkspace: java.nio.file.Path,
        forkWorkspace: java.nio.file.Path,
        request: RefreshRequest,
    ) {
        Indexino.connectBlocking(mainWorkspace).use { main ->
            runBlocking { main.refresh(request).await() }
        }
        Indexino.connectBlocking(forkWorkspace).use { fork ->
            val result = runBlocking { fork.refresh(request).await() }
            assertEquals(0, result.changes.changedFileCount)
        }
    }

    private fun querySymbolNames(indexino: Indexino): List<String> = runBlocking {
        indexino.snapshot().use { snapshot ->
            listOf("Panel", "ActionButton", "ForkActionButton", "SelectionContainer").flatMap { name
                ->
                snapshot
                    .findSymbols(SymbolQuery.named(name), QueryOptions.page(limit = 10))
                    .items
                    .map { it.name }
            }
        }
    }

    private fun workspaceTreeBytes(cacheRoot: java.nio.file.Path, workspaceId: String): Long {
        val root = cacheRoot.resolve("workspaces").resolve(workspaceId)
        if (!Files.isDirectory(root)) return 0
        return Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile).mapToLong { Files.size(it) }.sum()
        }
    }

    private fun canonicalCacheRoot(cacheDirectory: java.nio.file.Path): java.nio.file.Path =
        cacheDirectory.toRealPath()

    private fun withCache(cacheDirectory: java.nio.file.Path, block: () -> Unit) {
        val previousCacheDirectory = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", canonicalCacheRoot(cacheDirectory).toString())
        try {
            block()
        } finally {
            if (previousCacheDirectory == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheDirectory)
        }
    }

    private fun createLinkedWorktrees(): Pair<java.nio.file.Path, java.nio.file.Path> {
        val mainWorkspace = createGitWorkspace()
        val forkWorkspace = createTempDirectory("indexino-worktree-fork-")
        tempDirs.add(forkWorkspace)
        runGit(
            mainWorkspace,
            "worktree",
            "add",
            "-b",
            "overlay-fork-${System.nanoTime()}",
            forkWorkspace.toString(),
        )
        return mainWorkspace to forkWorkspace
    }

    private fun createGitWorkspaceCopy(source: java.nio.file.Path): java.nio.file.Path {
        val workspace = createTempDirectory("indexino-overlay-rebuild-")
        tempDirs.add(workspace)
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val relative = source.relativize(path)
                if (relative.startsWith(".git")) return@forEach
                val destination = workspace.resolve(relative)
                if (Files.isDirectory(path)) Files.createDirectories(destination)
                else {
                    Files.createDirectories(destination.parent)
                    Files.copy(path, destination)
                }
            }
        }
        runGit(workspace, "init")
        runGit(workspace, "config", "user.email", "test@example.com")
        runGit(workspace, "config", "user.name", "Test User")
        runGit(workspace, "add", ".")
        runGit(workspace, "commit", "-m", "rebuild fixture")
        return workspace
    }

    private fun createGitWorkspace(): java.nio.file.Path {
        val workspace = createTempDirectory("indexino-overlay-main-")
        tempDirs.add(workspace)
        val fixtureRoot = Path("src/test/resources/gradle-fixtures/multi-module")
        Files.walk(fixtureRoot).use { paths ->
            paths.forEach { path ->
                val destination = workspace.resolve(fixtureRoot.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(destination)
                else {
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

    private fun runGit(workspace: java.nio.file.Path, vararg args: String) {
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
