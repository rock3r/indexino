package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.core.git.GitHeadResolver
import dev.sebastiano.indexino.core.manifest.IndexManifestOrigin
import dev.sebastiano.indexino.producer.FileHashProducer
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.producer.SourceContentSnapshot
import dev.sebastiano.indexino.topology.SourceOriginResolver
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** Builds current origin provenance for manifest freshness checks and publication. */
@Suppress("TooManyFunctions")
internal object ManifestOriginResolver {
    fun resolve(
        workspace: Path,
        sources: List<IndexedSource>,
        externalOriginMetadata: Map<Path, Pair<String?, String?>>,
        includeWorkspaceWithoutSources: Boolean = false,
        sourceSnapshot: SourceContentSnapshot? = null,
    ): List<IndexManifestOrigin> =
        resolveWithState(
                workspace,
                sources,
                externalOriginMetadata,
                includeWorkspaceWithoutSources,
                sourceSnapshot,
                captureState = false,
            )
            .origins

    /**
     * Resolves origins and, with [captureState], the Git state a watcher refresh may later update
     * through [hint]. An origin is updated incrementally only when its unobservable Git inputs are
     * provably unchanged and the recursive watcher saw nothing but [OriginIncrementalHint] sources
     * change; every other origin runs the full whole-repository Git reads.
     */
    @Suppress("CyclomaticComplexMethod")
    fun resolveWithState(
        workspace: Path,
        sources: List<IndexedSource>,
        externalOriginMetadata: Map<Path, Pair<String?, String?>>,
        includeWorkspaceWithoutSources: Boolean = false,
        sourceSnapshot: SourceContentSnapshot? = null,
        captureState: Boolean = true,
        hint: OriginIncrementalHint? = null,
        progress: ((String) -> Unit)? = null,
        onPhaseForTests: ((originId: String, phase: String, completed: Boolean) -> Unit)? = null,
    ): OriginResolution {
        val incrementalCount = AtomicInteger()
        val timings = ConcurrentLinkedQueue<String>()
        fun <T> measured(originId: String, phase: String, action: () -> T): T {
            onPhaseForTests?.invoke(originId, phase, false)
            if (progress == null && onPhaseForTests == null) return action()
            val started = System.nanoTime()
            return action().also {
                if (progress != null)
                    timings.add(
                        "index origin=$originId phase=$phase durationMillis=" +
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    )
                onPhaseForTests?.invoke(originId, phase, true)
            }
        }
        fun resolveState(
            originId: String,
            originRoot: Path,
            externalExpectedRevision: String?,
        ): OriginGitState =
            measured(originId, "git-state") {
                hint
                    ?.let {
                        incrementalState(
                            workspace,
                            originId,
                            originRoot,
                            externalExpectedRevision,
                            it,
                        )
                    }
                    ?.also { incrementalCount.incrementAndGet() }
                    ?: fullState(workspace, originRoot, externalExpectedRevision, captureState)
            }
        fun withFingerprint(
            originId: String,
            originRoot: Path,
            sourceFingerprint: String,
            state: OriginGitState,
        ): Pair<IndexManifestOrigin, OriginGitState> {
            return measured(originId, "manifest-fingerprint") {
                originFrom(originId, originRoot, sourceFingerprint, state)
            } to state
        }
        fun resolveOrigin(
            originId: String,
            originRoot: Path,
            sourceFingerprint: String,
            externalExpectedRevision: String?,
        ): Pair<IndexManifestOrigin, OriginGitState> =
            withFingerprint(
                originId,
                originRoot,
                sourceFingerprint,
                resolveState(originId, originRoot, externalExpectedRevision),
            )
        val sourceOrigins =
            sources
                .groupBy { it.originId to it.originRoot }
                .map { (identity, originSources) ->
                    val (originId, originRoot) = identity
                    async {
                        val expectedRevision =
                            externalOriginMetadata[originRoot.toRealPath()]?.second
                        val fingerprint = {
                            measured(originId, "source-fingerprint") {
                                FileHashProducer.contentHash(
                                    originSources
                                        .sortedBy { it.path }
                                        .joinToString("\n") { source ->
                                            val hash =
                                                sourceSnapshot?.contentHash(source)
                                                    ?: FileHashProducer.contentHash(
                                                        source.originRoot
                                                            .resolve(source.path)
                                                            .readText()
                                                    )
                                            "${source.path}:$hash"
                                        }
                                )
                            }
                        }
                        val (capturedFingerprint, state) =
                            sourceFingerprintAndGitState(sourceSnapshot, fingerprint) {
                                resolveState(originId, originRoot, expectedRevision)
                            }
                        withFingerprint(originId, originRoot, capturedFingerprint, state)
                    }
                }
                .map { it.join() }
        // One realpath per distinct root, not per source: tens of thousands of syscalls otherwise.
        val sourceRoots =
            sources.mapTo(hashSetOf()) { it.originRoot }.map { it.toRealPath() }.toSet()
        val emptyExternalOrigins =
            externalOriginMetadata
                .filterKeys { it !in sourceRoots }
                .map { (originRoot, metadata) ->
                    resolveOrigin(
                        originId =
                            metadata.first ?: SourceOriginResolver.externalOriginId(originRoot),
                        originRoot = originRoot,
                        sourceFingerprint = FileHashProducer.contentHash(""),
                        externalExpectedRevision = metadata.second,
                    )
                }
        val workspaceOrigin =
            if (
                includeWorkspaceWithoutSources &&
                    sourceOrigins.none { it.first.originId == WORKSPACE_ORIGIN_ID }
            ) {
                listOf(
                    resolveOrigin(
                        originId = WORKSPACE_ORIGIN_ID,
                        originRoot = workspace,
                        sourceFingerprint = FileHashProducer.contentHash(""),
                        externalExpectedRevision = null,
                    )
                )
            } else {
                emptyList()
            }
        val resolved = sourceOrigins + emptyExternalOrigins + workspaceOrigin
        progress?.let { sink -> timings.forEach(sink) }
        return OriginResolution(
            origins = resolved.map { it.first }.sortedBy { it.originId },
            states =
                if (captureState) {
                    resolved.associate { (origin, state) -> origin.originId to state }
                } else emptyMap(),
            incremental = incrementalCount.get(),
        )
    }

    private fun sourceFingerprintAndGitState(
        snapshot: SourceContentSnapshot?,
        fingerprint: () -> String,
        gitState: () -> OriginGitState,
    ): Pair<String, OriginGitState> {
        if (snapshot == null) return fingerprint() to gitState()
        val runningGit = async(gitState)
        val captured = runCatching(fingerprint)
        val state = runCatching { runningGit.join() }
        captured.exceptionOrNull()?.let { failure ->
            state.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        return captured.getOrThrow() to state.getOrThrow()
    }

    private fun originFrom(
        originId: String,
        originRoot: Path,
        sourceFingerprint: String,
        state: OriginGitState,
    ): IndexManifestOrigin =
        IndexManifestOrigin(
            originId = originId,
            revision = state.revision,
            stateFingerprint =
                FileHashProducer.contentHash(
                    "$sourceFingerprint:${state.workingTreeFingerprint() ?: filesystemInventoryFingerprint(originRoot)}"
                ),
            expectedRevision = state.expectedRevision,
            dirty = state.dirty(),
        )

    /**
     * Each Git command below walks the whole origin; on a large checkout each costs seconds. They
     * are independent reads, so they run concurrently, and `git status` skips the untracked walk
     * that `ls-files --others` already performs. A captured state is stamped before and after the
     * reads; a stamp that moved means another Git client raced them, so the state stays full-only.
     */
    private fun fullState(
        workspace: Path,
        originRoot: Path,
        externalExpectedRevision: String?,
        captureState: Boolean,
    ): OriginGitState {
        val stampBefore = if (captureState) stamp(workspace, originRoot) else null
        val diff = async {
            val arguments = listOf("diff", "--no-ext-diff", "--binary", "HEAD", "--", ".")
            if (stampBefore == null) runGit(originRoot, arguments)
            else runGitWithIndexCopy(originRoot, stampBefore.gitDir, arguments)
        }
        val untracked = async {
            runGit(originRoot, "ls-files", "--others", "--exclude-standard", "-z", "--", ".")
        }
        val trackedStatus = async {
            runGit(originRoot, "status", "--porcelain", "--untracked-files=no", "--", ".")
        }
        val revision = async { revisionAtOriginRoot(originRoot) }
        val expected = async {
            externalExpectedRevision ?: expectedSubmoduleRevision(workspace, originRoot)
        }
        val untrackedPaths =
            untracked
                .join()
                ?.split('\u0000')
                ?.filter(String::isNotEmpty)
                ?.filterNot(OriginGitState::isTransitionalPath)
        val diffText = diff.join()
        val statusText = trackedStatus.join()
        val stamp = stampBefore?.value?.takeIf { stamp(workspace, originRoot)?.value == it }
        return OriginGitState(
            originRoot = originRoot,
            stamp = stamp,
            revision = revision.join(),
            externalExpectedRevision = externalExpectedRevision,
            expectedRevision = expected.join(),
            diff = diffText,
            diffSections = diffText?.takeIf { stamp != null }?.let(OriginGitState::diffSections),
            trackedStatus = statusText,
            trackedStatusEntries =
                statusText?.takeIf { stamp != null }?.let(OriginGitState::statusEntries),
            untracked = untrackedPaths?.let { OriginGitState.untrackedEntries(originRoot, it) },
        )
    }

    /**
     * Updates [OriginIncrementalHint.previous] for an origin inside the recursive watch root when
     * its stamp is unchanged. Only hinted files of this origin are re-read, with pathspec-limited
     * `diff` and `status`; untracked membership cannot change without a watcher event, so only
     * hinted untracked files are re-hashed. Returns null to request full recomputation.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun incrementalState(
        workspace: Path,
        originId: String,
        originRoot: Path,
        externalExpectedRevision: String?,
        hint: OriginIncrementalHint,
    ): OriginGitState? {
        val previous =
            hint.previous[originId]?.takeIf {
                it.incremental &&
                    it.originRoot == originRoot &&
                    it.externalExpectedRevision == externalExpectedRevision
            } ?: return null
        if (!originRoot.startsWith(hint.recursiveWatchRoot)) return null
        val current =
            stamp(workspace, originRoot)?.takeIf { it.value == previous.stamp } ?: return null
        val stamp = current.value
        if (enclosesTrackedHintedOrigin(originRoot, hint)) return null
        val own =
            hint.hintedSources
                .filter { it.originId == originId && it.originRoot == originRoot }
                .map { it.path }
                .distinct()
        if (own.isEmpty()) return previous
        if (own.size > MAX_INCREMENTAL_PATHS || !own.all(OriginGitState::isVerbatimPath)) {
            return null
        }
        val diff = async {
            runGitWithIndexCopy(
                originRoot,
                current.gitDir,
                listOf("--literal-pathspecs", "diff", "--no-ext-diff", "--binary", "HEAD", "--") +
                    own,
            )
        }
        val status = async {
            runGit(
                originRoot,
                listOf(
                    "--literal-pathspecs",
                    "status",
                    "--porcelain",
                    "--untracked-files=no",
                    "--",
                ) + own,
            )
        }
        val ownPaths = own.toSet()
        val newSections = diff.join()?.let(OriginGitState::diffSections) ?: return null
        val newEntries = status.join()?.let(OriginGitState::statusEntries) ?: return null
        if (newSections.any { it.path !in ownPaths } || newEntries.any { it.path !in ownPaths }) {
            return null
        }
        if (stamp(workspace, originRoot)?.value != stamp) return null
        return previous.withHintedPaths(
            stamp,
            externalExpectedRevision,
            ownPaths,
            newSections,
            newEntries,
        )
    }

    /**
     * A nested origin's edits can change this origin's diff only through paths this origin's index
     * tracks, such as a gitlink or vendored copy; any tracked path requires full reads.
     */
    private fun enclosesTrackedHintedOrigin(
        originRoot: Path,
        hint: OriginIncrementalHint,
    ): Boolean {
        val nestedRoots =
            hint.hintedSources
                .map { it.originRoot }
                .distinct()
                .filter { it != originRoot && it.startsWith(originRoot) }
        if (nestedRoots.isEmpty()) return false
        val tracked =
            runGit(
                originRoot,
                listOf("--literal-pathspecs", "ls-files", "--stage", "-z", "--") +
                    nestedRoots.map { originRoot.relativize(it).invariantSeparatorsPathString },
            )
        return tracked == null || tracked.isNotEmpty()
    }

    /**
     * Git inputs a workspace watcher cannot observe: HEAD and its effective tree, the HEAD of the
     * repository that decides [expectedSubmoduleRevision], effective configuration, the index file
     * identity, and ignore, attribute, graft and sparse-checkout files. Null when unavailable.
     */
    private class GitStamp(val value: String, val gitDir: Path)

    @Suppress("ReturnCount")
    private fun stamp(workspace: Path, originRoot: Path): GitStamp? {
        val parsed = async {
            runGit(
                originRoot,
                "rev-parse",
                "HEAD",
                "HEAD^{tree}",
                "--absolute-git-dir",
                "--git-common-dir",
            )
        }
        val config = async { runGit(originRoot, "config", "--list", "--show-origin") }
        val ownerHead = async {
            val owner = superprojectRoot(originRoot.toRealPath()) ?: workspace.toRealPath()
            runGit(owner, "rev-parse", "HEAD")
        }
        val lines = parsed.join()?.lines()?.filter(String::isNotEmpty) ?: return null
        if (lines.size != REV_PARSE_STAMP_LINES) return null
        val gitDir = Path.of(lines[2])
        val commonDir = originRoot.resolve(lines[3]).normalize()
        val configText = config.join() ?: return null
        val index =
            runCatching {
                    val attributes =
                        Files.readAttributes(
                            gitDir.resolve("index"),
                            BasicFileAttributes::class.java,
                        )
                    listOf(
                            attributes.size(),
                            attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                            attributes.fileKey(),
                        )
                        .joinToString(":")
                }
                .getOrDefault("absent")
        val inputs =
            listOf(
                commonDir.resolve("info/exclude"),
                commonDir.resolve("info/attributes"),
                commonDir.resolve("info/grafts"),
                gitDir.resolve("info/sparse-checkout"),
                configuredFile(configText, "core.excludesfile", "ignore"),
                configuredFile(configText, "core.attributesfile", "attributes"),
            )
        val value =
            (listOf(lines[0], lines[1], ownerHead.join().orEmpty(), configText, index) +
                    inputs.map { "$it=${OriginGitState.hashOrNull(it)}" })
                .joinToString("\u0000")
        return GitStamp(value, gitDir)
    }

    /**
     * `git diff` rewrites the index when it finds stat-only changes, even with optional locks
     * disabled. Diffing against a private copy keeps the output identical while leaving the user's
     * index, and so its stamp, untouched. The copy keeps the index mtime so racy-entry detection is
     * unchanged.
     */
    private fun runGitWithIndexCopy(
        originRoot: Path,
        gitDir: Path,
        arguments: List<String>,
    ): String? {
        val index = gitDir.resolve("index")
        if (!Files.isRegularFile(index)) return runGit(originRoot, arguments)
        val directory = Files.createTempDirectory("indexino-git-index-")
        return try {
            val copy = directory.resolve("index")
            Files.copy(index, copy, StandardCopyOption.COPY_ATTRIBUTES)
            runGit(originRoot, arguments, indexFile = copy)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /** The file Git reads for [key], or its XDG default. */
    private fun configuredFile(config: String, key: String, defaultName: String): Path {
        val home = System.getProperty("user.home")
        val configured =
            config
                .lineSequence()
                .mapNotNull { line ->
                    line.substringAfter('\t', "").takeIf { it.startsWith("$key=") }
                }
                .lastOrNull()
                ?.substringAfter('=')
        if (configured != null) {
            return if (configured.startsWith("~/")) Path.of(home, configured.drop(2))
            else Path.of(configured)
        }
        val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf(String::isNotBlank)
        return (xdg?.let(Path::of) ?: Path.of(home, ".config")).resolve("git").resolve(defaultName)
    }

    private fun <T> async(block: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync(block, GIT_EXECUTOR)

    private fun <T> CompletableFuture<T>.join(): T =
        try {
            get()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }

    private fun revisionAtOriginRoot(originRoot: Path): String? {
        val canonicalRoot = originRoot.toRealPath()
        val topLevel = runGit(canonicalRoot, "rev-parse", "--show-toplevel")?.trim()?.let(Path::of)
        if (topLevel?.toRealPath() != canonicalRoot) return null
        return GitHeadResolver.resolve(canonicalRoot)
            .takeUnless(GitHeadResolver::isFilesystemRevision)
    }

    private fun filesystemInventoryFingerprint(originRoot: Path): String =
        Files.walk(originRoot).use { paths ->
            paths
                .iterator()
                .asSequence()
                .filter(Path::isRegularFile)
                .map { file -> originRoot.relativize(file).toString().replace('\\', '/') to file }
                .filterNot { (path, _) ->
                    OriginGitState.isTransitionalPath(path) ||
                        path.split('/').any { it in TRANSIENT_DIRECTORY_NAMES }
                }
                .sortedBy { (path, _) -> path }
                .joinToString("\n") { (path, file) ->
                    "$path:${runCatching { FileHashProducer.contentHash(Files.readAllBytes(file)) }.getOrNull()}"
                }
                .let(FileHashProducer::contentHash)
        }

    private fun runGit(originRoot: Path, vararg arguments: String): String? =
        runGit(originRoot, arguments.asList())

    private fun runGit(
        originRoot: Path,
        arguments: List<String>,
        indexFile: Path? = null,
    ): String? {
        val process =
            runCatching {
                    ProcessBuilder(listOf("git", "-C", originRoot.toString()) + arguments)
                        .redirectErrorStream(true)
                        // Reads must not refresh the index: that would move the index stamp and
                        // write to the user's repository.
                        .apply {
                            environment()["GIT_OPTIONAL_LOCKS"] = "0"
                            indexFile?.let { environment()["GIT_INDEX_FILE"] = it.toString() }
                        }
                        .start()
                }
                .getOrNull() ?: return null
        val output = process.inputStream.bufferedReader().readText()
        return output.takeIf { process.waitFor() == 0 }
    }

    private fun expectedSubmoduleRevision(workspace: Path, originRoot: Path): String? {
        val canonicalOriginRoot = originRoot.toRealPath()
        val owner = superprojectRoot(canonicalOriginRoot) ?: workspace.toRealPath()
        if (canonicalOriginRoot == owner || !canonicalOriginRoot.startsWith(owner)) return null
        val mount = owner.relativize(canonicalOriginRoot).toString().replace('\\', '/')
        val process =
            runCatching {
                    ProcessBuilder("git", "-C", owner.toString(), "ls-tree", "HEAD", "--", mount)
                        .redirectErrorStream(true)
                        .start()
                }
                .getOrNull() ?: return null
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() != 0) return null
        val fields = output.substringBefore('\t').split(' ')
        return fields.getOrNull(0)?.takeIf { it == "160000" }?.let { fields.getOrNull(2) }
    }

    private fun superprojectRoot(originRoot: Path): Path? {
        val process =
            runCatching {
                    ProcessBuilder(
                            "git",
                            "-C",
                            originRoot.toString(),
                            "rev-parse",
                            "--show-superproject-working-tree",
                        )
                        .redirectErrorStream(true)
                        .start()
                }
                .getOrNull() ?: return null
        val output = process.inputStream.bufferedReader().readText().trim()
        return output.takeIf { process.waitFor() == 0 && it.isNotEmpty() }?.let(Path::of)
    }

    private const val REV_PARSE_STAMP_LINES = 4
    private const val MAX_INCREMENTAL_PATHS = 2048
    // Unbounded because origin tasks wait on their own Git reads; threads idle out after 60s.
    private val GIT_EXECUTOR: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "indexino-origin-git").apply { isDaemon = true }
    }
    private val TRANSIENT_DIRECTORY_NAMES =
        setOf(".git", ".gradle", ".idea", "build", "out", "target")
    private const val WORKSPACE_ORIGIN_ID = "workspace"
}
