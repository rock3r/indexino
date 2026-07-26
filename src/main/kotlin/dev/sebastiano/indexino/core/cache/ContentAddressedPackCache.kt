package dev.sebastiano.indexino.core.cache

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Installs immutable analysis packs under the cache-root two-level content-key fanout. */
internal class ContentAddressedPackCache(private val cacheRoot: Path) {
    fun installDirectory(directory: Path, basicFactSchemaVersion: Int = 1): String {
        val entries =
            Files.walk(directory).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .map { path ->
                        directory.relativize(path).toString().replace('\\', '/') to path
                    }
                    .sorted { left, right -> left.first.compareTo(right.first) }
                    .toList()
            }
        val contentKey = contentKey(entries, basicFactSchemaVersion)
        val destination = packPath(contentKey)
        if (Files.isRegularFile(destination)) return contentKey

        Files.createDirectories(destination.parent)
        val staging = destination.resolveSibling("$contentKey.tmp-${UUID.randomUUID()}")
        try {
            ZipOutputStream(Files.newOutputStream(staging)).use { zip ->
                entries.forEach { (relativePath, path) ->
                    zip.putNextEntry(ZipEntry(relativePath))
                    Files.newInputStream(path).use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            try {
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // A concurrent writer installed the same immutable key first.
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                try {
                    Files.move(staging, destination)
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    // A concurrent writer installed the same immutable key first.
                }
            }
        } finally {
            Files.deleteIfExists(staging)
        }
        return contentKey
    }

    @Suppress("NestedBlockDepth")
    fun materializeDirectory(contentKey: String, destination: Path) {
        val pack = packPath(contentKey)
        require(Files.isRegularFile(pack)) { "Content pack does not exist: $contentKey" }
        if (Files.isDirectory(destination)) return
        Files.createDirectories(destination.parent)
        val staging = destination.resolveSibling("${destination.fileName}.tmp-${UUID.randomUUID()}")
        try {
            Files.createDirectories(staging)
            val normalizedDestination = staging.toAbsolutePath().normalize()
            ZipFile(pack.toFile()).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val target = normalizedDestination.resolve(entry.name).normalize()
                    require(target.startsWith(normalizedDestination)) {
                        "Pack entry escapes destination"
                    }
                    if (entry.isDirectory) Files.createDirectories(target)
                    else {
                        Files.createDirectories(target.parent)
                        zip.getInputStream(entry).use { input ->
                            Files.newOutputStream(target).use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
            try {
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                try {
                    Files.move(staging, destination)
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    // Another client materialized the immutable pack first.
                }
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // Another client materialized the immutable pack first.
            }
        } finally {
            if (Files.exists(staging)) staging.toFile().deleteRecursively()
        }
    }

    fun packPath(contentKey: String): Path {
        require(contentKey.length >= FANOUT_PREFIX_LENGTH * 2) { "Content key is too short" }
        return cacheRoot
            .resolve("chunks")
            .resolve(contentKey.take(2))
            .resolve(contentKey.substring(FANOUT_PREFIX_LENGTH, FANOUT_PREFIX_LENGTH * 2))
            .resolve(contentKey)
    }

    private fun contentKey(entries: List<Pair<String, Path>>, basicFactSchemaVersion: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("basic-fact-schema:$basicFactSchemaVersion".toByteArray())
        digest.update(0)
        entries.forEach { (relativePath, path) ->
            digest.update(relativePath.toByteArray())
            digest.update(0)
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.update(0)
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private companion object {
        const val FANOUT_PREFIX_LENGTH: Int = 2
        const val BUFFER_SIZE: Int = 8_192
    }
}
