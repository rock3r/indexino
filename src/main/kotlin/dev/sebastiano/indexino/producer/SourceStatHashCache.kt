package dev.sebastiano.indexino.producer

import dev.sebastiano.indexino.core.fs.FileStatReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Persistent `(originId, path, filesystem stat) -> content hash` index for source capture.
 *
 * A hit proves the file's size, modification time, change time and file identity are unchanged
 * since the recorded hash. A stat change always forces a re-read and re-hash. Entries observed
 * within the filesystem timestamp granularity window are never trusted, so a same-tick edit cannot
 * reuse a stale hash.
 */
internal class SourceStatHashCache
private constructor(
    private val file: Path?,
    private val racyWindowNanos: Long = RACY_WINDOW_NANOS,
) {
    private val entries = ConcurrentHashMap<SourceIdentity, Entry>()
    @Volatile private var dirty = false

    internal class SourceStat(
        val size: Long,
        val modifiedNanos: Long,
        val changedNanos: Long,
        val fileKey: String?,
        val observedAtNanos: Long,
    ) {
        fun sameMetadata(other: SourceStat): Boolean =
            size == other.size &&
                modifiedNanos == other.modifiedNanos &&
                changedNanos == other.changedNanos &&
                fileKey == other.fileKey
    }

    private class Entry(val stat: SourceStat, val hash: String)

    private data class SourceIdentity(val originId: String, val path: String)

    /** Metadata of [source]'s current file, or null when it cannot be read. */
    fun stat(source: IndexedSource): SourceStat? = statOf(source.originRoot.resolve(source.path))

    /** The recorded hash when [stat] matches a trusted entry; null forces a read. */
    fun hashIfUnchanged(source: IndexedSource, stat: SourceStat): String? {
        val entry = entries[SourceIdentity(source.originId, source.path)] ?: return null
        if (!entry.stat.sameMetadata(stat)) return null
        if (!entry.isTrusted()) return null
        return entry.hash
    }

    fun record(source: IndexedSource, stat: SourceStat, hash: String) {
        entries[SourceIdentity(source.originId, source.path)] = Entry(stat, hash)
        dirty = true
    }

    /** Best-effort atomic persistence. Cache failures never fail a refresh. */
    fun flush() {
        val destination = file ?: return
        if (!dirty) return
        runCatching {
                destination.parent?.let(Files::createDirectories)
                val temporary =
                    Files.createTempFile(
                        destination.parent,
                        destination.fileName.toString(),
                        ".tmp",
                    )
                try {
                    Files.newBufferedWriter(temporary).use { writer ->
                        writer.appendLine(FORMAT_HEADER)
                        entries.entries
                            .sortedWith(
                                compareBy<Map.Entry<SourceIdentity, Entry>> { it.key.originId }
                                    .thenBy { it.key.path }
                            )
                            .forEach { (identity, entry) ->
                                writer.appendLine(
                                    listOf(
                                            identity.originId,
                                            identity.path,
                                            entry.stat.size.toString(),
                                            entry.stat.modifiedNanos.toString(),
                                            entry.stat.changedNanos.toString(),
                                            entry.stat.fileKey.orEmpty(),
                                            entry.hash,
                                            entry.stat.observedAtNanos.toString(),
                                        )
                                        .joinToString("\t") { encode(it) }
                                )
                            }
                    }
                    runCatching {
                            Files.move(
                                temporary,
                                destination,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        }
                        .getOrElse {
                            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
                        }
                } finally {
                    Files.deleteIfExists(temporary)
                }
                dirty = false
            }
            .onFailure { /* Keep in-memory hits when persistence is unavailable. */ }
    }

    private fun Entry.isTrusted(): Boolean =
        stat.observedAtNanos - stat.modifiedNanos > racyWindowNanos

    private fun load() {
        val source = file ?: return
        runCatching {
                Files.newBufferedReader(source).useLines { lines ->
                    val iterator = lines.iterator()
                    if (!iterator.hasNext() || iterator.next() != FORMAT_HEADER) return@useLines
                    iterator.forEach { line ->
                        parseEntry(line)?.let { (identity, entry) -> entries[identity] = entry }
                    }
                }
            }
            .onFailure { entries.clear() }
    }

    private fun parseEntry(line: String): Pair<SourceIdentity, Entry>? {
        val fields = line.split('\t').map(::decode)
        if (fields.size != FIELD_COUNT) return null
        val size = fields[2].toLongOrNull() ?: return null
        val modified = fields[3].toLongOrNull() ?: return null
        val changed = fields[4].toLongOrNull() ?: return null
        val observed = fields[7].toLongOrNull() ?: return null
        val hash = fields[6]
        if (!HASH_PATTERN.matches(hash)) return null
        return SourceIdentity(fields[0], fields[1]) to
            Entry(
                SourceStat(
                    size = size,
                    modifiedNanos = modified,
                    changedNanos = changed,
                    fileKey = fields[5].ifEmpty { null },
                    observedAtNanos = observed,
                ),
                hash,
            )
    }

    companion object {
        private const val FORMAT_HEADER = "indexino-source-stat-hash-1"
        private const val FIELD_COUNT = 8
        private const val RACY_WINDOW_NANOS = 2_000_000_000L
        private val HASH_PATTERN = Regex("^sha256:[0-9a-f]{64}$")

        /** An in-memory-only cache, for callers that do not want persistence. */
        fun inMemory(racyWindowNanos: Long = RACY_WINDOW_NANOS): SourceStatHashCache =
            SourceStatHashCache(file = null, racyWindowNanos = racyWindowNanos)

        fun open(file: Path, racyWindowNanos: Long = RACY_WINDOW_NANOS): SourceStatHashCache =
            SourceStatHashCache(file, racyWindowNanos).also(SourceStatHashCache::load)

        internal fun statOf(file: Path): SourceStat? {
            val stat = FileStatReader.read(file) ?: return null
            return SourceStat(
                size = stat.size,
                modifiedNanos = stat.modifiedNanos,
                changedNanos = stat.changedNanos,
                fileKey = stat.fileKey,
                observedAtNanos = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis()),
            )
        }

        private fun encode(value: String): String =
            buildString(value.length) {
                value.forEach { character ->
                    when (character) {
                        '%' -> append("%25")
                        '\t' -> append("%09")
                        '\n' -> append("%0A")
                        '\r' -> append("%0D")
                        else -> append(character)
                    }
                }
            }

        @Suppress("LoopWithTooManyJumpStatements")
        private fun decode(value: String): String =
            buildString(value.length) {
                var index = 0
                while (index < value.length) {
                    if (value[index] == '%' && index + ESCAPE_LENGTH <= value.length) {
                        val escape =
                            value
                                .substring(index + ESCAPE_PREFIX_LENGTH, index + ESCAPE_LENGTH)
                                .uppercase()
                        val decoded =
                            when (escape) {
                                "25" -> '%'
                                "09" -> '\t'
                                "0A" -> '\n'
                                "0D" -> '\r'
                                else -> null
                            }
                        if (decoded != null) {
                            append(decoded)
                            index += ESCAPE_LENGTH
                            continue
                        }
                    }
                    append(value[index])
                    index++
                }
            }

        private const val ESCAPE_PREFIX_LENGTH = 1
        private const val ESCAPE_LENGTH = 3
    }
}
