package dev.sebastiano.indexino.producer

import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.producer.xml.ResourceMetadata
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SourceChangeDetectorTest {
    @Test
    fun `watcher hash inventory detects only edited paths and preserves origin identity`() {
        val first = createTempDirectory("indexino-hint-diff-a-")
        val second = createTempDirectory("indexino-hint-diff-b-")
        try {
            val a = IndexedSource("origin:a", first, "Shared.kt")
            val b = IndexedSource("origin:b", second, "Shared.kt")
            val deleted = IndexedSource("origin:a", first, "Deleted.kt")
            val added = IndexedSource("origin:b", second, "Added.kt")
            first.resolve(a.path).writeText("class First")
            second.resolve(b.path).writeText("class Second")
            first.resolve(deleted.path).writeText("class Removed")
            val previous = SourceContentSnapshot.capture(listOf(a, b, deleted)).hashes()
            first.resolve(a.path).writeText("class Updated")
            second.resolve(added.path).writeText("class Added")
            val sources = listOf(a, b, added)
            val snapshot =
                SourceContentSnapshot.capture(sources, previous, setOf(first.resolve(a.path)))
            val changes = SourceChangeDetector.detect(previous, sources, snapshot)
            assertEquals(setOf(a, added), changes.changedSources)
            assertEquals(setOf(deleted.copy(originRoot = Path.of("."))), changes.deletedSources)
        } finally {
            first.toFile().deleteRecursively()
            second.toFile().deleteRecursively()
        }
    }

    @Test
    fun `metadata analysis cannot bypass an inherited hash mismatch`() {
        val root = createTempDirectory("indexino-inherited-metadata-")
        try {
            val metadata = IndexedSource.workspace(root, "build.gradle.kts")
            val resource = IndexedSource.workspace(root, "src/main/res/layout/panel.xml")
            resource.originRoot.resolve(resource.path).parent.createDirectories()
            root.resolve(metadata.path).writeText("namespace = \"original\"")
            root.resolve(resource.path).writeText("<LinearLayout/>")
            val sources = listOf(metadata, resource)
            val original = SourceContentSnapshot.capture(sources)
            root.resolve(metadata.path).writeText("namespace = \"changed\"")
            val inherited =
                SourceContentSnapshot.capture(
                    sources,
                    original.hashes(),
                    setOf(root.resolve(resource.path)),
                )
            XodusCodeIndexStore.open(root.resolve("store")).use { store ->
                val context =
                    IndexBuildContext(
                        store,
                        "metadata",
                        sourceFiles = sources.map { it.path },
                        sources = sources,
                        sourceSnapshot = inherited,
                    )
                assertFailsWith<IllegalStateException> {
                    ResourceMetadata.resourcePackage(context, resource)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `inherited hashes avoid unneeded reads but analyzers verify inherited bytes`() {
        val root = createTempDirectory("indexino-inherited-capture-")
        try {
            val unchanged = IndexedSource.workspace(root, "Unchanged.kt")
            val edited = IndexedSource.workspace(root, "Edited.kt")
            root.resolve(unchanged.path).writeText("class Original")
            root.resolve(edited.path).writeText("class Before")
            val originals = SourceContentSnapshot.capture(listOf(unchanged, edited))
            root.resolve(unchanged.path).writeText("class ChangedWithoutHint")
            root.resolve(edited.path).writeText("class After")

            val snapshot =
                SourceContentSnapshot.capture(
                    listOf(unchanged, edited),
                    originals.hashes(),
                    setOf(root.resolve(edited.path)),
                )
            assertEquals(originals.contentHash(unchanged), snapshot.contentHash(unchanged))
            assertEquals("class After", snapshot.content(edited))
            assertFailsWith<IllegalStateException> { snapshot.content(unchanged) }
            assertEquals(
                "class ChangedWithoutHint",
                root.resolve(unchanged.path).toFile().readText(),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

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
