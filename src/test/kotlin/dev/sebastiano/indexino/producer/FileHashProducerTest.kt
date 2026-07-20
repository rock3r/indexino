package dev.sebastiano.indexino.producer

import dev.sebastiano.indexino.core.record.FileHashRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
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
}
