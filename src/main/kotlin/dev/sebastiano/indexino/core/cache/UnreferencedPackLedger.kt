package dev.sebastiano.indexino.core.cache

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class UnreferencedPacks(val firstObservedEpochMillis: Map<String, Long> = emptyMap())

/** Persisted under the cache-wide GC lock so a restart cannot shorten a pack's grace period. */
internal class UnreferencedPackLedger(cacheRoot: Path) {
    private val path = cacheRoot.resolve("registry/unreferenced-packs.json")

    fun read(): Map<String, Long> =
        if (Files.isRegularFile(path)) {
            Json.decodeFromString<UnreferencedPacks>(Files.readString(path))
                .firstObservedEpochMillis
        } else {
            emptyMap()
        }

    fun write(firstObservedEpochMillis: Map<String, Long>) {
        Files.createDirectories(path.parent)
        val staging = path.resolveSibling("unreferenced-packs.tmp-${System.nanoTime()}")
        Files.writeString(staging, Json.encodeToString(UnreferencedPacks(firstObservedEpochMillis)))
        try {
            Files.move(
                staging,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(staging, path, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staging)
        }
    }
}
