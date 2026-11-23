package dev.sebastiano.indexino.topology

import dev.sebastiano.indexino.topology.bazel.LiveBazelProcessRunner
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Toolchain inputs that can change discovery output without any build-definition edit. */
internal class ToolchainIdentity
private constructor(
    val value: String,
    /** False when the Bazel client is absent, so a `build-parse` result is the real environment. */
    val bazelAvailable: Boolean,
) {
    companion object {
        fun of(
            bazel: String,
            jvm: String,
            kotlin: String,
            bazelAvailable: Boolean = true,
        ): ToolchainIdentity =
            ToolchainIdentity("bazel=$bazel\u0000jvm=$jvm\u0000kotlin=$kotlin", bazelAvailable)

        /** [versionProbe] is a test seam; production probes the workspace's Bazel launcher. */
        fun capture(
            project: Path,
            buildSystem: BuildSystem,
            versionProbe: ((Path) -> String)? = null,
        ): ToolchainIdentity {
            val bazel =
                if (buildSystem != BuildSystem.BAZEL) {
                    BazelProbe("not-bazel", available = true)
                } else if (versionProbe != null) {
                    val version = versionProbe(project)
                    BazelProbe("probe=$version", available = bazelAvailable(version))
                } else {
                    bazelProbe(project)
                }
            return of(
                bazel = bazel.identity,
                jvm = "${Runtime.version()}:${System.getProperty("java.vm.version")}",
                kotlin = KotlinVersion.CURRENT.toString(),
                bazelAvailable = bazel.available,
            )
        }

        private class BazelProbe(val identity: String, val available: Boolean)

        /** `unavailable:<reason>` and `exit=<code>` both mean Bazel could not be run. */
        private fun bazelAvailable(version: String): Boolean =
            !version.startsWith(UNAVAILABLE) && !version.startsWith(EXIT_PREFIX)

        private val bazelProbes = ConcurrentHashMap<String, BazelProbe>()

        private fun bazelProbe(workspace: Path): BazelProbe {
            val pinned =
                runCatching { Files.readString(workspace.resolve(".bazelversion")).trim() }
                    .getOrElse { "<absent>" }
            val executable = bazelExecutableIdentity()
            val key = "$pinned\u0000$executable"
            bazelProbes[key]?.let {
                return it
            }
            val version = probeVersion(workspace)
            val probe =
                BazelProbe(
                    "pinned=$pinned\u0000exe=$executable\u0000probe=$version",
                    available = bazelAvailable(version),
                )
            bazelProbes[key] = probe
            return probe
        }

        /**
         * `bazel --version` reports the client version without starting a server. The workspace's
         * `.bazelversion` and the launcher identity cover the Bazelisk-pinned server version; a
         * launcher or pin replacement changes this key even when discovery files do not.
         */
        private fun probeVersion(workspace: Path): String =
            try {
                val outcome =
                    LiveBazelProcessRunner.runCommand(
                        listOf("bazel", "--version"),
                        workspace,
                        timeoutMillis = VERSION_PROBE_TIMEOUT_MILLIS,
                    )
                if (outcome.exitCode == 0) {
                    outcome.lines.joinToString(" ").trim().ifEmpty { "empty" }
                } else {
                    "$EXIT_PREFIX${outcome.exitCode}"
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                "$UNAVAILABLE:${failure.javaClass.simpleName}"
            }

        private fun bazelExecutableIdentity(): String {
            val candidates =
                System.getenv("PATH")
                    .orEmpty()
                    .split(File.pathSeparator)
                    .filter(String::isNotBlank)
                    .flatMap { directory ->
                        BAZEL_EXECUTABLE_NAMES.map { name -> Path.of(directory, name) }
                    }
            return candidates.firstNotNullOfOrNull { candidate ->
                if (!Files.isRegularFile(candidate)) {
                    null
                } else {
                    runCatching {
                            val attributes =
                                Files.readAttributes(candidate, BasicFileAttributes::class.java)
                            "${candidate.toAbsolutePath().normalize()}:" +
                                "${attributes.size()}:" +
                                attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS)
                        }
                        .getOrNull()
                }
            } ?: "<absent>"
        }

        private const val VERSION_PROBE_TIMEOUT_MILLIS = 10_000L
        private const val UNAVAILABLE = "unavailable"
        private const val EXIT_PREFIX = "exit="
        private val BAZEL_EXECUTABLE_NAMES =
            listOf("bazel", "bazel.exe", "bazel.cmd", "bazelisk", "bazelisk.exe")
    }
}

/**
 * Persistent topology cache keyed by build-definition content, the workspace file inventory, the
 * requested scope, and toolchain versions.
 *
 * The file inventory matters even without a build-file edit: BUILD globs and Gradle source roots
 * react to added or removed files. A fresh resolution is stored only when its inputs did not move
 * while discovery was running, so a racing edit cannot be recorded as the state that produced an
 * older result.
 */
internal object TopologyCache {
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun resolve(
        project: Path,
        request: TopologyRequest,
        buildSystem: BuildSystem,
        cacheRoot: Path,
        toolchain: ToolchainIdentity,
        progress: (String) -> Unit = {},
        preDiscoveryRoots: () -> List<Path> = { emptyList() },
        resolveFresh: () -> TopologyResult,
    ): TopologyResult {
        require(buildSystem == BuildSystem.BAZEL || buildSystem == BuildSystem.GRADLE) {
            "Topology cache only supports Bazel and Gradle"
        }
        val requestKey = requestKey(project, request, buildSystem)
        val entryFile = cacheRoot.resolve("$requestKey.json")
        readEntry(entryFile)
            ?.takeIf {
                it.formatVersion == FORMAT_VERSION &&
                    it.requestKey == requestKey &&
                    it.toolchain == toolchain.value &&
                    it.valid(project, buildSystem, cacheRoot)
            }
            ?.let { entry ->
                progress("index topology=cached build-inputs-unchanged")
                return entry.result.toTopologyResult()
            }

        val requestedRoots = runCatching { preDiscoveryRoots() }.getOrDefault(emptyList())
        val preMounts = requestedRoots.map { root ->
            BuildDefinitionInputs.capture(root, buildSystem, cacheRoot)
        }
        val before = BuildDefinitionInputs.capture(project, buildSystem, cacheRoot)
        val resolved = resolveFresh()
        if (request.includeDeps && !resolved.includeDeps) {
            // A target-only fallback did not satisfy a dependency-inclusive request. Retry on the
            // next refresh instead of pinning the incomplete closure until an input changes.
            return resolved
        }
        if (buildSystem == BuildSystem.BAZEL && resolved.codeSourceFiles == null) {
            // A failed code-role query leaves srcs-vs-resources unknown. Retrying costs one query;
            // caching would keep code facts for resource-only files until another input changes.
            return resolved
        }
        if (
            buildSystem == BuildSystem.BAZEL &&
                resolved.topology == BUILD_PARSE &&
                toolchain.bazelAvailable
        ) {
            // The client works, so a BUILD-parse result means this query failed transiently.
            // Reusing it until an input changes would hide a recoverable environment.
            return resolved
        }
        val after =
            BuildDefinitionInputs.capture(project, buildSystem, cacheRoot) ?: return resolved
        if (!sameFingerprint(before, after)) {
            return resolved
        }
        val mounts =
            resolved.externalMounts.map { mount ->
                BuildDefinitionInputs.capture(mount, buildSystem, cacheRoot) ?: return resolved
            }
        if (mounts.any { !it.cacheable } || !mountsStable(preMounts, mounts)) {
            return resolved
        }
        writeEntry(
            entryFile,
            CacheEntry(
                formatVersion = FORMAT_VERSION,
                requestKey = requestKey,
                toolchain = toolchain.value,
                roots = (listOf(after) + mounts).map(::RootFingerprintDto),
                result = TopologyResultDto.from(resolved),
            ),
        )
        return resolved
    }

    private fun sameFingerprint(first: RootFingerprint?, second: RootFingerprint?): Boolean {
        if (first == null || second == null) return false
        return first.cacheable &&
            second.cacheable &&
            first.root == second.root &&
            first.definitionDigest == second.definitionDigest &&
            first.inventoryDigest == second.inventoryDigest
    }

    /**
     * Included-build roots are captured before and after discovery, exactly like the primary
     * workspace. A source added to a mount while Gradle collects sources therefore prevents the
     * result from being stored instead of pairing an old source list with a new inventory.
     */
    private fun mountsStable(
        before: List<RootFingerprint?>,
        after: List<RootFingerprint?>,
    ): Boolean {
        if (before.size != after.size) return false
        if (before.any { it == null } || after.any { it == null }) return false
        val beforeByRoot = before.filterNotNull().associateBy { it.root }
        val afterByRoot = after.filterNotNull().associateBy { it.root }
        if (beforeByRoot.keys != afterByRoot.keys) return false
        return afterByRoot.all { (root, post) ->
            val pre = beforeByRoot[root] ?: return false
            pre.definitionDigest == post.definitionDigest &&
                pre.inventoryDigest == post.inventoryDigest
        }
    }

    private fun CacheEntry.valid(
        project: Path,
        buildSystem: BuildSystem,
        cacheRoot: Path,
    ): Boolean {
        if (roots.isEmpty()) return false
        val canonicalProject =
            runCatching { project.toRealPath().toString() }.getOrNull() ?: return false
        if (roots.first().root != canonicalProject) return false
        val externalMounts = result.externalMounts
        if (roots.size != externalMounts.size + 1) return false
        roots.drop(1).zip(externalMounts).forEach { (stored, mount) ->
            if (stored.root != mount) return false
        }
        return roots.all { root ->
            BuildDefinitionInputs.matches(root.toRootFingerprint(), buildSystem, cacheRoot)
        }
    }

    private fun requestKey(
        project: Path,
        request: TopologyRequest,
        buildSystem: BuildSystem,
    ): String {
        val identity =
            listOf(
                    runCatching { project.toRealPath().toString() }
                        .getOrElse { project.toAbsolutePath().normalize().toString() },
                    buildSystem.name,
                    request.bazelTarget.orEmpty(),
                    request.gradleModule.orEmpty(),
                    request.includeDeps.toString(),
                )
                .joinToString("\u0000")
        return HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()))
    }

    private fun readEntry(file: Path): CacheEntry? =
        runCatching { json.decodeFromString(CacheEntry.serializer(), Files.readString(file)) }
            .getOrNull()

    private fun writeEntry(file: Path, entry: CacheEntry) {
        runCatching {
                Files.createDirectories(file.parent)
                val temporary = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
                try {
                    Files.writeString(
                        temporary,
                        json.encodeToString(CacheEntry.serializer(), entry),
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

    @Serializable
    private data class CacheEntry(
        val formatVersion: Int,
        val requestKey: String,
        val toolchain: String,
        val roots: List<RootFingerprintDto>,
        val result: TopologyResultDto,
    )

    @Serializable
    private data class RootFingerprintDto(
        val root: String,
        val definitionDigest: String,
        val inventoryDigest: String,
        val cacheable: Boolean = true,
    ) {
        constructor(
            fingerprint: RootFingerprint
        ) : this(
            fingerprint.root,
            fingerprint.definitionDigest,
            fingerprint.inventoryDigest,
            fingerprint.cacheable,
        )

        fun toRootFingerprint(): RootFingerprint =
            RootFingerprint(root, definitionDigest, inventoryDigest, cacheable)
    }

    @Serializable
    private data class TopologyResultDto(
        val sourceFiles: List<String>,
        val codeSourceFiles: List<String>? = null,
        val topology: String,
        val includeDeps: Boolean,
        val scope: String,
        val externalMounts: List<String> = emptyList(),
        val externalSources: List<ExternalSourceMountDto> = emptyList(),
        val resolvedTopologyDigest: String? = null,
    ) {
        fun toTopologyResult(): TopologyResult =
            TopologyResult(
                sourceFiles = sourceFiles,
                codeSourceFiles = codeSourceFiles?.toCollection(linkedSetOf()),
                topology = topology,
                includeDeps = includeDeps,
                scope = scope,
                externalMounts = externalMounts.map(Path::of),
                externalSources = externalSources.map { it.toExternalSourceMount() },
                resolvedTopologyDigest = resolvedTopologyDigest,
            )

        companion object {
            fun from(result: TopologyResult): TopologyResultDto =
                TopologyResultDto(
                    sourceFiles = result.sourceFiles,
                    codeSourceFiles = result.codeSourceFiles?.sorted(),
                    topology = result.topology,
                    includeDeps = result.includeDeps,
                    scope = result.scope,
                    externalMounts = result.externalMounts.map { it.toString() },
                    externalSources = result.externalSources.map(ExternalSourceMountDto::from),
                    resolvedTopologyDigest = result.resolvedTopologyDigest,
                )
        }
    }

    @Serializable
    private data class ExternalSourceMountDto(
        val root: String,
        val sourceFiles: List<String>,
        val codeSourceFiles: List<String>? = null,
        val originId: String? = null,
        val expectedRevision: String? = null,
    ) {
        fun toExternalSourceMount(): ExternalSourceMount =
            ExternalSourceMount(
                root = Path.of(root),
                sourceFiles = sourceFiles,
                codeSourceFiles = codeSourceFiles?.toCollection(linkedSetOf()),
                originId = originId,
                expectedRevision = expectedRevision,
            )

        companion object {
            fun from(mount: ExternalSourceMount): ExternalSourceMountDto =
                ExternalSourceMountDto(
                    root = mount.root.toString(),
                    sourceFiles = mount.sourceFiles,
                    codeSourceFiles = mount.codeSourceFiles?.sorted(),
                    originId = mount.originId,
                    expectedRevision = mount.expectedRevision,
                )
        }
    }

    private const val FORMAT_VERSION = 2
    private const val BUILD_PARSE = "build-parse"
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}
