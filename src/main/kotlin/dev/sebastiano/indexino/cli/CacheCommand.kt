package dev.sebastiano.indexino.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.core.cache.CacheActivityLock
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifest
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifestStore
import dev.sebastiano.indexino.core.cache.WorkspaceRegistryStore
import dev.sebastiano.indexino.core.cache.WorktreeOverlayPolicy
import dev.sebastiano.indexino.core.sourcelink.SourceLinkRegistryStore
import dev.sebastiano.indexino.engine.RuntimeLeaseStore
import dev.sebastiano.indexino.engine.RuntimePaths
import dev.sebastiano.indexino.model.LinkGenerationId
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import java.util.stream.Stream

internal class CacheCommand : CliktCommand(name = "cache") {
    init {
        subcommands(CacheStatusCliCommand(), CacheGcCliCommand(), CacheForgetCliCommand())
    }

    override fun run() = Unit
}

internal class CacheStatusCliCommand : CliktCommand(name = "status") {
    private val project by option("--project")

    override fun run() {
        echo(CacheMaintenance.status(InProcessCacheLayout.cacheRoot(), project?.let(Path::of)))
    }
}

internal class CacheGcCliCommand : CliktCommand(name = "gc") {
    override fun run() {
        echo(CacheMaintenance.gc(InProcessCacheLayout.cacheRoot()))
    }
}

internal class CacheForgetCliCommand : CliktCommand(name = "forget") {
    private val project by option("--project").required()

    override fun run() {
        val exitCode = CacheMaintenance.forget(InProcessCacheLayout.cacheRoot(), Path.of(project))
        if (exitCode != CliExitCodes.SUCCESS) throw ProgramResult(exitCode)
    }
}

@Suppress("TooManyFunctions")
internal object CacheMaintenance {
    fun status(cacheRoot: Path, project: Path? = null): String {
        val workspaceId = project?.let { InProcessCacheLayout.workspaceId(it.toRealPath()) }
        val workspaceRoot = workspaceId?.let { cacheRoot.resolve("workspaces").resolve(it) }
        val target = workspaceRoot ?: cacheRoot
        val files = regularFiles(target)
        val bytes = files.sumOf { Files.size(it) }
        return "cacheRoot=$cacheRoot files=${files.size} bytes=$bytes" +
            workspaceId?.let { " workspaceId=$it" }.orEmpty()
    }

    fun gc(cacheRoot: Path): String =
        CacheActivityLock.withExclusiveLock(cacheRoot) { collectGarbage(cacheRoot) }
            ?: "reclaimedPacks=0 reclaimedBytes=0 reclaimedMaterializations=0 " +
                "reclaimedClientRefs=0 activeRuntime=true"

    private fun collectGarbage(cacheRoot: Path): String {
        if (hasLiveRuntime(cacheRoot))
            return "reclaimedPacks=0 reclaimedBytes=0 reclaimedMaterializations=0 " +
                "reclaimedClientRefs=0 activeRuntime=true"
        val retainedGenerations = retainedGenerations(cacheRoot)
        val referenced =
            referencedPackKeys(cacheRoot).toMutableSet().apply {
                retainedGenerations?.forEach { (workspaceId, generation) ->
                    WorkspaceGenerationManifestStore(cacheRoot, workspaceId)
                        .readGeneration(generation)
                        ?.let { manifest ->
                            addAll(manifest.packKeys)
                            addAll(manifest.overlayPackKeys)
                        }
                }
            }
        val chunksRoot = cacheRoot.resolve("chunks")
        val packs =
            regularFiles(chunksRoot).filter { pack ->
                pack.fileName.toString().length == CONTENT_KEY_LENGTH
            }
        var reclaimedBytes = 0L
        var reclaimedPacks = 0
        packs.forEach { pack ->
            val key = pack.fileName.toString()
            if (key !in referenced) {
                reclaimedBytes += Files.size(pack)
                Files.deleteIfExists(pack)
                reclaimedPacks += 1
            }
        }
        val reclaimedMaterializations =
            retainedGenerations?.let { reclaimObsoleteMaterializations(cacheRoot, it) } ?: 0
        val reclaimedClientRefs = reclaimAbandonedClientRefs(cacheRoot)
        return "reclaimedPacks=$reclaimedPacks reclaimedBytes=$reclaimedBytes " +
            "reclaimedMaterializations=$reclaimedMaterializations " +
            "reclaimedClientRefs=$reclaimedClientRefs"
    }

    fun forget(cacheRoot: Path, project: Path): Int {
        val canonicalProject = project.toRealPath()
        val workspaceId = InProcessCacheLayout.workspaceId(canonicalProject)
        val leasePath = RuntimePaths.leasePath(cacheRoot, workspaceId)
        if (
            DaemonStopCommand().stop(canonicalProject, cacheRoot, deferCleanup = true) !=
                CliExitCodes.SUCCESS
        ) {
            return CliExitCodes.ANALYSIS_ERROR
        }
        repeat(FORGET_SHUTDOWN_WAIT_ATTEMPTS) {
            val outcome =
                CacheActivityLock.withExclusiveLock(cacheRoot) {
                    // The selected socket is gone and no compliant owner can hold an activity
                    // lease while this exclusive lock is held. Its leftover lease is stale.
                    val abandonedLease = leasePath.takeIf {
                        !Files.exists(RuntimePaths.socketPath(cacheRoot, workspaceId))
                    }
                    if (
                        hasLiveRuntime(cacheRoot, abandonedLease) ||
                            isBaseForCurrentWorkspace(cacheRoot, workspaceId)
                    ) {
                        return@withExclusiveLock CliExitCodes.ANALYSIS_ERROR
                    }
                    deleteTree(cacheRoot.resolve("workspaces").resolve(workspaceId))
                    WorkspaceRegistryStore(cacheRoot).remove(workspaceId)
                    Files.deleteIfExists(leasePath)
                    Files.deleteIfExists(RuntimePaths.tombstonePath(cacheRoot, workspaceId))
                    CliExitCodes.SUCCESS
                }
            if (outcome != null) return outcome
            Thread.sleep(FORGET_SHUTDOWN_WAIT_MILLIS)
        }
        return CliExitCodes.ANALYSIS_ERROR
    }

    private fun hasLiveRuntime(cacheRoot: Path, ignoredLease: Path? = null): Boolean =
        regularFiles(cacheRoot.resolve("runtime"))
            .filter { it.fileName.toString().endsWith(".lease.json") }
            .filterNot { it == ignoredLease }
            .mapNotNull { RuntimeLeaseStore.read(it) }
            .any(RuntimeLeaseStore::isLive)

    private fun isBaseForCurrentWorkspace(cacheRoot: Path, workspaceId: String): Boolean {
        val workspaces = cacheRoot.resolve("workspaces")
        if (!Files.isDirectory(workspaces)) return false
        Files.list(workspaces).use { roots ->
            return roots
                .filter(Files::isDirectory)
                .filter { it.fileName.toString() != workspaceId }
                .anyMatch { root ->
                    WorkspaceGenerationManifestStore(cacheRoot, root.fileName.toString())
                        .current()
                        ?.referencesWorkspace(cacheRoot, workspaceId) == true
                }
        }
    }

    private fun WorkspaceGenerationManifest.referencesWorkspace(
        cacheRoot: Path,
        workspaceId: String,
        visited: MutableSet<Pair<String, String>> = mutableSetOf(),
    ): Boolean {
        if (representation != WorktreeOverlayPolicy.REPRESENTATION_OVERLAY) return false
        val baseId = baseWorkspaceId ?: return false
        val generation = baseGeneration ?: return false
        if (baseId == workspaceId) return true
        if (!visited.add(baseId to generation)) return false
        return WorkspaceGenerationManifestStore(cacheRoot, baseId)
            .readGeneration(generation)
            ?.referencesWorkspace(cacheRoot, workspaceId, visited) == true
    }

    private fun referencedPackKeys(cacheRoot: Path): Set<String> {
        val workspaces = cacheRoot.resolve("workspaces")
        if (!Files.isDirectory(workspaces)) return emptySet()
        Files.list(workspaces).use { roots ->
            return roots
                .filter(Files::isDirectory)
                .flatMap { root ->
                    val manifest =
                        WorkspaceGenerationManifestStore(cacheRoot, root.fileName.toString())
                            .current()
                    manifest?.referencedPackKeys(cacheRoot)?.stream()
                        ?: java.util.stream.Stream.empty()
                }
                .collect(java.util.stream.Collectors.toSet())
        }
    }

    private fun WorkspaceGenerationManifest.referencedPackKeys(cacheRoot: Path): Set<String> {
        val keys = packKeys.toMutableSet()
        keys.addAll(overlayPackKeys)
        if (
            representation == WorktreeOverlayPolicy.REPRESENTATION_OVERLAY &&
                baseWorkspaceId != null &&
                baseGeneration != null
        ) {
            val base =
                WorkspaceGenerationManifestStore(cacheRoot, baseWorkspaceId)
                    .readGeneration(baseGeneration)
            if (base != null) keys.addAll(base.referencedPackKeys(cacheRoot))
        }
        return keys
    }

    private fun retainedGenerations(cacheRoot: Path): Set<Pair<String, String>>? {
        val workspaces = cacheRoot.resolve("workspaces")
        if (!Files.isDirectory(workspaces, LinkOption.NOFOLLOW_LINKS)) return emptySet()
        val roots =
            Files.list(workspaces).use { entries ->
                entries.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.toList()
            }
        val retained = mutableSetOf<Pair<String, String>>()
        fun retain(workspaceId: String, manifest: WorkspaceGenerationManifest): Boolean {
            if (!retained.add(workspaceId to manifest.generation)) return true
            if (manifest.representation == WorktreeOverlayPolicy.REPRESENTATION_OVERLAY) {
                val (baseId, base) = overlayBase(cacheRoot, manifest) ?: return false
                if (!retain(baseId, base)) return false
            }
            manifest.linkGeneration?.let { linkGeneration ->
                val providers =
                    linkedGenerations(cacheRoot, roots, workspaceId, linkGeneration) ?: return false
                if (providers.any { (id, linked) -> !retain(id, linked) }) return false
            }
            return true
        }
        var complete = true
        roots.forEach { root ->
            val currentPath = root.resolve("current")
            val current =
                WorkspaceGenerationManifestStore(cacheRoot, root.fileName.toString()).current()
            if (current != null) complete = retain(root.fileName.toString(), current) && complete
            else if (Files.exists(currentPath)) complete = false
        }
        return retained.takeIf { complete }
    }

    private fun overlayBase(
        cacheRoot: Path,
        manifest: WorkspaceGenerationManifest,
    ): Pair<String, WorkspaceGenerationManifest>? {
        val id = manifest.baseWorkspaceId ?: return null
        val generation = manifest.baseGeneration ?: return null
        val base =
            WorkspaceGenerationManifestStore(cacheRoot, id).readGeneration(generation)
                ?: return null
        return id to base
    }

    private fun linkedGenerations(
        cacheRoot: Path,
        roots: List<Path>,
        workspaceId: String,
        linkGeneration: String,
    ): List<Pair<String, WorkspaceGenerationManifest>>? {
        val links =
            SourceLinkRegistryStore(cacheRoot.resolve("workspaces").resolve(workspaceId))
                .readGeneration(LinkGenerationId.of(linkGeneration)) ?: return null
        val providers =
            links.registrations.map { registration ->
                roots.mapNotNull { root ->
                    val id = root.fileName.toString()
                    WorkspaceGenerationManifestStore(cacheRoot, id)
                        .readGeneration(registration.linkedGeneration)
                        ?.let { id to it }
                }
            }
        return providers.takeIf { matches -> matches.all { it.isNotEmpty() } }?.flatten()
    }

    private fun reclaimObsoleteMaterializations(
        cacheRoot: Path,
        retained: Set<Pair<String, String>>,
    ): Int {
        val workspaces = cacheRoot.resolve("workspaces")
        if (!Files.isDirectory(workspaces, LinkOption.NOFOLLOW_LINKS)) return 0
        var reclaimed = 0
        Files.list(workspaces).use { roots ->
            roots
                .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .flatMap { root ->
                    val generations = root.resolve("generations")
                    if (Files.isDirectory(generations, LinkOption.NOFOLLOW_LINKS)) {
                        Files.list(generations)
                            .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                            .filter {
                                root.fileName.toString() to it.fileName.toString() !in retained
                            }
                    } else {
                        Stream.empty<Path>()
                    }
                }
                .flatMap { generation ->
                    Stream.of(
                        generation.resolve("materialized"),
                        generation.resolve("overlay-delta"),
                    )
                }
                .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .forEach { expanded ->
                    deleteTree(expanded)
                    reclaimed++
                }
        }
        return reclaimed
    }

    private fun reclaimAbandonedClientRefs(cacheRoot: Path): Int {
        val workspaces = cacheRoot.resolve("workspaces")
        if (!Files.isDirectory(workspaces, LinkOption.NOFOLLOW_LINKS)) return 0
        var reclaimed = 0
        Files.list(workspaces).use { roots ->
            roots
                .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .flatMap { root ->
                    val refs = root.resolve("refs")
                    if (Files.isDirectory(refs, LinkOption.NOFOLLOW_LINKS)) Files.list(refs)
                    else Stream.empty<Path>()
                }
                .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .forEach { client ->
                    deleteTree(client)
                    reclaimed++
                }
        }
        return reclaimed
    }

    private const val CONTENT_KEY_LENGTH = 64
    private const val FORGET_SHUTDOWN_WAIT_ATTEMPTS = 250
    private const val FORGET_SHUTDOWN_WAIT_MILLIS = 20L

    private fun regularFiles(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        val files = mutableListOf<Path>()
        Files.walk(root).use { paths ->
            paths.forEach { path -> if (Files.isRegularFile(path)) files.add(path) }
        }
        return files
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
