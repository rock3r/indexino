// SPDX-License-Identifier: UEL-1.0
package dev.sebastiano.indexino.acceptance

import com.sun.management.UnixOperatingSystemMXBean
import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.IndexSnapshot
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.IndexinoConfiguration
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.RuntimeAttachMode
import dev.sebastiano.indexino.cli.CacheMaintenance
import dev.sebastiano.indexino.model.NameMatchMode
import dev.sebastiano.indexino.model.SymbolQuery
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Opt-in resource accounting over invented Git worktrees, never a containment benchmark. */
internal object LifecycleSoakAcceptanceDriver {
    private const val SOURCE = "src/main/kotlin/Marker.kt"
    private val request = RefreshRequest.forScope(IndexScope.gradle(":").includingDependencies())

    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        require(args.size == 3) { "new-disposable-root cycles[2..200] report.json" }
        val root = Path.of(args[0]).toAbsolutePath().normalize()
        val count = args[1].toInt()
        val output = Path.of(args[2]).toAbsolutePath().normalize()
        require(count in 2..200)
        require(!Files.exists(root, LinkOption.NOFOLLOW_LINKS))
        require(!output.startsWith(root) && Files.isDirectory(output.parent))
        Files.createDirectory(root)
        val previousCache = System.getProperty("indexino.cache.dir")
        val cycles = mutableListOf<JsonObject>()
        val report = linkedMapOf<String, JsonElement>("status" to JsonPrimitive("incomplete"))
        try {
            AutoCloseable { deleteRoot(root) }
                .use {
                    System.setProperty("indexino.cache.dir", root.resolve("cache").toString())
                    withTimeout(10.minutes) { exercise(root, count, cycles, report) }
                }
            report["status"] = JsonPrimitive("passed")
        } catch (error: Exception) {
            report["failureType"] = JsonPrimitive(error.javaClass.simpleName)
            throw error
        } finally {
            if (previousCache == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCache)
            report["cleanupVerified"] = JsonPrimitive(!Files.exists(root))
            report["cycles"] = JsonArray(cycles)
            report["requestedCycles"] = JsonPrimitive(count)
            report["schema"] = JsonPrimitive(1)
            report["runtimeMode"] = JsonPrimitive("IN_PROCESS; auto-refresh disabled")
            report["resourceThresholds"] = JsonPrimitive("report-only; none invented")
            report["containmentProof"] = JsonPrimitive(false)
            for (policy in listOf("age", "quota", "grace", "daemonPurge")) {
                report["${policy}Policy"] = JsonPrimitive("not-implemented")
            }
            Files.writeString(output, JsonObject(report).toString() + "\n")
        }
    }

    private suspend fun exercise(
        root: Path,
        count: Int,
        cycles: MutableList<JsonObject>,
        report: MutableMap<String, JsonElement>,
    ) {
        val trees = createWorktrees(root)
        val cache = root.resolve("cache")
        report["baseline"] = resources(cache)
        val pins = mutableListOf<IndexSnapshot>()
        report["phase"] = JsonPrimitive("seed-pins")
        try {
            for (tree in trees) {
                connect(tree).use { client ->
                    client.refresh(request).await()
                    pins += client.snapshot()
                }
            }
            repeat(count) { ordinal ->
                val cycle = ordinal + 1
                report["phase"] = JsonPrimitive("mutate-and-check-protected-gc")
                report["activeCycle"] = JsonPrimitive(cycle)
                val mutations = trees.mapIndexed { tree, workspace ->
                    mutate(workspace, tree, cycle)
                }
                pins.forEach { assertRow(it, "Seed") }
                val beforeGc = packs(cache)
                check(CacheMaintenance.gc(cache).contains("activeRuntime=true"))
                check(packs(cache) == beforeGc) { "GC removed packs while old pins were live" }
                pins.forEach { assertRow(it, "Seed") }
                cycles += buildJsonObject {
                    put("cycle", cycle)
                    put("mutations", JsonArray(mutations))
                    put("resources", resources(cache))
                    put("oldPinsVerified", true)
                    put("protectedGcVerified", true)
                }
            }
        } finally {
            pins.asReversed().forEach(IndexSnapshot::close)
        }
        report["phase"] = JsonPrimitive("closed-pins-gc")
        report["afterClose"] = resources(cache)
        check(refFiles(cache).isEmpty()) { "Closed clients and pins retained materialized refs" }
        val beforeGc = packs(cache)
        val gc = CacheMaintenance.gc(cache)
        check(!gc.contains("activeRuntime=true")) { "Closed activity still blocks GC" }
        val reclaimed = beforeGc - packs(cache)
        check(reclaimed.isNotEmpty()) { "Superseded packs were not reclaimed" }
        report["reclaimedPacks"] = JsonPrimitive(reclaimed.size)
        report["afterGc"] = resources(cache)
        report["phase"] = JsonPrimitive("post-gc-queries")
        trees.forEachIndexed { tree, workspace ->
            connect(workspace).use { client ->
                client.snapshot().use { assertRow(it, "Tree${tree}Cycle$count") }
            }
        }
        check(refFiles(cache).isEmpty()) { "Post-GC verification retained refs" }
        report["queriesAfterGcVerified"] = JsonPrimitive(true)
        report["phase"] = JsonPrimitive("complete")
    }

    private suspend fun mutate(workspace: Path, tree: Int, cycle: Int): JsonObject =
        connect(workspace).use { client ->
            client.snapshot().use { old ->
                val previous = if (cycle == 1) "Seed" else "Tree${tree}Cycle${cycle - 1}"
                val oldRow = assertRow(old, previous)
                val name = "Tree${tree}Cycle$cycle"
                Files.writeString(workspace.resolve(SOURCE), "package fixture.soak\nclass $name\n")
                check(client.refresh(request).await().generation != old.generation)
                val newRow = client.snapshot().use { assertRow(it, name) }
                check(assertRow(old, previous) == oldRow)
                buildJsonObject {
                    put("worktree", tree)
                    put("oldRow", oldRow)
                    put("newRow", newRow)
                }
            }
        }

    private suspend fun assertRow(snapshot: IndexSnapshot, name: String): String {
        val symbols = collectPages {
            snapshot.findSymbols(
                SymbolQuery.named("fixture.soak.").withMatch(NameMatchMode.PREFIX),
                it,
            )
        }
        check(symbols.size == 1) { "Unexpected symbol multiplicity" }
        val symbol = symbols.single()
        check(
            symbol.name == name && symbol.location.file.path == SOURCE && symbol.location.line == 2
        ) {
            "Incorrect public symbol result"
        }
        return "${symbol.name}:${symbol.location.line}"
    }

    private suspend fun connect(workspace: Path): Indexino =
        Indexino.connect(
            IndexinoConfiguration.forWorkspace(workspace)
                .withRuntimeAttach(RuntimeAttachMode.IN_PROCESS)
                .withAutoRefresh(AutoRefreshMode.DISABLED)
        )

    private fun resources(cache: Path): JsonObject {
        val files = files(cache)
        val refs = refFiles(cache)
        val descriptors =
            (ManagementFactory.getOperatingSystemMXBean() as? UnixOperatingSystemMXBean)
                ?.openFileDescriptorCount
        val descendants =
            runCatching { ProcessHandle.current().descendants().use { it.count() } }.getOrNull()
        val memory = ManagementFactory.getMemoryMXBean()
        return buildJsonObject {
            put("refFiles", refs.size)
            put("refMaterializations", refs.map(Path::getParent).distinct().size)
            put("logicalCacheBytes", files.sumOf(Files::size))
            put("liveThreads", ManagementFactory.getThreadMXBean().threadCount)
            put("openFileDescriptors", descriptors)
            put(
                "fdUnavailableReason",
                if (descriptors == null) "No UnixOperatingSystemMXBean" else null,
            )
            put("observedDescendants", descendants)
            put("observedProcessesIncludingSelf", descendants?.plus(1))
            put(
                "processUnavailableReason",
                if (descendants == null) "ProcessHandle unavailable" else null,
            )
            put("heapUsedBytes", memory.heapMemoryUsage.used)
            put("nonHeapUsedBytes", memory.nonHeapMemoryUsage.used)
            put("rssBytes", JsonNull)
            put("rssUnavailableReason", "No portable process RSS collector")
            put("allocatedCacheBytes", JsonNull)
            put(
                "allocatedBytesUnavailableReason",
                "Logical file lengths are not allocated disk blocks",
            )
        }
    }

    private fun files(root: Path): List<Path> =
        if (!Files.isDirectory(root)) emptyList()
        else Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList() }

    private fun refFiles(cache: Path): List<Path> =
        files(cache.resolve("workspaces")).filter { path ->
            cache.relativize(path).any { it.toString() == "refs" }
        }

    private fun packs(cache: Path): Set<Path> = files(cache.resolve("chunks")).toSet()

    private fun deleteRoot(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                // Git object files can carry the DOS read-only bit on Windows.
                Files.getFileAttributeView(path, DosFileAttributeView::class.java)?.let { view ->
                    if (view.readAttributes().isReadOnly) view.setReadOnly(false)
                }
                Files.delete(path)
            }
        }
    }

    private fun createWorktrees(root: Path): List<Path> {
        Files.writeString(root.resolve("empty.gitconfig"), "")
        Files.createDirectory(root.resolve("empty-hooks"))
        val repository = Files.createDirectory(root.resolve("repository"))
        Files.createDirectories(repository.resolve(SOURCE).parent)
        Files.writeString(repository.resolve(SOURCE), "package fixture.soak\nclass Seed\n")
        Files.writeString(
            repository.resolve("settings.gradle.kts"),
            "rootProject.name = \"soak\"\n",
        )
        git(root, repository, "init", "--template=", "--initial-branch=fixture")
        git(root, repository, "add", ".")
        git(root, repository, "commit", "-m", "Invented soak seed")
        return listOf("left", "right").map { name ->
            root.resolve(name).also {
                git(root, repository, "worktree", "add", "--detach", it.toString())
            }
        }
    }

    private fun git(root: Path, workspace: Path, vararg arguments: String) {
        val builder =
            ProcessBuilder(
                "git",
                "-C",
                workspace.toString(),
                "-c",
                "commit.gpgsign=false",
                "-c",
                "user.name=Fixture",
                "-c",
                "user.email=fixture@example.invalid",
                "-c",
                "core.hooksPath=${root.resolve("empty-hooks")}",
                *arguments,
            )
        builder.environment().keys.removeIf { it.startsWith("GIT_") }
        builder.environment()["GIT_CONFIG_NOSYSTEM"] = "1"
        builder.environment()["GIT_CONFIG_GLOBAL"] = root.resolve("empty.gitconfig").toString()
        val process =
            builder
                .redirectErrorStream(true)
                .redirectOutput(root.resolve("git.log").toFile())
                .start()
        try {
            check(process.waitFor(30, TimeUnit.SECONDS)) { "Disposable Git command timed out" }
            check(process.exitValue() == 0) { "Disposable Git command failed" }
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                check(process.waitFor(5, TimeUnit.SECONDS)) {
                    "Disposable Git process did not exit"
                }
            }
        }
    }
}
