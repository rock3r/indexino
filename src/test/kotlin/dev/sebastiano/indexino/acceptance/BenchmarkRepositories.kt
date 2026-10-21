// SPDX-License-Identifier: UEL-1.0
package dev.sebastiano.indexino.acceptance

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Git operations only in marked disposable benchmark roots, including declared nested repos. */
internal class BenchmarkRepositories(
    private val root: Path,
    private val workspace: Path,
    plan: JsonObject,
    private val workload: IncrementalWorkload,
) : AutoCloseable {
    private val relative =
        listOf("") +
            plan["nestedRepositories"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    private val originals: Map<String, String>
    private val branches: Map<String, String?>
    private val created = mutableListOf<Pair<Path, Path>>()
    private val switched = mutableListOf<String>()
    val count: Int
        get() = relative.size

    init {
        require(relative.distinct().size == relative.size)
        relative.forEach { name ->
            val path = workspace.resolve(name).normalize()
            require(path.startsWith(workspace) && path.toRealPath() == path)
            require(Path.of(git(path, "rev-parse", "--show-toplevel").trim()).toRealPath() == path)
            require(git(path, "status", "--porcelain").isBlank()) { "Repository must be clean" }
        }
        workload.sources.forEach { source ->
            val owner =
                Path.of(git(source.path.parent, "rev-parse", "--show-toplevel").trim()).toRealPath()
            require(relative.any { workspace.resolve(it) == owner }) {
                "Undeclared nested repository"
            }
        }
        originals = heads(workspace)
        branches = relative.associateWith { name ->
            git(workspace.resolve(name), "rev-parse", "--abbrev-ref", "HEAD").trim().takeUnless {
                it == "HEAD"
            }
        }
    }

    fun detach() {
        relative.forEach {
            git(workspace.resolve(it), "checkout", "--detach", originals.getValue(it))
            switched += it
        }
    }

    fun heads(at: Path): Map<String, String> = relative.associateWith {
        git(at.resolve(it), "rev-parse", "HEAD").trim()
    }

    fun commit(at: Path): Map<String, String> {
        relative.asReversed().forEach { name ->
            val repo = at.resolve(name)
            val paths =
                workload.sources
                    .filter {
                        relative
                            .filter { other -> it.path.startsWith(workspace.resolve(other)) }
                            .maxBy(String::length) == name
                    }
                    .map { workspace.resolve(name).relativize(it.path).toString() }
            if (paths.isNotEmpty()) {
                git(repo, "add", "--", *paths.toTypedArray())
                git(
                    repo,
                    "-c",
                    "user.name=Indexino Benchmark",
                    "-c",
                    "user.email=benchmark@example.invalid",
                    "-c",
                    "commit.gpgsign=false",
                    "commit",
                    "-m",
                    "Controlled benchmark payload",
                )
            }
        }
        return heads(at)
    }

    fun add(at: Path, revisions: Map<String, String>) {
        relative.forEach { name ->
            val destination = at.resolve(name)
            require(!Files.exists(destination)) { "Sibling destination already exists" }
            git(
                workspace.resolve(name),
                "worktree",
                "add",
                "--detach",
                destination.toString(),
                revisions.getValue(name),
            )
            created.add(workspace.resolve(name) to destination)
        }
        check(heads(at) == revisions)
    }

    fun checkout(revisions: Map<String, String>) {
        relative.forEach {
            git(workspace.resolve(it), "checkout", "--detach", revisions.getValue(it))
        }
        check(heads(workspace) == revisions)
    }

    override fun close() {
        var failure: Exception? = null
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (error: Exception) {
                failure = failure ?: error
            }
        }
        created.asReversed().forEach { (owner, path) ->
            attempt { git(owner, "worktree", "remove", "--force", path.toString()) }
        }
        switched.asReversed().forEach { name ->
            attempt {
                val repo = workspace.resolve(name)
                git(repo, "reset", "--hard", originals.getValue(name))
                git(repo, "checkout", branches[name] ?: originals.getValue(name))
                check(git(repo, "status", "--porcelain").isBlank())
            }
        }
        failure?.let { throw it }
    }

    private fun git(at: Path, vararg arguments: String): String {
        val output = root.resolve("benchmark-git-output.txt")
        val process =
            ProcessBuilder(
                    listOf(
                        "git",
                        "-c",
                        "core.hooksPath=${root.resolve("no-hooks")}",
                        "-C",
                        at.toString(),
                    ) + arguments
                )
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start()
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            error("Benchmark Git deadline exceeded")
        }
        check(process.exitValue() == 0) { "Benchmark Git command failed" }
        return Files.readString(output)
    }
}
