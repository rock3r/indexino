package dev.sebastiano.indexino.producer

import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceChangeDetectorTest {
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
