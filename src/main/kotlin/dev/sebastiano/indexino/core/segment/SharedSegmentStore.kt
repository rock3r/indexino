package dev.sebastiano.indexino.core.segment

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Reference-counted [MappedSegmentFile] per canonical pack path, mirroring the Xodus
 * [dev.sebastiano.indexino.core.store.SharedReadOnlyStore] discipline: simultaneous snapshots share
 * one mapping and one shared Arena; the last release unmaps the file so Windows can delete or
 * rename it. Segment files carry no exclusive directory lock, so this is an efficiency and
 * unmap-ordering guard rather than a hard ownership requirement.
 */
internal object SharedSegmentStore {
    private class Entry(var file: MappedSegmentFile? = null, var pins: Int = 0)

    private val entries = ConcurrentHashMap<Path, Entry>()

    fun open(packPath: Path): PinnedSegmentFile {
        val canonical = packPath.toAbsolutePath().normalize()
        while (true) {
            val entry = entries.computeIfAbsent(canonical) { Entry() }
            synchronized(entry) {
                // A waiter can hold a retired entry after its last owner released. Retry on the
                // current entry so two mappings never outlive their reference counts.
                if (entries[canonical] !== entry) return@synchronized
                val file =
                    entry.file
                        ?: try {
                            MappedSegmentFile.open(canonical).also { entry.file = it }
                        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
                            entries.remove(canonical, entry)
                            throw failure
                        }
                entry.pins++
                return PinnedSegmentFile(file) { release(canonical, entry) }
            }
        }
    }

    private fun release(canonical: Path, entry: Entry) {
        synchronized(entry) {
            entry.pins--
            if (entry.pins == 0) {
                try {
                    entry.file?.close()
                } finally {
                    entries.remove(canonical, entry)
                }
            }
        }
    }
}

/** One acquired mapping; [release] unmaps only when the last pin drops. */
internal class PinnedSegmentFile
internal constructor(val file: MappedSegmentFile, private val release: () -> Unit) : AutoCloseable {
    override fun close() {
        release()
    }
}
