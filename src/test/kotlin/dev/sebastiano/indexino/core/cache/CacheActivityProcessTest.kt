package dev.sebastiano.indexino.core.cache

import dev.sebastiano.indexino.cli.CacheMaintenance
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

internal class CacheActivityProcessTest {
    @TempDir lateinit var root: Path

    @Test
    fun `another process retains GC exclusion after this process releases its lease`() {
        val cache = root.resolve("cache")
        val ready = root.resolve("ready")
        val output = root.resolve("child.log")
        val pack = ContentAddressedPackCache(cache).packPath("a".repeat(64))
        Files.createDirectories(pack.parent)
        Files.writeString(pack, "unreferenced")
        val local = CacheActivityLock.acquire(cache)
        val process =
            ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    CacheActivityProcessProbe::class.java.name,
                    cache.toString(),
                    ready.toString(),
                )
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!Files.exists(ready) && process.isAlive && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertTrue(Files.exists(ready), Files.readString(output))
            local.close()
            local.close()
            CacheMaintenance.gc(cache)
            assertTrue(Files.exists(pack), "The child lease must independently exclude GC")
            process.outputStream.close()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Child failed to release its lease")
            assertEquals(0, process.exitValue(), Files.readString(output))
            CacheMaintenance.gc(cache)
            assertFalse(Files.exists(pack), "Process exit must release the OS lock")
        } finally {
            local.close()
            process.outputStream.close()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Child cleanup failed")
            }
        }
    }
}

internal object CacheActivityProcessProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        CacheActivityLock.acquire(Path.of(args[0])).use {
            Files.writeString(Path.of(args[1]), "ready")
            System.`in`.read()
        }
    }
}
