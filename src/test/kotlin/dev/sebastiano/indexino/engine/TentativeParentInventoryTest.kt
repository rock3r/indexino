package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.producer.FileHashProducer
import dev.sebastiano.indexino.producer.IndexedSource
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue

class TentativeParentInventoryTest {
    @Test
    fun `hashes same-parent siblings even when size and mtime are restored`() {
        assumeTrue("unix" in FileSystems.getDefault().supportedFileAttributeViews())
        val root = createTempDirectory("indexino-parent-candidate-").toRealPath()
        try {
            val parent = Files.createDirectory(root.resolve("sources"))
            val a = parent.resolve("A.kt")
            val b = parent.resolve("B.kt")
            val c = parent.resolve("C.kt")
            val temporary = parent.resolve("atomic.tmp")
            val outside = Files.createDirectories(root.resolve("other")).resolve("Outside.kt")
            Files.writeString(a, "class OldA")
            Files.writeString(b, "class OldB")
            Files.writeString(c, "class Unchanged")
            Files.writeString(outside, "class Outside")
            val sources =
                listOf(
                    IndexedSource.workspace(root, "sources/A.kt"),
                    IndexedSource.workspace(root, "sources/B.kt"),
                    IndexedSource.workspace(root, "sources/C.kt"),
                    IndexedSource.workspace(root, "other/Outside.kt"),
                )
            val hashes = sources.associateWith { source ->
                FileHashProducer.contentHash(Files.readAllBytes(root.resolve(source.path)))
            }
            val baseline = checkNotNull(TentativeParentInventory.capture(parent, sources))
            val originalMtime = Files.getLastModifiedTime(b)
            Files.writeString(b, "class NewB")
            Files.setLastModifiedTime(b, originalMtime)
            Files.writeString(temporary, "class NewA")
            Files.move(temporary, a, ATOMIC_MOVE, REPLACE_EXISTING)
            assertEquals(setOf(a, b), baseline.compare(hashes, temporary, setOf(a)))
            assertNull(baseline.compare(hashes, temporary, setOf(a, outside)))
            // A callback for an unchanged byte stream can still report a Git metadata change.
            assertEquals(setOf(a, b, c), baseline.compare(hashes, temporary, setOf(a, c)))
            val survivingAlias = root.resolve("alias.kt")
            Files.createLink(survivingAlias, b)
            assertNull(baseline.compare(hashes, temporary, setOf(a)))
            Files.delete(survivingAlias)
            Files.writeString(parent.resolve("New.kt"), "class New")
            assertNull(baseline.compare(hashes, temporary, setOf(a)))
            Files.delete(parent.resolve("New.kt"))
            Files.write(b, ByteArray(TentativeParentInventory.MAX_HASH_BYTES.toInt() + 1))
            assertNull(baseline.compare(hashes, temporary, setOf(a)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `linked child or parent cannot reuse a bounded parent inventory`() {
        assumeTrue("unix" in FileSystems.getDefault().supportedFileAttributeViews())
        val root = createTempDirectory("indexino-parent-link-").toRealPath()
        try {
            val parent = Files.createDirectory(root.resolve("sources"))
            val child = parent.resolve("A.kt")
            val target = root.resolve("target.kt")
            Files.writeString(target, "class Linked")
            Files.createSymbolicLink(child, target)
            val sources = listOf(IndexedSource.workspace(root, "sources/A.kt"))
            assertNull(TentativeParentInventory.capture(parent, sources))

            Files.delete(child)
            Files.writeString(child, "class Original")
            val baseline = checkNotNull(TentativeParentInventory.capture(parent, sources))
            val moved = root.resolve("moved")
            Files.move(parent, moved)
            Files.createSymbolicLink(parent, moved)
            assertNull(
                baseline.compare(
                    mapOf(sources.single() to FileHashProducer.contentHash("class Original")),
                    parent.resolve("atomic.tmp"),
                    setOf(child),
                )
            )
            assertNull(TentativeParentInventory.capture(parent, sources))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
