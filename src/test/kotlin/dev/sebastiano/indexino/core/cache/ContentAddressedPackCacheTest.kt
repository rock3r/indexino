package dev.sebastiano.indexino.core.cache

import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentAddressedPackCacheTest {
    private val tempDirs = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
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
}
