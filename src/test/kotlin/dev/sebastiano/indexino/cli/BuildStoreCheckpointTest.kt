package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.record.FileHashRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class BuildStoreCheckpointTest {
    @Test
    fun `successful checkpoint removes read-only files without changing adjacent files`(
        @TempDir root: Path
    ) {
        val adjacent =
            Files.createDirectory(root.resolve("outside")).resolve("keep.xd").also {
                it.writeText("outside checkpoint")
            }
        assertTrue(adjacent.toFile().setReadOnly())
        val writable = adjacent.toFile().canWrite()
        try {
            XodusCodeIndexStore.open(root.resolve("store")).use { store ->
                val checkpoint = BuildStoreCheckpoint(store, root.resolve("manifest.json"))
                checkpoint.use {
                    it.run {
                        val log = it.directory.resolve("records/readonly.xd")
                        log.writeText("completed log")
                        assertTrue(log.toFile().setReadOnly())
                        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
                            Files.createSymbolicLink(it.directory.resolve("file-link"), adjacent)
                            Files.createSymbolicLink(
                                it.directory.resolve("directory-link"),
                                adjacent.parent,
                            )
                        }
                    }
                }
                assertFalse(checkpoint.directory.exists())
                assertEquals("outside checkpoint", adjacent.readText())
                assertEquals(writable, adjacent.toFile().canWrite())
            }
        } finally {
            adjacent.toFile().setWritable(true)
        }
    }

    @Test
    fun `checkpoint deletion preserves the filesystem failure and affected path`(
        @TempDir root: Path
    ) {
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"))
        XodusCodeIndexStore.open(root.resolve("store")).use { store ->
            val checkpoint = BuildStoreCheckpoint(store, root.resolve("manifest.json"))
            checkpoint.run {}
            Files.setPosixFilePermissions(
                checkpoint.directory,
                PosixFilePermissions.fromString("r-x------"),
            )
            try {
                assumeTrue(!Files.isWritable(checkpoint.directory))
                val failure = assertFailsWith<FileSystemException> { checkpoint.close() }
                assertEquals(checkpoint.directory.resolve("records").toString(), failure.file)
                assertTrue(checkpoint.directory.exists())
            } finally {
                Files.setPosixFilePermissions(
                    checkpoint.directory,
                    PosixFilePermissions.fromString("rwx------"),
                )
                checkpoint.directory.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `rollback batches writes and restores the final partial batch`(@TempDir root: Path) {
        XodusCodeIndexStore.open(root.resolve("store")).use { actual ->
            val before =
                (0..512).associate { index ->
                    CodeIndexKey.file("File$index.kt", "hash") to
                        FileHashRecord("File$index.kt", "old-$index")
                }
            before.forEach(actual::put)
            var inBatch = false
            var operations = 0
            var unbatched = 0
            val batches = mutableListOf<Int>()
            val store =
                object : CodeIndexStore by actual {
                    override fun put(key: CodeIndexKey, record: CodeIndexRecord) {
                        if (inBatch) operations++ else unbatched++
                        actual.put(key, record)
                    }

                    override fun delete(key: CodeIndexKey) {
                        if (inBatch) operations++ else unbatched++
                        actual.delete(key)
                    }

                    override fun <T> transaction(block: () -> T): T {
                        inBatch = true
                        operations = 0
                        try {
                            return actual.transaction(block)
                        } finally {
                            batches += operations
                            inBatch = false
                        }
                    }
                }
            val failure = IllegalArgumentException("injected failure")
            val checkpoint = BuildStoreCheckpoint(store, root.resolve("manifest.json"))
            val observed =
                assertFailsWith<IllegalArgumentException> {
                    checkpoint.use {
                        it.run {
                            actual.delete(before.keys.last())
                            actual.put(
                                CodeIndexKey.file("Added.kt", "hash"),
                                FileHashRecord("Added.kt", "new"),
                            )
                            throw failure
                        }
                    }
                }
            assertSame(failure, observed)
            assertEquals(before, actual.prefixScan("").toMap())
            assertEquals(0, unbatched, "Every rollback write must participate in a batch")
            assertTrue(batches.any { it > 1 })
            assertTrue(batches.all { it in 1..256 })
            assertEquals(1026, batches.sum())
        }
    }

    @Test
    fun `rollback fits a heap smaller than the decoded store`(@TempDir root: Path) {
        val output = root.resolve("heap-probe.log")
        val process =
            ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx64m",
                    "-cp",
                    System.getProperty("java.class.path"),
                    BuildStoreCheckpointHeapProbe::class.java.name,
                    root.toString(),
                )
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start()
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "Heap probe timed out")
            assertEquals(0, process.exitValue(), output.readText().takeLast(4000))
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
    }

    @Test
    fun `checkpoint streams records and restores overwrites deletions and manifest`(
        @TempDir root: Path
    ) {
        val actual = XodusCodeIndexStore.open(root.resolve("store"))
        try {
            val first = CodeIndexKey.file("First.kt", "first")
            val second = CodeIndexKey.file("Second.kt", "second")
            val added = CodeIndexKey.file("Added.kt", "added")
            val before =
                mapOf(
                    first to FileHashRecord("First.kt", "old"),
                    second to FileHashRecord("Second.kt", "removed"),
                )
            before.forEach(actual::put)
            val store =
                object : CodeIndexStore by actual {
                    override fun prefixScan(
                        prefix: String
                    ): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> =
                        error("Eager whole-store scan is forbidden")
                }
            val manifest = root.resolve("manifest.json").also { it.writeText("old manifest") }
            val failure = IllegalArgumentException("producer failed")
            val checkpoint = BuildStoreCheckpoint(store, manifest)

            val observed =
                assertFailsWith<IllegalArgumentException> {
                    checkpoint.use {
                        it.run {
                            store.put(first, FileHashRecord("First.kt", "changed"))
                            store.delete(second)
                            store.put(added, FileHashRecord("Added.kt", "new"))
                            manifest.writeText("new manifest")
                            throw failure
                        }
                    }
                }

            assertSame(failure, observed)
            assertEquals(before, actual.prefixScan("").toMap())
            assertEquals("old manifest", manifest.readText())
            assertFalse(checkpoint.directory.exists())
        } finally {
            actual.close()
        }
    }

    @Test
    fun `failed restore retains checkpoint and preserves original failure`(@TempDir root: Path) {
        val actual = XodusCodeIndexStore.open(root.resolve("store"))
        try {
            val key = CodeIndexKey.file("Original.kt", "original")
            val original = FileHashRecord("Original.kt", "before")
            actual.put(key, original)
            val rollbackFailure = IllegalStateException("delete failed")
            val store =
                object : CodeIndexStore by actual {
                    override fun delete(key: CodeIndexKey) {
                        throw rollbackFailure
                    }
                }
            val manifest = root.resolve("manifest.json").also { it.writeText("old manifest") }
            val failure = IllegalArgumentException("original failure")
            val checkpoint = BuildStoreCheckpoint(store, manifest)

            val observed =
                assertFailsWith<IllegalArgumentException> {
                    checkpoint.use {
                        it.run {
                            store.put(key, FileHashRecord("Original.kt", "partial"))
                            throw failure
                        }
                    }
                }

            assertSame(failure, observed)
            assertTrue(observed.suppressed.any { it === rollbackFailure })
            assertTrue(checkpoint.directory.exists())
            val backup =
                XodusCodeIndexStore.open(checkpoint.directory.resolve("records"), readOnly = true)
            try {
                assertEquals(original, backup.get(key))
            } finally {
                backup.close()
            }
        } finally {
            actual.close()
        }
    }

    @Test
    fun `cold rollback removes newly written manifest`(@TempDir root: Path) {
        val store = XodusCodeIndexStore.open(root.resolve("store"))
        try {
            val manifest = root.resolve("manifest.json")
            val failure = IllegalArgumentException("cold build failed")
            val checkpoint = BuildStoreCheckpoint(store, manifest)
            val observed =
                assertFailsWith<IllegalArgumentException> {
                    checkpoint.use {
                        it.run {
                            store.put(
                                CodeIndexKey.file("New.kt", "new"),
                                FileHashRecord("New.kt", "new"),
                            )
                            manifest.writeText("partial manifest")
                            throw failure
                        }
                    }
                }
            assertSame(failure, observed)
            assertFalse(store.prefixScan("").any())
            assertFalse(manifest.exists())
            assertFalse(checkpoint.directory.exists())
        } finally {
            store.close()
        }
    }
}

internal object BuildStoreCheckpointHeapProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Path.of(args.single())
        val store = XodusCodeIndexStore.open(root.resolve("large-store"))
        try {
            val payload = "x".repeat(256 * 1024)
            repeat(512) { index ->
                store.put(
                    CodeIndexKey.file("File$index.kt", "hash"),
                    FileHashRecord("File$index.kt", "$index:$payload"),
                )
            }
            val failure = IllegalStateException("injected producer failure")
            val checkpoint = BuildStoreCheckpoint(store, root.resolve("manifest.json"))
            try {
                checkpoint.use {
                    it.run {
                        store.put(
                            CodeIndexKey.file("File0.kt", "hash"),
                            FileHashRecord("File0.kt", "partial"),
                        )
                        store.delete(CodeIndexKey.file("File1.kt", "hash"))
                        throw failure
                    }
                }
            } catch (observed: IllegalStateException) {
                check(observed === failure)
            }
            var count = 0
            store.forEachPrefix("") { _, record ->
                check(record is FileHashRecord)
                val index = record.relativePath.removePrefix("File").removeSuffix(".kt").toInt()
                check(index in 0 until 512)
                check(record.contentHash == "$index:$payload")
                count++
                true
            }
            check(count == 512)
            check(!checkpoint.directory.exists())
        } finally {
            store.close()
        }
    }
}
