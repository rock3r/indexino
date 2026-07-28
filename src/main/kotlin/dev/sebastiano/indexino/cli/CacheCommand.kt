package dev.sebastiano.indexino.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifest
import dev.sebastiano.indexino.engine.RuntimeLeaseStore
import dev.sebastiano.indexino.engine.RuntimePaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlinx.serialization.json.Json

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

internal object CacheMaintenance {
    private val json = Json { ignoreUnknownKeys = true }

    fun status(cacheRoot: Path, project: Path? = null): String {
        val workspaceId = project?.let { InProcessCacheLayout.workspaceId(it.toRealPath()) }
        val workspaceRoot = workspaceId?.let { cacheRoot.resolve("workspaces").resolve(it) }
        val target = workspaceRoot ?: cacheRoot
        val files = regularFiles(target)
        val bytes = files.sumOf { Files.size(it) }
        return "cacheRoot=$cacheRoot files=${files.size} bytes=$bytes" +
            workspaceId?.let { " workspaceId=$it" }.orEmpty()
    }

    fun gc(cacheRoot: Path): String {
        if (hasLiveRuntime(cacheRoot)) return "reclaimedPacks=0 reclaimedBytes=0 activeRuntime=true"
        val referenced = referencedPackKeys(cacheRoot)
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
        return "reclaimedPacks=$reclaimedPacks reclaimedBytes=$reclaimedBytes"
    }

    fun forget(cacheRoot: Path, project: Path): Int {
        val canonicalProject = project.toRealPath()
        val workspaceId = InProcessCacheLayout.workspaceId(canonicalProject)
        if (DaemonStopCommand().stop(canonicalProject, cacheRoot) != CliExitCodes.SUCCESS) {
            return CliExitCodes.ANALYSIS_ERROR
        }
        deleteTree(cacheRoot.resolve("workspaces").resolve(workspaceId))
        Files.deleteIfExists(RuntimePaths.tombstonePath(cacheRoot, workspaceId))
        return CliExitCodes.SUCCESS
    }

    private fun hasLiveRuntime(cacheRoot: Path): Boolean =
        regularFiles(cacheRoot.resolve("runtime"))
            .filter { it.fileName.toString().endsWith(".lease") }
            .mapNotNull { RuntimeLeaseStore.read(it) }
            .any(RuntimeLeaseStore::isLive)

    private fun referencedPackKeys(cacheRoot: Path): Set<String> =
        regularFiles(cacheRoot.resolve("workspaces"))
            .filter { it.fileName.toString() == "manifest.json" }
            .flatMap { manifest ->
                json
                    .decodeFromString(
                        WorkspaceGenerationManifest.serializer(),
                        Files.readString(manifest),
                    )
                    .packKeys
                    .asSequence()
            }
            .toSet()

    private const val CONTENT_KEY_LENGTH = 64

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
