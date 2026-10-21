package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.producer.FileHashProducer
import dev.sebastiano.indexino.producer.IndexedSource
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/** Bounded inventory; it cannot certify aliases outside this directory. */
internal class TentativeParentInventory
private constructor(
    val parent: Path,
    private val identity: Any,
    private val children: Map<String, Boolean>,
    private val indexed: Map<Path, IndexedSource>,
) {
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun compare(hashes: Map<IndexedSource, String>, unknown: Path, known: Set<Path>): Set<Path>? {
        val start = System.nanoTime()
        if (known.isEmpty() || known.any { it.parent != parent || it !in indexed }) return null
        if (
            unknown.parent != parent ||
                unknown.fileName.toString() in children ||
                Files.exists(unknown, NOFOLLOW_LINKS)
        )
            return null
        val first = inventory(parent) ?: return null
        if (first.first != identity || first.second != children) return null
        var bytes = 0L
        val changed = linkedSetOf<Path>()
        for ((path, source) in indexed) {
            val attributes =
                runCatching {
                        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    }
                    .getOrNull() ?: return null
            if (
                !attributes.isRegularFile ||
                    hasSharedInode(path) ||
                    attributes.size() > MAX_HASH_BYTES - bytes
            )
                return null
            val remaining = MAX_HASH_BYTES - bytes
            val contents =
                runCatching {
                        Files.newInputStream(path).use { it.readNBytes((remaining + 1).toInt()) }
                    }
                    .getOrNull() ?: return null
            bytes += contents.size
            if (bytes > MAX_HASH_BYTES) return null
            val after =
                runCatching {
                        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    }
                    .getOrNull() ?: return null
            if (
                attributes.fileKey() != after.fileKey() ||
                    attributes.size() != after.size() ||
                    hasSharedInode(path)
            )
                return null
            val baseline = hashes[source] ?: return null
            if (FileHashProducer.contentHash(contents) != baseline) changed.add(path)
            if (System.nanoTime() - start > MAX_SCAN_NANOS) return null
        }
        val last = inventory(parent) ?: return null
        if (first != last || System.nanoTime() - start > MAX_SCAN_NANOS) return null
        if (known.none { it in changed }) return null
        return changed + known
    }

    internal companion object {
        // The retained IntelliJ plan has q95=26 children and q95=124 KiB of immediate file bytes.
        const val MAX_CHILDREN = 64
        const val MAX_HASH_BYTES = 256 * 1024L
        val MAX_SCAN_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(25)

        fun capture(parent: Path, sources: List<IndexedSource>): TentativeParentInventory? {
            val snapshot = inventory(parent) ?: return null
            val indexed =
                sources
                    .asSequence()
                    .map { source -> source.originRoot.resolve(source.path).normalize() to source }
                    .filter { it.first.parent == parent }
                    .toMap()
            if (
                indexed.isEmpty() ||
                    indexed.size > MAX_CHILDREN ||
                    indexed.keys.any(::hasSharedInode)
            )
                return null
            return TentativeParentInventory(parent, snapshot.first, snapshot.second, indexed)
        }

        private fun hasSharedInode(path: Path): Boolean =
            runCatching {
                    (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() !=
                        1L
                }
                .getOrDefault(true)

        private fun inventory(parent: Path): Pair<Any, Map<String, Boolean>>? {
            val start = System.nanoTime()
            return runCatching {
                    val attributes =
                        Files.readAttributes(
                            parent,
                            BasicFileAttributes::class.java,
                            NOFOLLOW_LINKS,
                        )
                    if (!attributes.isDirectory) return null
                    val identity = attributes.fileKey() ?: return null
                    val children = linkedMapOf<String, Boolean>()
                    Files.newDirectoryStream(parent).use { stream ->
                        for (entry in stream) {
                            if (children.size >= MAX_CHILDREN) return null
                            val child =
                                Files.readAttributes(
                                    entry,
                                    BasicFileAttributes::class.java,
                                    NOFOLLOW_LINKS,
                                )
                            if (!child.isDirectory && !child.isRegularFile) return null
                            children[entry.fileName.toString()] = child.isRegularFile
                            if (System.nanoTime() - start > MAX_SCAN_NANOS) return null
                        }
                    }
                    if (
                        Files.readAttributes(
                                parent,
                                BasicFileAttributes::class.java,
                                NOFOLLOW_LINKS,
                            )
                            .fileKey() != identity
                    )
                        return null
                    identity to children
                }
                .getOrNull()
        }
    }
}
