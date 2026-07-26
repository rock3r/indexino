package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.ReferenceQuery
import dev.sebastiano.indexino.model.SourceFile
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.SymbolQuery
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InProcessIndexinoTest {
    private val tempDirs = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun `manual refresh exposes paged symbols and references through a stable snapshot`() {
        val workspace = createGitWorkspace()
        val cacheDirectory = createTempDirectory("indexino-facade-cache-")
        tempDirs.add(cacheDirectory)
        val previousCacheDirectory = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheDirectory.toString())
        val indexino = Indexino.connectBlocking(workspace)
        try {
            val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
            val handle = runSuspend { indexino.refresh(request) }
            val result = runSuspend { handle.await() }

            assertEquals(handle.id, result.refreshId)
            assertEquals(RefreshOutcome.UPDATED, result.outcome)
            assertTrue(result.changes.changedFileCount > 0)

            val snapshot = runSuspend { indexino.snapshot() }
            try {
                assertEquals(SnapshotFreshness.UNKNOWN, snapshot.freshnessAtAcquisition)
                assertSnapshotQueries(snapshot, result, workspace)
            } finally {
                snapshot.close()
            }

            val unknownPluginFailure =
                assertFailsWith<IndexinoException> {
                    runSuspend {
                        indexino.refresh(request.withPlugin(PluginId.of("dev.example.unknown")))
                    }
                }
            assertEquals("INVALID_REQUEST", unknownPluginFailure.failure.category.value)
        } finally {
            indexino.close()
            if (previousCacheDirectory == null) {
                System.clearProperty("indexino.cache.dir")
            } else {
                System.setProperty("indexino.cache.dir", previousCacheDirectory)
            }
        }
    }

    @Test
    fun `connecting to a missing workspace reports invalid request`() {
        val missingWorkspace = createTempDirectory("missing-indexino-workspace-")
        missingWorkspace.toFile().deleteRecursively()

        val failure =
            assertFailsWith<IndexinoException> { Indexino.connectBlocking(missingWorkspace) }

        assertEquals("INVALID_REQUEST", failure.failure.category.value)
    }

    @Test
    fun `refresh maps topology resolution failures to IndexinoException`() {
        val workspace = createTempDirectory("indexino-no-topology-")
        tempDirs.add(workspace)
        // Minimal git metadata so connect succeeds; no Gradle/Bazel project files.
        Files.writeString(workspace.resolve("README"), "x")
        runGit(workspace, "init")
        runGit(workspace, "config", "user.email", "test@example.com")
        runGit(workspace, "config", "user.name", "Test")
        runGit(workspace, "add", "README")
        runGit(workspace, "commit", "-m", "init")

        val cacheDirectory = createTempDirectory("indexino-topology-map-cache-")
        tempDirs.add(cacheDirectory)
        val previousCacheDirectory = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheDirectory.toString())
        try {
            val indexino = Indexino.connectBlocking(workspace)
            try {
                val failure =
                    assertFailsWith<IndexinoException> {
                        runSuspend {
                            indexino
                                .refresh(RefreshRequest.forScope(IndexScope.gradle(":ui")))
                                .await()
                        }
                    }
                assertEquals("INTERNAL", failure.failure.category.value)
                assertEquals("internal", failure.failure.code)
                assertTrue(failure.cause != null)
            } finally {
                indexino.close()
            }
        } finally {
            if (previousCacheDirectory == null) {
                System.clearProperty("indexino.cache.dir")
            } else {
                System.setProperty("indexino.cache.dir", previousCacheDirectory)
            }
        }
    }

    @Test
    fun `refresh fails when observed includeDeps mismatches the requested scope`() {
        val workspace = createGitWorkspace()
        val cacheDirectory = createTempDirectory("indexino-include-deps-mismatch-cache-")
        tempDirs.add(cacheDirectory)
        val previousCacheDirectory = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheDirectory.toString())
        try {
            val indexino = Indexino.connectBlocking(workspace)
            indexino.observedIncludeDepsOverrideForTests = false
            try {
                val failure =
                    assertFailsWith<IndexinoException> {
                        runSuspend {
                            indexino
                                .refresh(
                                    RefreshRequest.forScope(
                                        IndexScope.gradle(":ui").includingDependencies()
                                    )
                                )
                                .await()
                        }
                    }
                assertEquals("TOPOLOGY", failure.failure.category.value)
                assertEquals("scope_include_deps_mismatch", failure.failure.code)
                assertTrue(failure.failure.retryable)
            } finally {
                indexino.observedIncludeDepsOverrideForTests = null
                indexino.close()
            }
        } finally {
            if (previousCacheDirectory == null) {
                System.clearProperty("indexino.cache.dir")
            } else {
                System.setProperty("indexino.cache.dir", previousCacheDirectory)
            }
        }
    }

    @Test
    fun `bazel scopes without includingDependencies fail rather than silently expanding deps`() {
        val workspace = createGitWorkspace()
        val cacheDirectory = createTempDirectory("indexino-bazel-deps-required-cache-")
        tempDirs.add(cacheDirectory)
        val previousCacheDirectory = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheDirectory.toString())
        try {
            val indexino = Indexino.connectBlocking(workspace)
            try {
                val failure =
                    assertFailsWith<IndexinoException> {
                        runSuspend {
                            indexino
                                .refresh(RefreshRequest.forScope(IndexScope.bazel("//ui:ui")))
                                .await()
                        }
                    }
                assertEquals("INVALID_REQUEST", failure.failure.category.value)
                assertEquals("bazel_dependencies_required", failure.failure.code)
                assertTrue(failure.failure.message.contains("includingDependencies()"))
            } finally {
                indexino.close()
            }
        } finally {
            if (previousCacheDirectory == null) {
                System.clearProperty("indexino.cache.dir")
            } else {
                System.setProperty("indexino.cache.dir", previousCacheDirectory)
            }
        }
    }

    @Test
    fun `close reclaims unpinned client generation copies`() {
        val workspace = createGitWorkspace()
        val cacheDirectory = createTempDirectory("indexino-close-reclaim-cache-")
        tempDirs.add(cacheDirectory)
        val previousCacheDirectory = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheDirectory.toString())
        try {
            val indexino = Indexino.connectBlocking(workspace)
            try {
                runSuspend {
                    indexino.refresh(RefreshRequest.forScope(IndexScope.gradle(":ui"))).await()
                }
                assertTrue(generationCopyExists(workspace, null))
            } finally {
                indexino.close()
            }
            assertFalse(generationCopyExists(workspace, null))
        } finally {
            if (previousCacheDirectory == null) {
                System.clearProperty("indexino.cache.dir")
            } else {
                System.setProperty("indexino.cache.dir", previousCacheDirectory)
            }
        }
    }

    @Test
    fun `refresh finishing after close fails closed and leaves no generation copies`() {
        val workspace = createGitWorkspace()
        val cacheDirectory = createTempDirectory("indexino-close-during-refresh-cache-")
        tempDirs.add(cacheDirectory)
        val previousCacheDirectory = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheDirectory.toString())
        try {
            val indexino = Indexino.connectBlocking(workspace)
            indexino.afterPublishGenerationStoreForTests = { indexino.close() }
            try {
                val failure =
                    assertFailsWith<IndexinoException> {
                        runSuspend {
                            indexino
                                .refresh(RefreshRequest.forScope(IndexScope.gradle(":ui")))
                                .await()
                        }
                    }
                assertEquals("CLOSED", failure.failure.category.value)
                assertEquals("client_closed", failure.failure.code)
                assertFalse(generationCopyExists(workspace, null))
            } finally {
                indexino.afterPublishGenerationStoreForTests = null
                indexino.close()
            }
        } finally {
            if (previousCacheDirectory == null) {
                System.clearProperty("indexino.cache.dir")
            } else {
                System.setProperty("indexino.cache.dir", previousCacheDirectory)
            }
        }
    }

    @Test
    fun `close during same-generation refresh keeps pinned snapshot stores`() {
        val workspace = createGitWorkspace()
        val cacheDirectory = createTempDirectory("indexino-close-pinned-refresh-cache-")
        tempDirs.add(cacheDirectory)
        val previousCacheDirectory = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheDirectory.toString())
        try {
            val indexino = Indexino.connectBlocking(workspace)
            val first = runSuspend {
                indexino.refresh(RefreshRequest.forScope(IndexScope.gradle(":ui"))).await()
            }
            val pinned = runSuspend { indexino.snapshot() }
            try {
                indexino.afterPublishGenerationStoreForTests = { indexino.close() }
                val failure =
                    assertFailsWith<IndexinoException> {
                        runSuspend {
                            indexino
                                .refresh(RefreshRequest.forScope(IndexScope.gradle(":ui")))
                                .await()
                        }
                    }
                assertEquals("CLOSED", failure.failure.category.value)
                assertTrue(generationCopyExists(workspace, first.generation.value))
                val symbols = runSuspend {
                    pinned.findSymbols(
                        SymbolQuery.named("ActionButton"),
                        QueryOptions.page(limit = 1),
                    )
                }
                assertEquals(1, symbols.items.size)
            } finally {
                indexino.afterPublishGenerationStoreForTests = null
                pinned.close()
                indexino.close()
            }
            assertFalse(generationCopyExists(workspace, first.generation.value))
        } finally {
            if (previousCacheDirectory == null) {
                System.clearProperty("indexino.cache.dir")
            } else {
                System.setProperty("indexino.cache.dir", previousCacheDirectory)
            }
        }
    }

    @Test
    fun `findSymbols does not expose resource definitions before S10`() {
        val workspace = createGitWorkspace()
        val cacheDirectory = createTempDirectory("indexino-no-res-cache-")
        tempDirs.add(cacheDirectory)
        val previousCacheDirectory = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheDirectory.toString())
        val indexino = Indexino.connectBlocking(workspace)
        try {
            runSuspend {
                indexino.refresh(RefreshRequest.forScope(IndexScope.gradle(":ui"))).await()
            }
            runSuspend { indexino.snapshot() }
                .use { snapshot ->
                    val resources = runSuspend {
                        snapshot.findSymbols(
                            SymbolQuery.named("main").withKind("resource"),
                            QueryOptions.page(limit = 20),
                        )
                    }
                    assertTrue(resources.items.isEmpty())
                }
        } finally {
            indexino.close()
            if (previousCacheDirectory == null) {
                System.clearProperty("indexino.cache.dir")
            } else {
                System.setProperty("indexino.cache.dir", previousCacheDirectory)
            }
        }
    }

    @Test
    fun `concurrent refreshes on one workspace both publish openable generations`() {
        val workspace = createGitWorkspace()
        val cacheDirectory = createTempDirectory("indexino-lock-cache-")
        tempDirs.add(cacheDirectory)
        val previousCacheDirectory = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheDirectory.toString())
        val clientA = Indexino.connectBlocking(workspace)
        val clientB = Indexino.connectBlocking(workspace)
        try {
            val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
            val errors = mutableListOf<Throwable>()
            val resultA = arrayOfNulls<RefreshResult>(1)
            val resultB = arrayOfNulls<RefreshResult>(1)
            val threadA = Thread {
                runCatching { resultA[0] = runSuspend { clientA.refresh(request).await() } }
                    .onFailure { errors += it }
            }
            val threadB = Thread {
                runCatching { resultB[0] = runSuspend { clientB.refresh(request).await() } }
                    .onFailure { errors += it }
            }
            threadA.start()
            threadB.start()
            threadA.join()
            threadB.join()
            assertTrue(errors.isEmpty(), errors.joinToString())
            val first = requireNotNull(resultA[0])
            val second = requireNotNull(resultB[0])
            runSuspend { clientA.snapshot() }
                .use { snapshot ->
                    assertEquals(first.generation, snapshot.generation)
                    val symbols = runSuspend {
                        snapshot.findSymbols(
                            SymbolQuery.named("ActionButton"),
                            QueryOptions.page(limit = 1),
                        )
                    }
                    assertEquals(1, symbols.items.size)
                }
            runSuspend { clientB.snapshot() }
                .use { snapshot ->
                    assertEquals(second.generation, snapshot.generation)
                    val symbols = runSuspend {
                        snapshot.findSymbols(
                            SymbolQuery.named("ActionButton"),
                            QueryOptions.page(limit = 1),
                        )
                    }
                    assertEquals(1, symbols.items.size)
                }
        } finally {
            clientA.close()
            clientB.close()
            if (previousCacheDirectory == null) {
                System.clearProperty("indexino.cache.dir")
            } else {
                System.setProperty("indexino.cache.dir", previousCacheDirectory)
            }
        }
    }

    @Test
    fun `clients keep independent generation copies so reclaim cannot delete peers`() {
        val workspace = createGitWorkspace()
        val cacheDirectory = createTempDirectory("indexino-clients-cache-")
        tempDirs.add(cacheDirectory)
        val previousCacheDirectory = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheDirectory.toString())
        val clientA = Indexino.connectBlocking(workspace)
        val clientB = Indexino.connectBlocking(workspace)
        try {
            val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
            val firstGeneration = runSuspend { clientA.refresh(request).await() }.generation
            val pinned = runSuspend { clientA.snapshot() }
            try {
                runSuspend { clientB.refresh(request).await() }
                Files.writeString(
                    workspace.resolve("ui/src/main/kotlin/PeerRefresh.kt"),
                    "fun PeerRefresh() = Unit",
                )
                val secondGeneration = runSuspend { clientB.refresh(request).await() }.generation
                assertNotEquals(firstGeneration, secondGeneration)

                assertEquals(firstGeneration, pinned.generation)
                val pinnedSymbols = runSuspend {
                    pinned.findSymbols(
                        SymbolQuery.named("ActionButton"),
                        QueryOptions.page(limit = 1),
                    )
                }
                assertEquals(1, pinnedSymbols.items.size)
            } finally {
                pinned.close()
            }
        } finally {
            clientA.close()
            clientB.close()
            if (previousCacheDirectory == null) {
                System.clearProperty("indexino.cache.dir")
            } else {
                System.setProperty("indexino.cache.dir", previousCacheDirectory)
            }
        }
    }

    @Test
    fun `symbol ownerId prefers the enclosing definition in the same file`() {
        val workspace = createGitWorkspace()
        Files.writeString(
            workspace.resolve("ui/src/main/kotlin/OwnerA.kt"),
            """
            package owners
            class SharedOwner {
                fun memberA() = Unit
            }
            """
                .trimIndent(),
        )
        Files.writeString(
            workspace.resolve("ui/src/main/kotlin/OwnerB.kt"),
            """
            package owners
            class SharedOwner {
                fun memberB() = Unit
            }
            """
                .trimIndent(),
        )
        val cacheDirectory = createTempDirectory("indexino-owner-cache-")
        tempDirs.add(cacheDirectory)
        val previousCacheDirectory = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheDirectory.toString())
        val indexino = Indexino.connectBlocking(workspace)
        try {
            runSuspend {
                indexino.refresh(RefreshRequest.forScope(IndexScope.gradle(":ui"))).await()
            }
            runSuspend { indexino.snapshot() }
                .use { snapshot ->
                    val owners =
                        runSuspend {
                                snapshot.findSymbols(
                                    SymbolQuery.named("SharedOwner"),
                                    QueryOptions.page(limit = 10),
                                )
                            }
                            .items
                    assertEquals(2, owners.size)
                    val ownerA = owners.single { it.location.file.path.endsWith("OwnerA.kt") }
                    val ownerB = owners.single { it.location.file.path.endsWith("OwnerB.kt") }
                    val memberA =
                        runSuspend {
                                snapshot.findSymbols(
                                    SymbolQuery.named("memberA"),
                                    QueryOptions.page(limit = 1),
                                )
                            }
                            .items
                            .single()
                    val memberB =
                        runSuspend {
                                snapshot.findSymbols(
                                    SymbolQuery.named("memberB"),
                                    QueryOptions.page(limit = 1),
                                )
                            }
                            .items
                            .single()
                    assertEquals(ownerA.id, memberA.ownerId)
                    assertEquals(ownerB.id, memberB.ownerId)
                }
        } finally {
            indexino.close()
            if (previousCacheDirectory == null) {
                System.clearProperty("indexino.cache.dir")
            } else {
                System.setProperty("indexino.cache.dir", previousCacheDirectory)
            }
        }
    }

    @Test
    fun `findReferences does not attach call sites to same-name properties`() {
        val workspace = createGitWorkspace()
        Files.writeString(
            workspace.resolve("ui/src/main/kotlin/SharedName.kt"),
            """
            val shared = 1
            fun shared(value: Int) = value
            fun useShared() {
                shared(2)
            }
            """
                .trimIndent(),
        )
        val cacheDirectory = createTempDirectory("indexino-arity-cache-")
        tempDirs.add(cacheDirectory)
        val previousCacheDirectory = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheDirectory.toString())
        val indexino = Indexino.connectBlocking(workspace)
        try {
            runSuspend {
                indexino.refresh(RefreshRequest.forScope(IndexScope.gradle(":ui"))).await()
            }
            runSuspend { indexino.snapshot() }
                .use { snapshot ->
                    val symbols =
                        runSuspend {
                                snapshot.findSymbols(
                                    SymbolQuery.named("shared"),
                                    QueryOptions.page(limit = 10),
                                )
                            }
                            .items
                    val property = symbols.single { it.kind == "property" }
                    val function = symbols.single { it.kind == "function" }
                    assertNotEquals(property.id, function.id)

                    val propertyRefs = runSuspend {
                        snapshot.findReferences(
                            ReferenceQuery.to(property.id),
                            QueryOptions.page(limit = 10),
                        )
                    }
                    assertTrue(propertyRefs.items.none { it.arity != null })

                    val functionRefs = runSuspend {
                        snapshot.findReferences(
                            ReferenceQuery.to(function.id),
                            QueryOptions.page(limit = 10),
                        )
                    }
                    assertEquals(1, functionRefs.items.size)
                    assertEquals(function.id, functionRefs.items.single().symbolId)
                    assertEquals(1, functionRefs.items.single().arity)
                    assertFalse(property.id in functionRefs.items.single().candidateSymbolIds)
                }
        } finally {
            indexino.close()
            if (previousCacheDirectory == null) {
                System.clearProperty("indexino.cache.dir")
            } else {
                System.setProperty("indexino.cache.dir", previousCacheDirectory)
            }
        }
    }

    @Test
    fun `snapshots pin immutable generations and definition identities`() {
        val workspace = createGitWorkspace()
        Files.writeString(
            workspace.resolve("ui/src/main/kotlin/Overloads.kt"),
            """
            fun overloaded() {}
            fun overloaded(value: Int) {}
            fun callOverloads() {
                overloaded()
                overloaded(1)
            }
            """
                .trimIndent(),
        )
        val cacheDirectory = createTempDirectory("indexino-generations-cache-")
        tempDirs.add(cacheDirectory)
        val previousCacheDirectory = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheDirectory.toString())
        val indexino = Indexino.connectBlocking(workspace)
        try {
            val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
            val firstResult = runSuspend { indexino.refresh(request).await() }
            val firstSnapshot = runSuspend { indexino.snapshot() }
            try {
                val overloads =
                    runSuspend {
                            firstSnapshot.findSymbols(
                                SymbolQuery.named("overloaded"),
                                QueryOptions.page(limit = 10),
                            )
                        }
                        .items
                assertEquals(2, overloads.size)
                assertEquals(2, overloads.map { it.id }.distinct().size)
                overloads.forEach { overload ->
                    val references = runSuspend {
                        firstSnapshot.findReferences(
                            ReferenceQuery.to(overload.id),
                            QueryOptions.page(limit = 10),
                        )
                    }
                    assertEquals(1, references.items.size)
                    assertEquals(overload.id, references.items.single().symbolId)
                }

                Files.writeString(
                    workspace.resolve("ui/src/main/kotlin/AddedAfterSnapshot.kt"),
                    "fun AddedAfterSnapshot() = Unit",
                )
                val secondResult = runSuspend { indexino.refresh(request).await() }
                assertNotEquals(firstResult.generation, secondResult.generation)
                val oldSymbols = runSuspend {
                    firstSnapshot.findSymbols(
                        SymbolQuery.named("AddedAfterSnapshot"),
                        QueryOptions.page(limit = 10),
                    )
                }
                assertTrue(oldSymbols.items.isEmpty())
                runSuspend { indexino.snapshot() }
                    .use { secondSnapshot ->
                        val newSymbols = runSuspend {
                            secondSnapshot.findSymbols(
                                SymbolQuery.named("AddedAfterSnapshot"),
                                QueryOptions.page(limit = 10),
                            )
                        }
                        assertEquals(1, newSymbols.items.size)
                    }

                assertPluginGenerationIsDistinct(
                    indexino,
                    request,
                    firstResult,
                    firstSnapshot,
                    secondResult,
                )
            } finally {
                firstSnapshot.close()
            }
            assertFalse(generationCopyExists(workspace, firstResult.generation.value))
        } finally {
            indexino.close()
            if (previousCacheDirectory == null) {
                System.clearProperty("indexino.cache.dir")
            } else {
                System.setProperty("indexino.cache.dir", previousCacheDirectory)
            }
        }
    }

    private fun assertPluginGenerationIsDistinct(
        indexino: Indexino,
        request: RefreshRequest,
        firstResult: RefreshResult,
        firstSnapshot: IndexSnapshot,
        secondResult: RefreshResult,
    ) {
        val pluginResult = runSuspend {
            indexino
                .refresh(request.withPlugin(PluginId.of("dev.sebastiano.selection-context")))
                .await()
        }
        assertEquals(secondResult.revision, pluginResult.revision)
        assertNotEquals(secondResult.generation, pluginResult.generation)
        assertEquals(firstResult.generation, firstSnapshot.generation)
        val stillPinnedSymbols = runSuspend {
            firstSnapshot.findSymbols(
                SymbolQuery.named("AddedAfterSnapshot"),
                QueryOptions.page(limit = 10),
            )
        }
        assertTrue(stillPinnedSymbols.items.isEmpty())
    }

    private fun assertSnapshotQueries(
        snapshot: IndexSnapshot,
        result: RefreshResult,
        workspace: java.nio.file.Path,
    ) {
        assertEquals(result.generation, snapshot.generation)
        assertEquals(result.revision, snapshot.revision)

        val sourceFile =
            SourceFile.of(
                SourceOriginId.of("workspace"),
                "ui/src/main/kotlin/Panel.kt",
                "ui/src/main/kotlin/Panel.kt",
            )
        val firstSymbols = runSuspend {
            snapshot.findSymbols(SymbolQuery.inFile(sourceFile), QueryOptions.page(limit = 2))
        }
        assertEquals(2, firstSymbols.items.size)
        assertTrue(firstSymbols.hasMore)
        val cursor = requireNotNull(firstSymbols.nextCursor)
        assertTrue(cursor.startsWith("indexino:v1:"))
        assertHostQueryLimit(
            snapshot = snapshot,
            sourceFile = sourceFile,
            cursor = cursor,
            totalCount = checkNotNull(firstSymbols.totalCount),
        )

        val remainingSymbols = runSuspend {
            snapshot.findSymbols(
                SymbolQuery.inFile(sourceFile),
                QueryOptions.after(limit = 10, cursor = cursor),
            )
        }
        assertFalse(remainingSymbols.items.isEmpty())

        val actionButton =
            runSuspend {
                    snapshot.findSymbols(
                        SymbolQuery.named("ActionButton"),
                        QueryOptions.page(limit = 1),
                    )
                }
                .items
                .single()
        val references = runSuspend {
            snapshot.findReferences(
                ReferenceQuery.to(actionButton.id),
                QueryOptions.page(limit = 1),
            )
        }
        assertEquals(1, references.items.size)
        val invalidCursorFailure =
            assertFailsWith<IndexinoException> {
                runSuspend {
                    snapshot.findSymbols(
                        SymbolQuery.inFile(sourceFile),
                        QueryOptions.after(limit = 1, cursor = "not-a-cursor"),
                    )
                }
            }
        assertEquals("INVALID_REQUEST", invalidCursorFailure.failure.category.value)
        val otherOriginFile =
            SourceFile.of(SourceOriginId.of("other"), sourceFile.path, sourceFile.displayPath)
        val otherOriginSymbols = runSuspend {
            snapshot.findSymbols(SymbolQuery.inFile(otherOriginFile), QueryOptions.page(limit = 10))
        }
        assertTrue(otherOriginSymbols.items.isEmpty())

        snapshot.close()
        val closedFailure =
            assertFailsWith<IndexinoException> {
                runSuspend {
                    snapshot.findSymbols(
                        SymbolQuery.inFile(sourceFile),
                        QueryOptions.page(limit = 1),
                    )
                }
            }
        assertEquals("CLOSED", closedFailure.failure.category.value)
        assertFalse(Files.exists(workspace.resolve(".indexino")))
    }

    private fun assertHostQueryLimit(
        snapshot: IndexSnapshot,
        sourceFile: SourceFile,
        cursor: String,
        totalCount: Int,
    ) {
        // Host maximum is 10_000 (facade policy; not published as a public constant yet).
        val hostMax = 10_000
        val overflowSafeSymbols = runSuspend {
            snapshot.findSymbols(
                SymbolQuery.inFile(sourceFile),
                QueryOptions.page(limit = hostMax, offset = 1),
            )
        }
        assertEquals(totalCount - 1, overflowSafeSymbols.items.size)
        assertFalse(overflowSafeSymbols.hasMore)
        assertEquals(null, overflowSafeSymbols.nextCursor)

        val atHostMax = runSuspend {
            snapshot.findSymbols(SymbolQuery.inFile(sourceFile), QueryOptions.page(limit = hostMax))
        }
        assertEquals(totalCount, atHostMax.totalCount)

        val overHostMax =
            assertFailsWith<IndexinoException> {
                runSuspend {
                    snapshot.findSymbols(
                        SymbolQuery.inFile(sourceFile),
                        QueryOptions.page(limit = hostMax + 1),
                    )
                }
            }
        assertEquals("INVALID_REQUEST", overHostMax.failure.category.value)
        assertEquals("limit_exceeds_maximum", overHostMax.failure.code)
        assertTrue(overHostMax.failure.message.contains((hostMax + 1).toString()))
        assertTrue(overHostMax.failure.message.contains(hostMax.toString()))

        val overHostMaxCursor =
            assertFailsWith<IndexinoException> {
                runSuspend {
                    snapshot.findSymbols(
                        SymbolQuery.inFile(sourceFile),
                        QueryOptions.after(limit = hostMax + 1, cursor = cursor),
                    )
                }
            }
        assertEquals("limit_exceeds_maximum", overHostMaxCursor.failure.code)

        val actionButtonForLimit =
            runSuspend {
                    snapshot.findSymbols(
                        SymbolQuery.named("ActionButton"),
                        QueryOptions.page(limit = 1),
                    )
                }
                .items
                .single()
        val overHostMaxReferences =
            assertFailsWith<IndexinoException> {
                runSuspend {
                    snapshot.findReferences(
                        ReferenceQuery.to(actionButtonForLimit.id),
                        QueryOptions.page(limit = hostMax + 1),
                    )
                }
            }
        assertEquals("limit_exceeds_maximum", overHostMaxReferences.failure.code)
    }

    private fun generationCopyExists(workspace: java.nio.file.Path, generation: String?): Boolean {
        val clientsRoot = InProcessCacheLayout.storeRoot(workspace).resolve("clients")
        if (!Files.isDirectory(clientsRoot)) return false
        return Files.walk(clientsRoot).use { paths ->
            paths.anyMatch { path ->
                if (!Files.isDirectory(path)) return@anyMatch false
                if (generation == null) {
                    path.fileName.toString() == "store"
                } else {
                    path.fileName.toString() == generation
                }
            }
        }
    }

    private fun createGitWorkspace(): java.nio.file.Path {
        val workspace = createTempDirectory("indexino-facade-")
        tempDirs.add(workspace)
        val fixtureRoot = Path("src/test/resources/gradle-fixtures/multi-module")
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

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            }
        )
        return checkNotNull(outcome).getOrThrow()
    }
}
