package dev.sebastiano.indexino.engine

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import kotlin.io.path.invariantSeparatorsPathString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A stat-only proof that an origin's Git metadata and complete working tree are unchanged. */
internal class OriginStateStamp(val value: String, val reusable: Boolean)

/**
 * Persistent per-origin [OriginGitState] cache.
 *
 * An entry is reusable only while `.git/HEAD`, the ref it names, `packed-refs`, the index, the
 * effective Git configuration and every working-tree file's stat agree with the recorded stamp.
 * That covers tracked and untracked changes without spawning Git, including changes outside the
 * indexed source closure. Entries touched inside the filesystem timestamp granularity window are
 * not stored, so a same-tick edit cannot be mistaken for an unchanged tree.
 */
@Suppress("TooManyFunctions")
internal class OriginStateCache(
    private val directory: Path,
    private val cacheRoot: Path? = null,
    private val racyWindowNanos: Long = RACY_WINDOW_NANOS,
    /** Test-only XDG config home; production resolves `$XDG_CONFIG_HOME` or `~/.config`. */
    private val xdgConfigHomeOverride: Path? = null,
) {
    private val home: Path = Path.of(System.getProperty("user.home"))
    private val xdgConfigHome: Path =
        xdgConfigHomeOverride
            ?: System.getenv("XDG_CONFIG_HOME")?.takeIf(String::isNotBlank)?.let(Path::of)
            ?: home.resolve(".config")

    /**
     * Stat-only evidence for [originRoot]. Null when the repository shape or configuration makes
     * the working tree undecidable without Git.
     */
    fun stamp(originRoot: Path, externalExpectedRevision: String?): OriginStateStamp? {
        val canonicalRoot = runCatching { originRoot.toRealPath() }.getOrNull() ?: return null
        val layout = gitLayout(canonicalRoot, externalExpectedRevision) ?: return null
        val metadata = gitMetadata(layout) ?: return null
        val worktree = worktreeDigest(canonicalRoot) ?: return null
        val observedAtNanos = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis())
        val value =
            digest(
                listOf(FORMAT_VERSION.toString(), metadata, worktree.digest).joinToString("\u0000")
            )
        val reusable =
            worktree.newestChangeNanos <= observedAtNanos &&
                observedAtNanos - worktree.newestChangeNanos > racyWindowNanos
        return OriginStateStamp(value, reusable)
    }

    fun lookup(
        originId: String,
        originRoot: Path,
        externalExpectedRevision: String?,
        stamp: OriginStateStamp,
    ): OriginGitState? {
        val file = entryFile(originId, originRoot, externalExpectedRevision) ?: return null
        val entry = readEntry(file) ?: return null
        val canonicalRoot =
            runCatching { originRoot.toRealPath().toString() }.getOrNull() ?: return null
        if (
            entry.formatVersion != FORMAT_VERSION ||
                entry.originId != originId ||
                entry.originRoot != canonicalRoot ||
                entry.externalExpectedRevision != externalExpectedRevision ||
                entry.stamp != stamp.value
        ) {
            return null
        }
        return entry.toState() ?: return null
    }

    fun store(
        originId: String,
        originRoot: Path,
        externalExpectedRevision: String?,
        stamp: OriginStateStamp,
        state: OriginGitState,
    ) {
        if (!stamp.reusable) return
        val file = entryFile(originId, originRoot, externalExpectedRevision) ?: return
        val canonicalRoot = runCatching { originRoot.toRealPath().toString() }.getOrNull() ?: return
        val entry =
            StoredOriginState.from(originId, canonicalRoot, externalExpectedRevision, stamp, state)
                ?: return
        runCatching {
                Files.createDirectories(file.parent)
                val temporary = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
                try {
                    Files.writeString(
                        temporary,
                        json.encodeToString(StoredOriginState.serializer(), entry),
                    )
                    runCatching {
                            Files.move(
                                temporary,
                                file,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        }
                        .getOrElse {
                            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
                        }
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
            .onFailure { /* A cache write is never allowed to fail a refresh. */ }
    }

    private fun entryFile(
        originId: String,
        originRoot: Path,
        externalExpectedRevision: String?,
    ): Path? {
        val canonicalRoot =
            runCatching { originRoot.toRealPath().toString() }.getOrNull() ?: return null
        val identity =
            listOf(originId, canonicalRoot, externalExpectedRevision.orEmpty())
                .joinToString("\u0000")
        return directory.resolve(digest(identity) + ".json")
    }

    private fun readEntry(file: Path): StoredOriginState? =
        runCatching {
                json.decodeFromString(StoredOriginState.serializer(), Files.readString(file))
            }
            .getOrNull()

    private class GitLayout(
        val originRoot: Path,
        val gitDir: Path,
        val commonDir: Path,
        val gitFileContent: String?,
        val externalExpectedRevision: String?,
    )

    private class ConfigSnapshot(
        val fingerprint: String,
        val excludesFile: Path?,
        val attributesFile: Path?,
    )

    private fun gitLayout(originRoot: Path, externalExpectedRevision: String?): GitLayout? {
        if (UNSUPPORTED_ENVIRONMENT_VARIABLES.any { System.getenv(it) != null }) return null
        val dotGit = originRoot.resolve(GIT)
        return runCatching {
                when {
                    Files.isDirectory(dotGit) ->
                        GitLayout(originRoot, dotGit, dotGit, null, externalExpectedRevision)
                    Files.isRegularFile(dotGit) -> {
                        val content = Files.readString(dotGit).trim()
                        if (!content.startsWith(GITDIR_PREFIX)) return null
                        val listed = content.removePrefix(GITDIR_PREFIX).trim()
                        if (listed.isEmpty()) return null
                        val gitDirPath = Path.of(listed)
                        val gitDir =
                            (if (gitDirPath.isAbsolute) gitDirPath
                                else originRoot.resolve(gitDirPath))
                                .normalize()
                                .toRealPath()
                        val commonFile = gitDir.resolve("commondir")
                        if (Files.isRegularFile(commonFile)) {
                            val common = Files.readString(commonFile).trim()
                            if (common.isEmpty()) return null
                            val commonPath = Path.of(common)
                            GitLayout(
                                originRoot = originRoot,
                                gitDir = gitDir,
                                commonDir =
                                    (if (commonPath.isAbsolute) commonPath
                                        else gitDir.resolve(commonPath))
                                        .normalize()
                                        .toRealPath(),
                                gitFileContent = content,
                                externalExpectedRevision = externalExpectedRevision,
                            )
                        } else {
                            // A gitlink without a common dir is a submodule. Its owner's HEAD
                            // decides expectedRevision unless the caller supplied one.
                            if (externalExpectedRevision == null) null
                            else
                                GitLayout(
                                    originRoot,
                                    gitDir,
                                    gitDir,
                                    content,
                                    externalExpectedRevision,
                                )
                        }
                    }
                    else -> null
                }
            }
            .getOrNull()
    }

    private fun gitMetadata(layout: GitLayout): String? =
        runCatching {
                val headText =
                    Files.readString(layout.gitDir.resolve("HEAD")).trim().ifEmpty {
                        return null
                    }
                val ref =
                    when {
                        headText.startsWith(HEAD_REF_PREFIX) ->
                            headText.removePrefix(HEAD_REF_PREFIX).trim()
                        DETACHED_HEAD.matches(headText) -> null
                        else -> return null
                    }
                if (ref != null && !isSafeRelativePath(ref)) return null
                val head = stableContent(layout.gitDir.resolve("HEAD"))
                val looseRef = ref?.let { stableContent(layout.commonDir.resolve(it)) } ?: "unused"
                val packedRefs = stableContent(layout.commonDir.resolve("packed-refs"))
                val index = statOnly(layout.gitDir.resolve("index"))
                val config = configFingerprint(layout) ?: return null
                val globalIgnore = config.excludesFile ?: xdgConfigHome.resolve("git/ignore")
                val globalAttributes =
                    config.attributesFile ?: xdgConfigHome.resolve("git/attributes")
                val info =
                    listOf(
                            layout.commonDir.resolve("info/exclude"),
                            layout.commonDir.resolve("info/attributes"),
                            layout.commonDir.resolve("info/grafts"),
                            layout.gitDir.resolve("info/sparse-checkout"),
                            layout.commonDir.resolve("shallow"),
                            globalIgnore,
                            globalAttributes,
                        )
                        .joinToString("\u0000") { stableContent(it) }
                listOf(
                        "head=$head",
                        "ref=$looseRef",
                        "packed=$packedRefs",
                        "index=$index",
                        "config=${config.fingerprint}",
                        "info=$info",
                        "gitfile=${layout.gitFileContent.orEmpty()}",
                        "commondir=${stableContent(layout.gitDir.resolve("commondir"))}",
                        "nested=${nestedModulesDigest(layout.commonDir) ?: return null}",
                    )
                    .joinToString("\u0000")
            }
            .getOrNull()

    private class ConfiguredPath(val path: Path?, val ambiguous: Boolean = false)

    private fun configFingerprint(layout: GitLayout): ConfigSnapshot? {
        val envGlobal =
            if (xdgConfigHomeOverride == null) {
                System.getenv("GIT_CONFIG_GLOBAL")?.takeIf(String::isNotBlank)?.let(Path::of)
            } else null
        val globalPaths =
            if (envGlobal != null) listOf(envGlobal)
            else
                buildList {
                    if (xdgConfigHomeOverride == null) add(home.resolve(".gitconfig"))
                    add(xdgConfigHome.resolve("git/config"))
                }
        val systemPath =
            if (xdgConfigHomeOverride == null && System.getenv("GIT_CONFIG_NOSYSTEM") != "1") {
                System.getenv("GIT_CONFIG_SYSTEM")?.takeIf(String::isNotBlank)?.let(Path::of)
                    ?: systemConfigPath()
            } else null
        val repoConfig = layout.commonDir.resolve("config")
        val worktreeConfig = layout.gitDir.resolve("config.worktree")
        val paths =
            buildList {
                    add(repoConfig)
                    add(worktreeConfig)
                    addAll(globalPaths)
                    systemPath?.let(::add)
                }
                .distinct()
        // Ascending precedence; the last value of a key in this order is the effective one.
        val precedence =
            buildList {
                    systemPath?.let(::add)
                    addAll(globalPaths)
                    add(repoConfig)
                    add(worktreeConfig)
                }
                .distinct()
        return runCatching {
                val texts = paths.associateWith { path ->
                    if (Files.isRegularFile(path)) Files.readString(path) else null
                }
                texts.values.filterNotNull().forEach { text ->
                    if (!supportedConfig(text)) return null
                }
                val excludes = effectiveValue(texts, precedence, "excludesfile", layout.originRoot)
                val attributes =
                    effectiveValue(texts, precedence, "attributesfile", layout.originRoot)
                if (excludes.ambiguous || attributes.ambiguous) return null
                val fingerprint = buildString {
                    paths.forEach { path ->
                        append(stableContent(path))
                        append('\u0000')
                    }
                }
                ConfigSnapshot(fingerprint, excludes.path, attributes.path)
            }
            .getOrNull()
    }

    private fun effectiveValue(
        texts: Map<Path, String?>,
        precedence: List<Path>,
        key: String,
        originRoot: Path,
    ): ConfiguredPath {
        var raw: String? = null
        precedence.forEach { path ->
            texts[path]?.let { text -> configValue(text, "core", key)?.let { raw = it } }
        }
        val value = raw ?: return ConfiguredPath(null)
        if (value.contains('#') || value.contains(';'))
            return ConfiguredPath(null, ambiguous = true)
        val path = resolveConfiguredPath(value, originRoot)
        return ConfiguredPath(path, ambiguous = path == null)
    }

    /** Reads `[section] key = value` from a raw config file; later values win. */
    private fun configValue(text: String, section: String, key: String): String? {
        var currentSection: String? = null
        var result: String? = null
        text.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith(';')) {
                return@forEach
            }
            if (trimmed.startsWith('[')) {
                currentSection =
                    trimmed
                        .removePrefix("[")
                        .substringBefore(']')
                        .substringBefore('"')
                        .trim()
                        .lowercase()
            } else if (currentSection == section) {
                val separator = trimmed.indexOf('=')
                val name =
                    (if (separator >= 0) trimmed.substring(0, separator) else trimmed)
                        .trim()
                        .lowercase()
                if (name == key) {
                    val value =
                        if (separator >= 0) trimmed.substring(separator + 1).trim()
                        else trimmed.substringAfter(' ', "").trim()
                    if (value.isNotEmpty()) result = value.removeSurrounding("\"")
                }
            }
        }
        return result
    }

    private fun resolveConfiguredPath(value: String, originRoot: Path): Path? =
        runCatching {
                when {
                    value == "~" -> home
                    value.startsWith("~/") -> home.resolve(value.drop(2))
                    else -> {
                        val path = Path.of(value)
                        if (path.isAbsolute) path else originRoot.resolve(path).normalize()
                    }
                }
            }
            .getOrNull()

    private fun supportedConfig(text: String): Boolean {
        // Both `include.path = ...` and the section form `[include] path = ...` can pull in a file
        // whose changes this stamp cannot observe. `[includeIf ...]` is conditional, so refuse it
        // as well rather than evaluate conditions here.
        if (INCLUDE_SECTION.containsMatchIn(text)) return false
        val lower = text.lowercase()
        return UNSUPPORTED_CONFIG_KEYS.none { lower.contains(it) }
    }

    /**
     * A tracked submodule's HEAD advances without changing the parent's working-tree files, and its
     * refs/index live under the excluded `.git/modules` tree. Fingerprint those metadata files so
     * the parent's diff/status cannot be reused across a gitlink move.
     */
    private fun nestedModulesDigest(commonDir: Path): String? {
        val modules = commonDir.resolve("modules")
        if (!Files.isDirectory(modules)) return "none"
        return runCatching {
                val entries = mutableListOf<String>()
                Files.walkFileTree(
                    modules,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(
                            directory: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            if (Thread.currentThread().isInterrupted)
                                throw InterruptedException("Origin state stamp stopped")
                            if (
                                directory != modules &&
                                    directory.fileName.toString() in NESTED_SKIPPED_DIRECTORIES
                            ) {
                                return FileVisitResult.SKIP_SUBTREE
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(
                            file: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            if (Thread.currentThread().isInterrupted)
                                throw InterruptedException("Origin state stamp stopped")
                            val relative = modules.relativize(file).invariantSeparatorsPathString
                            val segments = relative.split('/')
                            val name = segments.last()
                            val entry =
                                when {
                                    name == NESTED_INDEX -> "$relative:${statOnly(file)}"
                                    name in NESTED_METADATA_FILES ||
                                        NESTED_REFS in segments ||
                                        NESTED_INFO in segments ->
                                        "$relative:${stableContent(file)}"
                                    else -> null
                                }
                            if (entry != null) entries += entry
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
                digest(entries.sorted().joinToString("\n"))
            }
            .getOrNull()
    }

    private fun systemConfigPath(): Path =
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            Path.of(System.getenv("PROGRAMDATA").orEmpty(), "Git", "config")
        } else {
            Path.of("/etc/gitconfig")
        }

    private class WorktreeDigest(val digest: String, val newestChangeNanos: Long)

    private fun worktreeDigest(originRoot: Path): WorktreeDigest? {
        val skip =
            cacheRoot
                ?.let { runCatching { it.toRealPath() }.getOrNull() }
                ?.takeIf { it.startsWith(originRoot) }
        val lines = mutableListOf<String>()
        var newest = 0L
        return runCatching {
                Files.walkFileTree(
                    originRoot,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(
                            directory: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            if (Thread.currentThread().isInterrupted)
                                throw InterruptedException("Origin state stamp stopped")
                            if (directory != originRoot && directory.fileName.toString() == GIT) {
                                return FileVisitResult.SKIP_SUBTREE
                            }
                            if (skip != null && directory == skip) {
                                return FileVisitResult.SKIP_SUBTREE
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(
                            file: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            if (Thread.currentThread().isInterrupted)
                                throw InterruptedException("Origin state stamp stopped")
                            val relative = originRoot.relativize(file).invariantSeparatorsPathString
                            val change = changeTimeNanos(file, attributes)
                            // Git's change evidence is modification time; ctime is compared for
                            // equality but cannot indicate a racy write when it never advances
                            // (Windows keeps creation time here).
                            newest =
                                maxOf(
                                    newest,
                                    attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                                )
                            val target =
                                if (attributes.isSymbolicLink) {
                                    digest(
                                        runCatching { Files.readSymbolicLink(file).toString() }
                                            .getOrElse { "unreadable" }
                                    )
                                } else ""
                            lines +=
                                "$relative\t$change\t" +
                                    "${attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS)}\t" +
                                    "${attributes.size()}\t${attributes.fileKey()}\t$target"
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(
                            file: Path,
                            exception: java.io.IOException,
                        ): FileVisitResult {
                            lines +=
                                "${originRoot.relativize(file).invariantSeparatorsPathString}\tunreadable"
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
                WorktreeDigest(digest(lines.sorted().joinToString("\n")), newest)
            }
            .getOrNull()
    }

    private fun stableContent(file: Path): String {
        if (!Files.isRegularFile(file)) return "absent"
        val bytes = Files.readAllBytes(file)
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
        return "present:${digest(bytes)}:${attributes.size()}:" +
            "${attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS)}:" +
            "${changeTimeNanos(file, attributes)}:${attributes.fileKey()}"
    }

    private fun statOnly(file: Path): String =
        runCatching {
                if (!Files.isRegularFile(file)) return "absent"
                val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
                "present:${attributes.size()}:" +
                    "${attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS)}:" +
                    "${changeTimeNanos(file, attributes)}:${attributes.fileKey()}"
            }
            .getOrElse { "absent" }

    private fun StoredOriginState.toState(): OriginGitState? {
        val root = runCatching { Path.of(originRoot).toRealPath() }.getOrNull() ?: return null
        val sectionPaths = diffSections.orEmpty().map { it.path }
        val statusPaths = trackedStatusEntries.orEmpty().map { it.path }
        val untrackedPaths = untracked.map { it.path }
        if ((sectionPaths + statusPaths + untrackedPaths).any { !isSafeRelativePath(it) })
            return null
        if (!DETACHED_HEAD.matches(revision.orEmpty()) && revision != null) return null
        if (
            !DETACHED_HEAD.matches(resolvedExpectedRevision.orEmpty()) &&
                resolvedExpectedRevision != null
        ) {
            return null
        }
        return OriginGitState(
            originRoot = root,
            stamp = gitStamp,
            revision = revision,
            externalExpectedRevision = externalExpectedRevision,
            expectedRevision = resolvedExpectedRevision,
            diff = diff,
            diffSections = diffSections?.map { OriginGitState.DiffSection(it.path, it.text) },
            trackedStatus = trackedStatus,
            trackedStatusEntries =
                trackedStatusEntries?.map { OriginGitState.StatusEntry(it.path, it.line) },
            untracked = untracked.map { OriginGitState.UntrackedEntry(it.path, it.hash) },
        )
    }

    private fun digest(value: String): String = digest(value.toByteArray())

    private fun digest(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun changeTimeNanos(file: Path, attributes: BasicFileAttributes): Long =
        runCatching {
                (Files.getAttribute(file, "unix:ctime") as FileTime).to(TimeUnit.NANOSECONDS)
            }
            .getOrElse { attributes.creationTime().to(TimeUnit.NANOSECONDS) }

    private fun isSafeRelativePath(path: String): Boolean =
        path.isNotEmpty() &&
            !path.startsWith('/') &&
            !path.startsWith('\\') &&
            '\\' !in path &&
            path.split('/').none { it == ".." || it.isBlank() }

    @Serializable
    private data class StoredOriginState(
        val formatVersion: Int,
        val originId: String,
        val originRoot: String,
        val externalExpectedRevision: String?,
        val stamp: String,
        val gitStamp: String?,
        val revision: String?,
        val resolvedExpectedRevision: String?,
        val diff: String?,
        val diffSections: List<DiffSectionDto>?,
        val trackedStatus: String?,
        val trackedStatusEntries: List<StatusEntryDto>?,
        val untracked: List<UntrackedDto>,
    ) {
        companion object {
            fun from(
                originId: String,
                originRoot: String,
                externalExpectedRevision: String?,
                stamp: OriginStateStamp,
                state: OriginGitState,
            ): StoredOriginState? {
                val untrackedEntries = state.untracked ?: return null
                val revision = state.revision ?: return null
                val diff = state.diff ?: return null
                return StoredOriginState(
                    formatVersion = FORMAT_VERSION,
                    originId = originId,
                    originRoot = originRoot,
                    externalExpectedRevision = externalExpectedRevision,
                    stamp = stamp.value,
                    gitStamp = state.stamp,
                    revision = revision,
                    resolvedExpectedRevision = state.expectedRevision,
                    diff = diff,
                    diffSections = state.diffSections?.map { DiffSectionDto(it.path, it.text) },
                    trackedStatus = state.trackedStatus,
                    trackedStatusEntries =
                        state.trackedStatusEntries?.map { StatusEntryDto(it.path, it.line) },
                    untracked = untrackedEntries.map { UntrackedDto(it.path, it.contentHash) },
                )
            }
        }
    }

    @Serializable private data class DiffSectionDto(val path: String, val text: String)

    @Serializable private data class StatusEntryDto(val path: String, val line: String)

    @Serializable private data class UntrackedDto(val path: String, val hash: String?)

    companion object {
        const val RACY_WINDOW_NANOS = 2_000_000_000L

        fun open(
            directory: Path,
            cacheRoot: Path? = null,
            racyWindowNanos: Long = RACY_WINDOW_NANOS,
            xdgConfigHomeOverride: Path? = null,
        ): OriginStateCache =
            OriginStateCache(directory, cacheRoot, racyWindowNanos, xdgConfigHomeOverride)

        private const val FORMAT_VERSION = 1
        private const val GIT = ".git"
        private const val GITDIR_PREFIX = "gitdir:"
        private const val HEAD_REF_PREFIX = "ref:"
        private val DETACHED_HEAD = Regex("^[0-9a-f]{40,64}$")
        private val UNSUPPORTED_ENVIRONMENT_VARIABLES =
            listOf(
                "GIT_DIR",
                "GIT_WORK_TREE",
                "GIT_INDEX_FILE",
                "GIT_EXTERNAL_DIFF",
                "GIT_DIFF_OPTS",
                "GIT_CONFIG",
                "GIT_CONFIG_COUNT",
                "GIT_CONFIG_PARAMETERS",
            )
        private val INCLUDE_SECTION = Regex("(?im)^\\s*\\[\\s*include(?:if)?\\b")
        private val NESTED_SKIPPED_DIRECTORIES =
            setOf("objects", "logs", "hooks", "lfs", "rr-cache")
        private val NESTED_METADATA_FILES =
            setOf("HEAD", "packed-refs", "config", "commondir", "config.worktree")
        private const val NESTED_INDEX = "index"
        private const val NESTED_REFS = "refs"
        private const val NESTED_INFO = "info"
        private val UNSUPPORTED_CONFIG_KEYS =
            listOf(
                "include.path",
                "includeif",
                "bare = true",
                "bare=true",
                "worktree =",
                "worktree=",
            )
        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
