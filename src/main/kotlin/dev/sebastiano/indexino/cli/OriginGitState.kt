package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.producer.FileHashProducer
import dev.sebastiano.indexino.producer.IndexedSource
import java.nio.file.Path

/**
 * The Git inputs of one origin's working-tree fingerprint and dirty flag, kept in a form that a
 * watcher refresh can update for a few known files without re-walking the repository.
 *
 * [stamp] names every Git input a workspace watcher cannot observe (HEAD and its tree, the owner
 * repository HEAD, effective configuration, the index file identity and ignore/attribute files).
 * [diffSections] and [trackedStatus] are null when the captured output could not be split into
 * unambiguous per-path entries; such a state only supports full recomputation.
 */
internal class OriginGitState(
    val originRoot: Path,
    val stamp: String?,
    val revision: String?,
    val externalExpectedRevision: String?,
    val expectedRevision: String?,
    val diff: String?,
    val diffSections: List<DiffSection>?,
    val trackedStatus: String?,
    val trackedStatusEntries: List<StatusEntry>?,
    val untracked: List<UntrackedEntry>?,
) {
    internal class DiffSection(val path: String, val text: String)

    internal class StatusEntry(val path: String, val line: String)

    internal class UntrackedEntry(val path: String, val contentHash: String?)

    val incremental: Boolean
        get() =
            stamp != null &&
                revision != null &&
                diff != null &&
                diffSections != null &&
                trackedStatusEntries != null &&
                untracked != null

    /**
     * This state after only [hintedPaths] changed in the working tree: their fresh pathspec-limited
     * [hintedSections] and [hintedStatus] replace the captured entries, and untracked hinted files
     * are re-hashed. Membership of untracked files cannot change without a watcher event.
     */
    fun withHintedPaths(
        stamp: String,
        externalExpectedRevision: String?,
        hintedPaths: Set<String>,
        hintedSections: List<DiffSection>,
        hintedStatus: List<StatusEntry>,
    ): OriginGitState {
        val sections =
            (checkNotNull(diffSections).filterNot { it.path in hintedPaths } + hintedSections)
                .sortedWith { first, second -> compareBytes(first.path, second.path) }
        val entries =
            checkNotNull(trackedStatusEntries).filterNot { it.path in hintedPaths } + hintedStatus
        val hashedUntracked =
            checkNotNull(untracked).map { entry ->
                if (entry.path in hintedPaths) {
                    UntrackedEntry(entry.path, hashOrNull(originRoot.resolve(entry.path)))
                } else entry
            }
        return OriginGitState(
            originRoot = originRoot,
            stamp = stamp,
            revision = revision,
            externalExpectedRevision = externalExpectedRevision,
            expectedRevision = expectedRevision,
            diff = sections.joinToString("") { it.text },
            diffSections = sections,
            trackedStatus = entries.joinToString("") { it.line + "\n" },
            trackedStatusEntries = entries,
            untracked = hashedUntracked,
        )
    }

    /** Null means Git could not describe this origin; callers fall back to a filesystem walk. */
    fun workingTreeFingerprint(): String? {
        if (diff == null || untracked == null) return null
        val untrackedFingerprint = untracked.joinToString("\n") { "${it.path}:${it.contentHash}" }
        return FileHashProducer.contentHash("$diff\n$untrackedFingerprint")
    }

    /**
     * Equivalent to non-transitional lines of a full `git status --porcelain`: tracked status comes
     * from `--untracked-files=no`, untracked files from `ls-files --others`, which honours the same
     * standard excludes.
     */
    fun dirty(): Boolean {
        val trackedDirty =
            trackedStatus?.lineSequence()?.filter(String::isNotBlank)?.any { status ->
                !isTransitionalPath(status.drop(PORCELAIN_STATUS_PREFIX_LENGTH).trim())
            } == true
        return trackedDirty || (trackedStatus != null && !untracked.isNullOrEmpty())
    }

    internal companion object {
        private const val PORCELAIN_STATUS_PREFIX_LENGTH = 3
        private const val TRANSITIONAL_INDEX_DIRECTORY = ".indexino"
        private const val DIFF_HEADER = "diff --git "
        private const val SOURCE_PREFIX = "a/"
        // "a/", " b/"
        private const val HEADER_PREFIX_CHARACTERS = 5
        private const val MIN_PRINTABLE = 0x20
        private const val MAX_PRINTABLE = 0x7e

        fun isTransitionalPath(path: String): Boolean =
            path.split('/').any { segment -> segment == TRANSITIONAL_INDEX_DIRECTORY }

        /** A path Git prints verbatim (never C-quoted) in diff headers and porcelain status. */
        fun isVerbatimPath(path: String): Boolean =
            path.isNotEmpty() &&
                path.all { it.code in MIN_PRINTABLE..MAX_PRINTABLE && it != '"' && it != '\\' } &&
                !path.startsWith(' ') &&
                !path.endsWith(' ')

        fun untrackedEntries(root: Path, paths: List<String>): List<UntrackedEntry> =
            paths.sorted().map { UntrackedEntry(it, hashOrNull(root.resolve(it))) }

        fun hashOrNull(file: Path): String? =
            runCatching { FileHashProducer.contentHash(java.nio.file.Files.readAllBytes(file)) }
                .getOrNull()

        /**
         * Splits `git diff` output into per-path sections, or returns null unless every section has
         * an unrenamed `a/<path> b/<path>` header with a verbatim path, in Git's byte order.
         * Content lines always carry a one-character prefix and base85 lines contain no space, so a
         * line starting with the header prefix always begins a new section.
         */
        fun diffSections(diff: String): List<DiffSection>? {
            if (diff.isEmpty()) return emptyList()
            if (!diff.startsWith(DIFF_HEADER) || !diff.endsWith("\n")) return null
            val starts = buildList {
                add(0)
                var index = diff.indexOf("\n$DIFF_HEADER")
                while (index >= 0) {
                    add(index + 1)
                    index = diff.indexOf("\n$DIFF_HEADER", index + 1)
                }
            }
            val sections = starts.mapIndexed { position, start ->
                val end = starts.getOrNull(position + 1) ?: diff.length
                val text = diff.substring(start, end)
                val path = headerPath(text.substringBefore('\n')) ?: return null
                DiffSection(path, text)
            }
            val ordered =
                sections.zipWithNext().all { (first, second) ->
                    compareBytes(first.path, second.path) < 0
                }
            return sections.takeIf { ordered }
        }

        private fun headerPath(header: String): String? {
            // "a/" + path + " b/" + path
            val rest = header.removePrefix(DIFF_HEADER)
            val pathLength = (rest.length - HEADER_PREFIX_CHARACTERS) / 2
            if (pathLength < 1) return null
            val path = rest.substring(SOURCE_PREFIX.length, SOURCE_PREFIX.length + pathLength)
            return path.takeIf { rest == "$SOURCE_PREFIX$path b/$path" && isVerbatimPath(it) }
        }

        /** Per-path porcelain entries, or null for renames, copies or quoted paths. */
        fun statusEntries(status: String): List<StatusEntry>? {
            val entries = mutableListOf<StatusEntry>()
            for (line in status.lines().filter(String::isNotBlank)) {
                if (line.length <= PORCELAIN_STATUS_PREFIX_LENGTH || line[2] != ' ') return null
                if (line[0] in "RC" || line[1] in "RC") return null
                val path = line.substring(PORCELAIN_STATUS_PREFIX_LENGTH)
                if (!isVerbatimPath(path)) return null
                entries += StatusEntry(path, line)
            }
            return entries
        }

        fun compareBytes(first: String, second: String): Int =
            java.util.Arrays.compareUnsigned(
                first.toByteArray(Charsets.UTF_8),
                second.toByteArray(Charsets.UTF_8),
            )
    }
}

/** Resolution result plus the Git state a watcher refresh may update incrementally. */
internal class OriginResolution(
    val origins: List<dev.sebastiano.indexino.core.manifest.IndexManifestOrigin>,
    val states: Map<String, OriginGitState>,
    /** Origins updated from [OriginIncrementalHint.previous] without whole-repository Git walks. */
    val incremental: Int,
)

/**
 * Evidence that only [hintedSources] changed in the working tree since [previous] was captured: a
 * recursive watcher rooted at [recursiveWatchRoot] saw no other change and never overflowed.
 */
internal class OriginIncrementalHint(
    val previous: Map<String, OriginGitState>,
    val hintedSources: List<IndexedSource>,
    val recursiveWatchRoot: Path,
)
