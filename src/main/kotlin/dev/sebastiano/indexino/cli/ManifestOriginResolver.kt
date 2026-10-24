package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.core.manifest.IndexManifestOrigin
import dev.sebastiano.indexino.producer.FileHashProducer
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.producer.SourceContentSnapshot
import dev.sebastiano.indexino.topology.SourceOriginResolver
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    ): OriginResolution = withOriginScope {
        val incrementalCount = AtomicInteger()
        val timings = ConcurrentLinkedQueue<String>()
        suspend fun <T> measured(originId: String, phase: String, action: suspend () -> T): T {
            runInterruptible { onPhaseForTests?.invoke(originId, phase, false) }
            if (progress == null && onPhaseForTests == null) return action()
            val started = System.nanoTime()
            return action().also {
                if (progress != null)
                    timings.add(
                        "index origin=$originId phase=$phase durationMillis=" +
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    )
                runInterruptible { onPhaseForTests?.invoke(originId, phase, true) }
            }
        }
        suspend fun resolveState(
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
        suspend fun withFingerprint(
            originId: String,
            originRoot: Path,
            sourceFingerprint: String,
            state: OriginGitState,
        ): Pair<IndexManifestOrigin, OriginGitState> {
            return measured(originId, "manifest-fingerprint") {
                runInterruptible { originFrom(originId, originRoot, sourceFingerprint, state) }
            } to state
        }
        suspend fun resolveOrigin(
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
        val inventory = sources.groupBy { it.originId to it.originRoot }.entries.toList()
        val nextOrigin = AtomicInteger()
        val sourceOrigins = coroutineScope {
            List(minOf(ORIGIN_WORKERS, inventory.size)) {
                    async {
                        buildList {
                            while (true) {
                                ensureActive()
                                val index = nextOrigin.getAndIncrement()
                                if (index >= inventory.size) break
                                val (identity, originSources) = inventory[index]
                                val (originId, originRoot) = identity
                                val expectedRevision =
                                    externalOriginMetadata[originRoot.toRealPath()]?.second
                                val fingerprint: suspend () -> String = {
                                    measured(originId, "source-fingerprint") {
                                        runInterruptible {
                                            FileHashProducer.contentHash(
                                                originSources
                                                    .sortedBy { it.path }
                                                    .joinToString("\n") { source ->
                                                        if (Thread.currentThread().isInterrupted)
                                                            throw InterruptedException()
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
                                }
                                val (capturedFingerprint, state) =
                                    sourceFingerprintAndGitState(sourceSnapshot, fingerprint) {
                                        resolveState(originId, originRoot, expectedRevision)
                                    }
                                add(
                                    withFingerprint(
                                        originId,
                                        originRoot,
                                        capturedFingerprint,
                                        state,
                                    )
                                )
                            }
                        }
                    }
                }
                .awaitAll()
                .flatten()
        }
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
        OriginResolution(
            origins = resolved.map { it.first }.sortedBy { it.originId },
            states =
                if (captureState) {
                    resolved.associate { (origin, state) -> origin.originId to state }
                } else emptyMap(),
            incremental = incrementalCount.get(),
        )
    }

    private suspend fun sourceFingerprintAndGitState(
        snapshot: SourceContentSnapshot?,
        fingerprint: suspend () -> String,
        gitState: suspend () -> OriginGitState,
    ): Pair<String, OriginGitState> = coroutineScope {
        if (snapshot == null) return@coroutineScope fingerprint() to gitState()
        val runningGit = async { gitState() }
        fingerprint() to runningGit.await()
    }

    // This internal blocking bridge owns its dispatcher; it is not supplied by an API caller.
    @Suppress("InjectDispatcher")
    private fun <T> withOriginScope(block: suspend CoroutineScope.() -> T): T {
        val owner = Job()
        try {
            return runBlocking(Dispatchers.IO + owner, block)
        } finally {
            // Interrupted runBlocking cancels its body but does not wait for child cleanup.
            // Do not let a stopped refresh publish its terminal event before all Git exits.
            owner.cancel()
            var interrupted = Thread.interrupted()
            while (!owner.isCompleted) {
                try {
                    runBlocking { owner.join() }
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
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
    private suspend fun fullState(
        workspace: Path,
        originRoot: Path,
        externalExpectedRevision: String?,
        captureState: Boolean,
    ): OriginGitState = coroutineScope {
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
                .await()
                ?.split('\u0000')
                ?.filter(String::isNotEmpty)
                ?.filterNot(OriginGitState::isTransitionalPath)
        val diffText = diff.await()
        val statusText = trackedStatus.await()
        val stamp = stampBefore?.value?.takeIf { stamp(workspace, originRoot)?.value == it }
        OriginGitState(
            originRoot = originRoot,
            stamp = stamp,
            revision = revision.await(),
            externalExpectedRevision = externalExpectedRevision,
            expectedRevision = expected.await(),
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
    private suspend fun incrementalState(
        workspace: Path,
        originId: String,
        originRoot: Path,
        externalExpectedRevision: String?,
        hint: OriginIncrementalHint,
    ): OriginGitState? = coroutineScope {
        val previous =
            hint.previous[originId]?.takeIf {
                it.incremental &&
                    it.originRoot == originRoot &&
                    it.externalExpectedRevision == externalExpectedRevision
            } ?: return@coroutineScope null
        if (!originRoot.startsWith(hint.recursiveWatchRoot)) return@coroutineScope null
        val current =
            stamp(workspace, originRoot)?.takeIf { it.value == previous.stamp }
                ?: return@coroutineScope null
        val stamp = current.value
        if (enclosesTrackedHintedOrigin(originRoot, hint)) return@coroutineScope null
        val own =
            hint.hintedSources
                .filter { it.originId == originId && it.originRoot == originRoot }
                .map { it.path }
                .distinct()
        if (own.isEmpty()) return@coroutineScope previous
        if (own.size > MAX_INCREMENTAL_PATHS || !own.all(OriginGitState::isVerbatimPath)) {
            return@coroutineScope null
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
        val newSections =
            diff.await()?.let(OriginGitState::diffSections) ?: return@coroutineScope null
        val newEntries =
            status.await()?.let(OriginGitState::statusEntries) ?: return@coroutineScope null
        if (newSections.any { it.path !in ownPaths } || newEntries.any { it.path !in ownPaths }) {
            return@coroutineScope null
        }
        if (stamp(workspace, originRoot)?.value != stamp) return@coroutineScope null
        previous.withHintedPaths(stamp, externalExpectedRevision, ownPaths, newSections, newEntries)
    }

    /**
     * A nested origin's edits can change this origin's diff only through paths this origin's index
     * tracks, such as a gitlink or vendored copy; any tracked path requires full reads.
     */
    private suspend fun enclosesTrackedHintedOrigin(
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
    private suspend fun stamp(workspace: Path, originRoot: Path): GitStamp? = coroutineScope {
        val parsed = async {
            runGit(
                originRoot,
                "rev-parse",
                "HEAD",
                "HEAD^{tree}",
                "--absolute-git-dir",
                "--git-common-dir",
                "--show-superproject-working-tree",
            )
        }
        val config = async { runGit(originRoot, "config", "--list", "--show-origin") }
        val parsedText = parsed.await() ?: return@coroutineScope null
        val lines = parsedText.lines().filter(String::isNotEmpty)
        if (lines.size !in REV_PARSE_STAMP_LINES..REV_PARSE_STAMP_LINES + 1)
            return@coroutineScope null
        val gitDir = Path.of(lines[2])
        val commonDir = originRoot.resolve(lines[3]).normalize()
        val owner = lines.getOrNull(4)?.let(Path::of) ?: workspace.toRealPath()
        val ownerHead =
            if (runCatching { Files.isSameFile(owner, originRoot) }.getOrDefault(false)) {
                // Preserve the newline in the old rev-parse HEAD output for existing stamps.
                parsedText.substringBefore('\n') + "\n"
            } else runGit(owner, "rev-parse", "HEAD").orEmpty()
        val configText = config.await() ?: return@coroutineScope null
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
            (listOf(lines[0], lines[1], ownerHead, configText, index) +
                    inputs.map { "$it=${OriginGitState.hashOrNull(it)}" })
                .joinToString("\u0000")
        GitStamp(value, gitDir)
    }

    /**
     * `git diff` rewrites the index when it finds stat-only changes, even with optional locks
     * disabled. Diffing against a private copy keeps the output identical while leaving the user's
     * index, and so its stamp, untouched. The copy keeps the index mtime so racy-entry detection is
     * unchanged.
     */
    private suspend fun runGitWithIndexCopy(
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

    private suspend fun revisionAtOriginRoot(originRoot: Path): String? {
        val canonicalRoot = originRoot.toRealPath()
        val topLevel = runGit(canonicalRoot, "rev-parse", "--show-toplevel")?.trim()?.let(Path::of)
        if (topLevel?.toRealPath() != canonicalRoot) return null
        return runGit(canonicalRoot, "rev-parse", "HEAD")?.trim()
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
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    "$path:${runCatching { FileHashProducer.contentHash(Files.readAllBytes(file)) }.getOrNull()}"
                }
                .let(FileHashProducer::contentHash)
        }

    private suspend fun runGit(originRoot: Path, vararg arguments: String): String? =
        runGit(originRoot, arguments.asList())

    private suspend fun runGit(
        originRoot: Path,
        arguments: List<String>,
        indexFile: Path? = null,
    ): String? = GIT_PROCESSES.withPermit {
        runInterruptible {
            val output = Files.createTempFile("indexino-origin-git-", ".out")
            var process: Process? = null
            val descendants = linkedSetOf<ProcessHandle>()
            try {
                // Only a start failure is an unavailable Git command, never cancellation.
                process =
                    try {
                        ProcessBuilder(listOf("git", "-C", originRoot.toString()) + arguments)
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile())
                            .apply {
                                environment()["GIT_OPTIONAL_LOCKS"] = "0"
                                indexFile?.let { environment()["GIT_INDEX_FILE"] = it.toString() }
                            }
                            .start()
                    } catch (_: java.io.IOException) {
                        return@runInterruptible null
                    }
                do {
                    process.descendants().use { children -> children.forEach(descendants::add) }
                } while (!process.waitFor(PROCESS_POLL_MILLIS, TimeUnit.MILLISECONDS))
                Files.readAllBytes(output).toString(Charsets.UTF_8).takeIf {
                    process.exitValue() == 0
                }
            } finally {
                // runInterruptible does not complete (and the permit is not returned) until this
                // blocking body has reaped its process, including on cancellation during waitFor.
                process?.let { terminateAndReap(it, descendants) }
                Files.deleteIfExists(output)
            }
        }
    }

    private fun terminateAndReap(process: Process, knownDescendants: Set<ProcessHandle>) {
        val descendants =
            (knownDescendants + process.descendants().use { it.toList() }).sortedByDescending {
                child ->
                generateSequence(child) { it.parent().orElse(null) }.count()
            }
        var interrupted = Thread.interrupted()
        try {
            // Leave each parent alive to reap its children before terminating the parent.
            descendants.forEach { child ->
                child.destroyForcibly()
                while (child.isAlive) {
                    try {
                        child.onExit().get()
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            }
            process.destroyForcibly()
            while (process.isAlive) {
                try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private suspend fun expectedSubmoduleRevision(workspace: Path, originRoot: Path): String? {
        val canonicalOriginRoot = originRoot.toRealPath()
        val owner = superprojectRoot(canonicalOriginRoot) ?: workspace.toRealPath()
        if (canonicalOriginRoot == owner || !canonicalOriginRoot.startsWith(owner)) return null
        val mount = owner.relativize(canonicalOriginRoot).toString().replace('\\', '/')
        val output = runGit(owner, "ls-tree", "HEAD", "--", mount)?.trim() ?: return null
        val fields = output.substringBefore('\t').split(' ')
        return fields.getOrNull(0)?.takeIf { it == "160000" }?.let { fields.getOrNull(2) }
    }

    private suspend fun superprojectRoot(originRoot: Path): Path? =
        runGit(originRoot, "rev-parse", "--show-superproject-working-tree")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(Path::of)

    private const val REV_PARSE_STAMP_LINES = 4
    private const val MAX_INCREMENTAL_PATHS = 2048
    private const val ORIGIN_WORKERS = 4
    private const val PROCESS_POLL_MILLIS = 25L
    private val GIT_PROCESSES = Semaphore(4)
    private val TRANSIENT_DIRECTORY_NAMES =
        setOf(".git", ".gradle", ".idea", "build", "out", "target")
    private const val WORKSPACE_ORIGIN_ID = "workspace"
}
