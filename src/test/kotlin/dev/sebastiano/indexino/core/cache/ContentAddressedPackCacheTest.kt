package dev.sebastiano.indexino.core.cache

import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ContentAddressedPackCacheTest {
    private val tempDirs = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `materialization accepts a competing directory installed before atomic rename`() {
        val root = createTempDirectory("indexino-pack-race-").also(tempDirs::add)
        val source = Files.createDirectory(root.resolve("source"))
        source.resolve("facts.json").writeText("immutable facts")
        val winner = ContentAddressedPackCache(root)
        val contentKey = winner.installDirectory(source)
        val destination = root.resolve("restored")
        val contender =
            ContentAddressedPackCache(root) { from, to ->
                winner.materializeDirectory(contentKey, to)
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
            }

        val outcome = runCatching { contender.materializeDirectory(contentKey, destination) }

        assertTrue(
            outcome.isSuccess,
            "A complete competing copy must win: ${outcome.exceptionOrNull()}",
        )
        assertEquals("immutable facts", Files.readString(destination.resolve("facts.json")))
        Files.list(root).use { paths ->
            assertTrue(paths.noneMatch { it.fileName.toString().startsWith("restored.tmp-") })
        }
    }

    @Test
    fun `materialization propagates move failures without a winning directory`() {
        val root = createTempDirectory("indexino-pack-move-failure-").also(tempDirs::add)
        val source = Files.createDirectory(root.resolve("source"))
        source.resolve("facts.json").writeText("facts")
        val contentKey = ContentAddressedPackCache(root).installDirectory(source)
        for (existingFile in listOf(false, true)) {
            val destination = root.resolve("restored-$existingFile")
            val failure = java.nio.file.AccessDeniedException(destination.toString())
            val cache =
                ContentAddressedPackCache(root) { _, to ->
                    if (existingFile) Files.writeString(to, "not a materialized directory")
                    throw failure
                }

            assertSame(
                failure,
                assertFailsWith<java.nio.file.AccessDeniedException> {
                    cache.materializeDirectory(contentKey, destination)
                },
            )
            if (existingFile) {
                assertEquals("not a materialized directory", Files.readString(destination))
            }
        }
        Files.list(root).use { paths ->
            assertTrue(paths.noneMatch { it.fileName.toString().contains(".tmp-") })
        }
    }

    @Test
    fun `installs directory packs under content key fanout without rewriting duplicates`() {
        val root = createTempDirectory("indexino-pack-cache-").also(tempDirs::add)
        val source = createTempDirectory("indexino-pack-source-").also(tempDirs::add)
        source.resolve("facts.json").writeText("facts")
        Files.createDirectories(source.resolve("nested"))
        source.resolve("nested/meta.txt").writeText("meta")
        val cache = ContentAddressedPackCache(root)

        val first = cache.installDirectory(source)
        val pack =
            root
                .resolve("chunks")
                .resolve(first.take(2))
                .resolve(first.substring(2, 4))
                .resolve(first)
        val timestamp = Files.getLastModifiedTime(pack)
        val second = cache.installDirectory(source)

        assertEquals(first, second)
        assertEquals(timestamp, Files.getLastModifiedTime(pack))
        assertTrue(Files.isRegularFile(pack))
        val restored = root.resolve("restored")
        cache.materializeDirectory(first, restored)
        assertEquals("facts", Files.readString(restored.resolve("facts.json")))
        assertEquals("meta", Files.readString(restored.resolve("nested/meta.txt")))
        source.resolve("facts.json").writeText("updated")
        val updated = cache.installDirectory(source)
        cache.replaceMaterializedDirectory(updated, restored)
        assertEquals("updated", Files.readString(restored.resolve("facts.json")))

        ZipFile(pack.toFile()).use { zip ->
            assertEquals(
                "facts",
                zip.getInputStream(zip.getEntry("facts.json")).bufferedReader().readText(),
            )
            assertEquals(
                "meta",
                zip.getInputStream(zip.getEntry("nested/meta.txt")).bufferedReader().readText(),
            )
        }
    }

    @Test
    fun `replacement rollback failure retains the backup`() {
        val root = createTempDirectory("indexino-pack-cache-").also(tempDirs::add)
        val source = createTempDirectory("indexino-pack-source-").also(tempDirs::add)
        source.resolve("facts.json").writeText("replacement")
        val destination = root.resolve("restored")
        Files.createDirectories(destination)
        destination.resolve("facts.json").writeText("current")
        val contentKey = ContentAddressedPackCache(root).installDirectory(source)
        var moveCount = 0
        val cache =
            ContentAddressedPackCache(root) { from, to ->
                moveCount += 1
                if (moveCount in 3..4) throw IOException("simulated replacement failure")
                Files.move(from, to)
            }

        assertFailsWith<IOException> { cache.replaceMaterializedDirectory(contentKey, destination) }

        Files.list(root).use { paths ->
            assertTrue(paths.anyMatch { it.fileName.toString().startsWith("restored.backup-") })
        }
    }

    @Test
    fun `replacement failure restores the current materialization`() {
        val root = createTempDirectory("indexino-pack-cache-").also(tempDirs::add)
        val source = createTempDirectory("indexino-pack-source-").also(tempDirs::add)
        source.resolve("facts.json").writeText("replacement")
        val destination = root.resolve("restored")
        Files.createDirectories(destination)
        destination.resolve("facts.json").writeText("current")
        val contentKey = ContentAddressedPackCache(root).installDirectory(source)
        var moveCount = 0
        val cache =
            ContentAddressedPackCache(root) { from, to ->
                moveCount += 1
                if (moveCount == 3) throw IOException("simulated replacement failure")
                Files.move(from, to)
            }

        assertFailsWith<IOException> { cache.replaceMaterializedDirectory(contentKey, destination) }

        assertEquals("current", Files.readString(destination.resolve("facts.json")))
    }
}
