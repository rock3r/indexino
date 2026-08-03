package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.producer.IndexedSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoRefreshControllerTest {
    @Test
    fun `external origin changes reach the refresh queue`() {
        val root = Files.createTempDirectory(Path.of("/tmp"), "indexino-external-watch-")
        val workspace = root.resolve("workspace")
        val externalRoot = root.resolve("external")
        val sourceRoot = externalRoot.resolve("src/main/kotlin")
        val source = sourceRoot.resolve("Convention.kt")
        Files.createDirectories(workspace)
        Files.createDirectories(sourceRoot)
        Files.writeString(source, "class Convention")
        val refreshes = AtomicInteger()
        val controller =
            AutoRefreshController(
                workspace,
                AutoRefreshMode.ENABLED,
                refresh = { refreshes.incrementAndGet() },
            )
        try {
            val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
            controller.register(
                request,
                listOf(IndexedSource("external", externalRoot, "src/main/kotlin/Convention.kt")),
            )

            assertTrue(sourceRoot in controller.directoriesForTests(request))
            controller.onPathChangedForTests(source)
            awaitRefresh(refreshes)
        } finally {
            controller.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `watches empty non-main source roots discovered from topology`() {
        val root = Files.createTempDirectory(Path.of("/tmp"), "indexino-empty-source-root-")
        val workspace = root.resolve("workspace")
        val sourceRoot = workspace.resolve("module/src/commonMain/kotlin")
        Files.createDirectories(sourceRoot)
        val controller = AutoRefreshController(workspace, AutoRefreshMode.ENABLED, refresh = {})
        try {
            val request = RefreshRequest.forScope(IndexScope.gradle(":module"))
            controller.register(request, sources = emptyList(), topologyRoots = listOf(workspace))

            assertTrue(sourceRoot in controller.directoriesForTests(request))
        } finally {
            controller.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `refreshed closure stops watching removed external roots`() {
        val root = Files.createTempDirectory(Path.of("/tmp"), "indexino-watch-replace-")
        val workspace = root.resolve("workspace")
        val firstRoot = root.resolve("first")
        val secondRoot = root.resolve("second")
        val firstSource = firstRoot.resolve("src/main/kotlin/First.kt")
        val secondSource = secondRoot.resolve("src/main/kotlin/Second.kt")
        Files.createDirectories(workspace)
        Files.createDirectories(firstSource.parent)
        Files.createDirectories(secondSource.parent)
        Files.writeString(firstSource, "class First")
        Files.writeString(secondSource, "class Second")
        val refreshes = AtomicInteger()
        val controller =
            AutoRefreshController(
                workspace,
                AutoRefreshMode.ENABLED,
                refresh = { refreshes.incrementAndGet() },
            )
        try {
            val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
            controller.register(
                request,
                listOf(IndexedSource("first", firstRoot, "src/main/kotlin/First.kt")),
            )
            controller.register(
                request,
                listOf(IndexedSource("second", secondRoot, "src/main/kotlin/Second.kt")),
            )

            assertFalse(firstSource.parent in controller.directoriesForTests(request))
            assertTrue(secondSource.parent in controller.directoriesForTests(request))
            controller.onPathChangedForTests(firstSource)
            Thread.sleep(DEBOUNCE_SETTLE_MILLIS)
            assertEquals(0, refreshes.get())
            controller.onPathChangedForTests(secondSource)
            awaitRefresh(refreshes)
        } finally {
            controller.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun awaitRefresh(refreshes: AtomicInteger) {
        repeat(100) {
            if (refreshes.get() > 0) return
            Thread.sleep(20L)
        }
        assertTrue(refreshes.get() > 0, "No refresh was enqueued")
    }

    private companion object {
        const val DEBOUNCE_SETTLE_MILLIS = 250L
    }
}
