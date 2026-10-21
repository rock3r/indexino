package dev.sebastiano.indexino.producer

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.FileHashRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileHashProducerTest {
    private lateinit var store: XodusCodeIndexStore
    private lateinit var tempDir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("filehash-producer-")
        store = XodusCodeIndexStore.open(tempDir.resolve("base.xodus"))
    }

    @AfterTest
    fun tearDown() {
        store.close()
    }

    @Test
    fun `writes file hash records for each source file`() {
        val workspace = Path("src/test/resources/fixtures/bazel")
        val context =
            IndexBuildContext(
                store = store,
                commitHash = "abc123",
                scope = "//plugins/foo/ui:ui",
                sourceFiles =
                    listOf(
                        "plugins/foo/ui/src/main/kotlin/Panel.kt",
                        "plugins/foo/ui/src/main/kotlin/Other.kt",
                    ),
                workspaceRoot = workspace,
            )

        FileHashProducer().produce(context, store)

        val fileKeys = store.prefixScan("file:").toList()
        assertEquals(2, fileKeys.size)
        fileKeys.forEach { (_, record) ->
            val fileRecord = record as FileHashRecord
            assertTrue(fileRecord.contentHash.startsWith("sha256:"))
            assertTrue(context.sourceFiles.contains(fileRecord.relativePath))
        }
    }

    @Test
    fun `reports origin-qualified progress for duplicate paths`() {
        val firstOrigin = tempDir.resolve("first").also { Files.createDirectories(it) }
        val secondOrigin = tempDir.resolve("second").also { Files.createDirectories(it) }
        Files.writeString(firstOrigin.resolve("Shared.kt"), "class First")
        Files.writeString(secondOrigin.resolve("Shared.kt"), "class Second")
        val progress = mutableListOf<String>()
        val sources =
            listOf(
                IndexedSource("repo:first", firstOrigin, "Shared.kt"),
                IndexedSource("repo:second", secondOrigin, "Shared.kt"),
            )

        FileHashProducer()
            .produce(
                IndexBuildContext(
                    store = store,
                    commitHash = "abc123",
                    sourceFiles = sources.map(IndexedSource::path),
                    sources = sources,
                    changedSourceSet = sources.toSet(),
                    progress = progress::add,
                ),
                store,
            )

        assertEquals(listOf("[1/2] repo:first:Shared.kt", "[2/2] repo:second:Shared.kt"), progress)
    }

    @Test
    fun `reindex removes stale hashes after content changes or files disappear`() {
        val producer = FileHashProducer()
        producer.produce(
            IndexBuildContext.forInlineSources(
                store = store,
                commitHash = "abc123",
                sourceFiles = mapOf("A.java" to "class A {}", "layout.xml" to "<FrameLayout />"),
            )
        )
        producer.produce(
            IndexBuildContext.forInlineSources(
                store = store,
                commitHash = "abc123",
                sourceFiles = mapOf("A.java" to "class A { int value; }"),
            )
        )

        val records =
            store.prefixScan("file:").map { it.second }.filterIsInstance<FileHashRecord>().toList()
        assertEquals(1, records.size)
        assertEquals("A.java", records.single().relativePath)
        assertEquals(
            FileHashProducer.contentHash("class A { int value; }"),
            records.single().contentHash,
        )
    }

    @Test
    fun `refresh checks changed identities once rather than rescanning them per stored file`() {
        val sources =
            (0 until 64).map { IndexedSource("workspace", tempDir, "File$it.java") } +
                IndexedSource("nested", tempDir, "File0.java")
        val originalHash = FileHashProducer.contentHash("original")
        sources.forEach { source ->
            store.put(
                CodeIndexKey.file("${source.originId}:${source.path}", originalHash),
                FileHashRecord(source.path, originalHash, source.originId),
            )
        }
        val changed =
            object : AbstractSet<IndexedSource>() {
                private val backing = setOf(sources.first())
                var iterations = 0
                override val size: Int
                    get() = backing.size

                override fun iterator(): Iterator<IndexedSource> {
                    iterations++
                    return backing.iterator()
                }

                override fun contains(element: IndexedSource): Boolean = element in backing
            }

        FileHashProducer()
            .produce(
                IndexBuildContext(
                    store = store,
                    commitHash = "abc123",
                    sourceFiles = sources.map(IndexedSource::path),
                    sources = sources,
                    changedSourceSet = changed,
                    sourceContentOverrides = mapOf("File0.java" to "changed"),
                ),
                store,
            )

        assertTrue(changed.iterations <= 2, "Changed identities should not be scanned per file")
        val hashes =
            store
                .prefixScan("file:")
                .map { it.second as FileHashRecord }
                .associate { (it.originId to it.relativePath) to it.contentHash }
        assertEquals(sources.size, hashes.size)
        assertEquals(FileHashProducer.contentHash("changed"), hashes["workspace" to "File0.java"])
        assertEquals(originalHash, hashes["nested" to "File0.java"])
        assertEquals(originalHash, hashes["workspace" to "File1.java"])
    }
}
