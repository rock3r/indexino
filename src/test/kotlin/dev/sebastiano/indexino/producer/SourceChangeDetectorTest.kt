package dev.sebastiano.indexino.producer

import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SourceChangeDetectorTest {
    @Test
    fun `role changes invalidate unchanged bytes and remove stale language facts`() {
        val root = createTempDirectory("indexino-source-roles-")
        try {
            root.resolve("Example.java").writeText("class Example { void render() {} }")
            root.resolve("Sample.kt").writeText("class Sample { fun render() {} }")
            val code = listOf("Example.java", "Sample.kt").map { IndexedSource.workspace(root, it) }
            val resources = code.map { it.copy(isCode = false) }
            val codeSnapshot = SourceContentSnapshot.capture(code)
            val resourceSnapshot = SourceContentSnapshot.capture(resources)
            assertNotEquals(codeSnapshot.combinedHash(), resourceSnapshot.combinedHash())
            assertNotEquals(
                FileHashProducer.combinedIndexedSourcesHash(code),
                FileHashProducer.combinedIndexedSourcesHash(resources),
            )
            val store = XodusCodeIndexStore.open(root.resolve("store"))
            try {
                for (sources in listOf(code, resources, code)) {
                    val snapshot = SourceContentSnapshot.capture(sources)
                    val changes = SourceChangeDetector.detect(store, sources, snapshot)
                    assertEquals(sources.toSet(), changes.changedSources)
                    val context =
                        IndexBuildContext(
                            store = store,
                            commitHash = "roles",
                            sourceFiles = sources.map { it.path },
                            sources = sources,
                            sourceSnapshot = snapshot,
                            changedSourceSet = changes.changedSources,
                        )
                    listOf("file-hash", "java-source", "kotlin-psi-symbols").forEach {
                        checkNotNull(ProducerRegistry.get(it)).produce(context)
                    }
                    assertEquals(2, store.prefixScan("file:").count())
                    assertEquals(sources.first().isCode, store.prefixScan("sym:").any())
                    assertTrue(
                        SourceChangeDetector.detect(store, sources, snapshot)
                            .changedSources
                            .isEmpty()
                    )
                }
            } finally {
                store.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `detects equal relative paths independently by origin`() {
        val firstRoot = createTempDirectory("indexino-change-origin-first-")
        val secondRoot = createTempDirectory("indexino-change-origin-second-")
        val relativePath = "src/main/kotlin/Sample.kt"
        firstRoot
            .resolve(relativePath)
            .also { it.parent.createDirectories() }
            .writeText("class First")
        secondRoot
            .resolve(relativePath)
            .also { it.parent.createDirectories() }
            .writeText("class Second")
        val sources =
            listOf(
                IndexedSource("git:first", firstRoot, relativePath),
                IndexedSource("git:second", secondRoot, relativePath),
            )

        val store = XodusCodeIndexStore.open(createTempDirectory("indexino-change-store-"))
        try {
            FileHashProducer()
                .produce(
                    IndexBuildContext(
                        store = store,
                        commitHash = "first",
                        sourceFiles = listOf(relativePath),
                        sources = sources,
                    ),
                    store,
                )
            secondRoot.resolve(relativePath).writeText("class SecondChanged")

            val changes = SourceChangeDetector.detect(store, sources)

            assertEquals(setOf(sources[1]), changes.changedSources)
            assertEquals(emptySet(), changes.deletedSources)
            val context =
                IndexBuildContext(
                    store = store,
                    commitHash = "second",
                    sourceFiles = listOf(relativePath),
                    sources = sources,
                    changedSourceSet = changes.changedSources,
                    deletedSourceSet = changes.deletedSources,
                )
            assertEquals(setOf(sources[1]), context.changedSources)
        } finally {
            store.close()
        }
    }
}
