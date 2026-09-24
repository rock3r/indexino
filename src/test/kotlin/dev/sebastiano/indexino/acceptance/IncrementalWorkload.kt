// SPDX-License-Identifier: UEL-1.0
package dev.sebastiano.indexino.acceptance

import dev.sebastiano.indexino.api.IndexSnapshot
import dev.sebastiano.indexino.model.CallQuery
import dev.sebastiano.indexino.model.NameMatchMode
import dev.sebastiano.indexino.model.SourceFile
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.SymbolId
import dev.sebastiano.indexino.model.SymbolQuery
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Controlled payloads appended to existing captured files; original source remains intact. */
internal class IncrementalWorkload(workspace: Path, plan: JsonObject) {
    internal class Source(
        val path: Path,
        val relative: String,
        val module: String,
        val packageName: String,
        val ordinal: Int,
        val original: String,
        val file: SourceFile,
    ) {
        fun name(version: Int): String = "IndexinoBenchmark${ordinal}V$version"

        fun fqn(version: Int): String =
            listOf(packageName, name(version)).filter(String::isNotEmpty).joinToString(".")

        fun content(version: Int): String {
            val parameters =
                if (relative.endsWith(".java")) {
                    if (version == 0) "int first" else "int first, int second"
                } else {
                    if (version == 0) "first: Int" else "first: Int, second: Int"
                }
            val expression = if (version == 0) "first" else "first + second"
            val arguments = if (version == 0) "17" else "17, 29"
            val body =
                if (relative.endsWith(".java")) {
                    "class ${name(version)} { static int probe($parameters) { return $expression; } " +
                        "static int caller() { return probe($arguments); } }"
                } else {
                    "class ${name(version)} { fun probe($parameters): Int = $expression; " +
                        "fun caller(): Int = probe($arguments) }"
                }
            return "$original\n$body\n"
        }
    }

    internal class Group(val id: String, val sources: List<Source>)

    val sources: List<Source> =
        plan
            .getValue("files")
            .jsonArray
            .mapIndexed { index, value ->
                val spec = value.jsonObject
                val relative = spec.getValue("path").jsonPrimitive.content
                val path = workspace.resolve(relative).normalize()
                require(
                    !Path.of(relative).isAbsolute &&
                        path.startsWith(workspace) &&
                        Files.isRegularFile(path)
                )
                require(
                    path.toRealPath().startsWith(workspace.toRealPath()) &&
                        !Files.isSymbolicLink(path)
                )
                require(relative.endsWith(".java") || relative.endsWith(".kt"))
                val original = Files.readString(path)
                require("IndexinoBenchmark" !in original) { "Benchmark marker collision" }
                Source(
                    path,
                    relative,
                    spec.getValue("module").jsonPrimitive.content,
                    spec.getValue("package").jsonPrimitive.content,
                    index,
                    original,
                    expectedFile(workspace, path),
                )
            }
            .also { require(it.isNotEmpty() && it.map(Source::path).distinct().size == it.size) }

    val groups: List<Group> =
        plan
            .getValue("groups")
            .jsonArray
            .map { value ->
                val spec = value.jsonObject
                val indices = spec.getValue("files").jsonArray.map { it.jsonPrimitive.int }
                require(indices.isNotEmpty() && indices.distinct().size == indices.size)
                Group(spec.getValue("id").jsonPrimitive.content, indices.map { sources[it] })
            }
            .also { require(it.isNotEmpty() && it.map(Group::id).distinct().size == it.size) }

    fun write(selected: List<Source>, version: Int) {
        selected.forEach { Files.writeString(it.path, it.content(version)) }
    }

    fun restoreOriginals() {
        sources.forEach { Files.writeString(it.path, it.original) }
    }

    suspend fun matches(
        snapshot: IndexSnapshot,
        selected: List<Source>,
        version: Int,
        onMismatch: (Int, String) -> Unit = { _, _ -> },
    ): Boolean {
        for (source in selected) {
            val current = collectPages {
                snapshot.findSymbols(
                    SymbolQuery.named(source.fqn(version)).withMatch(NameMatchMode.FQN),
                    it,
                )
            }
            if (
                current.size != 1 ||
                    current.single().name != source.name(version) ||
                    current.single().location.file != source.file
            ) {
                onMismatch(source.ordinal, "declaration")
                return false
            }
            val old = collectPages {
                snapshot.findSymbols(
                    SymbolQuery.named(source.fqn(1 - version)).withMatch(NameMatchMode.FQN),
                    it,
                )
            }
            if (old.isNotEmpty()) {
                onMismatch(source.ordinal, "oldDeclaration")
                return false
            }
            val methods = collectPages {
                snapshot.findSymbols(
                    SymbolQuery.named("${source.fqn(version)}#probe").withMatch(NameMatchMode.FQN),
                    it,
                )
            }
            if (
                methods.size != 1 ||
                    methods.single().arity != version + 1 ||
                    methods.single().location.file != source.file
            ) {
                onMismatch(source.ordinal, "method")
                return false
            }
            if (!callerMatches(snapshot, source, version, methods.single().id)) {
                onMismatch(source.ordinal, "caller")
                return false
            }
        }
        return true
    }

    private suspend fun callerMatches(
        snapshot: IndexSnapshot,
        source: Source,
        version: Int,
        method: SymbolId,
    ): Boolean {
        val callers = collectPages {
            snapshot.findSymbols(
                SymbolQuery.named("${source.fqn(version)}#caller").withMatch(NameMatchMode.FQN),
                it,
            )
        }
        if (callers.size != 1 || callers.single().location.file != source.file) return false
        val calls =
            collectPages { snapshot.findCalls(CallQuery.inFile(source.file), it) }
                .filter { it.enclosingSymbolId == callers.single().id }
        if (calls.size != 1) return false
        val call = calls.single()
        return call.calleeName == "probe" &&
            call.candidateSymbolIds == listOf(method) &&
            call.range.start.file == source.file &&
            call.arguments.map { it.position } == (0..version).toList()
    }

    private fun expectedFile(workspace: Path, source: Path): SourceFile {
        val owner =
            generateSequence(source.parent) { it.parent }
                .takeWhile { it.startsWith(workspace) }
                .firstOrNull { Files.exists(it.resolve(".git")) } ?: workspace
        val relativeOwner = workspace.relativize(owner).toString().replace('\\', '/')
        val origin = if (owner == workspace) "workspace" else "git:$relativeOwner"
        return SourceFile.of(
            SourceOriginId.of(origin),
            owner.relativize(source).toString().replace('\\', '/'),
            source.toString(),
        )
    }
}
