package dev.sebastiano.indexino.topology.bazel

import java.nio.file.Path

internal object BazelCodeRoleQuery {
    fun targetSources(
        target: String,
        workspace: Path,
        runner: BazelProcessRunner,
    ): BazelQueryOutcome {
        val sources = linkedSetOf<String>()
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque<String>()
        pending += target
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            val aliasResult = run("kind('alias rule', $current)", workspace, runner)
            if (aliasResult.exitCode != 0) return aliasResult
            val isAlias = aliasResult.lines.any(::isLabel)
            val sourceResult =
                run(
                    if (isAlias) "kind('source file', labels(actual, $current))"
                    else "kind('source file', labels(srcs, $current))",
                    workspace,
                    runner,
                )
            if (sourceResult.exitCode != 0) return sourceResult
            sources += sourceResult.lines
            val ruleResult =
                run(
                    if (isAlias) "kind('rule', labels(actual, $current))"
                    else
                        "kind('filegroup rule', labels(srcs, $current)) union " +
                            "kind('alias rule', labels(srcs, $current))",
                    workspace,
                    runner,
                )
            if (ruleResult.exitCode != 0) return ruleResult
            pending += ruleResult.lines.filter(::isLabel)
        }
        return BazelQueryOutcome(0, sources.toList())
    }

    fun dependencySources(
        target: String,
        workspace: Path,
        runner: BazelProcessRunner,
    ): BazelQueryOutcome {
        val closure = "deps($target)"
        val compilationRules =
            "$closure except kind('filegroup rule', $closure) except kind('alias rule', $closure)"
        val initialSources =
            run(
                "kind('source file', labels(srcs, $compilationRules)) union " +
                    "kind('source file', labels(srcs, $target)) union " +
                    "kind('source file', labels(actual, $target))",
                workspace,
                runner,
            )
        if (initialSources.exitCode != 0) return initialSources
        val initialRules =
            run(
                "kind('filegroup rule', labels(srcs, $compilationRules)) union " +
                    "kind('alias rule', labels(srcs, $compilationRules)) union " +
                    "kind('(filegroup|alias) rule', labels(srcs, $target)) union " +
                    "kind('(filegroup|alias) rule', labels(actual, $target))",
                workspace,
                runner,
            )
        if (initialRules.exitCode != 0) return initialRules
        return expandCodeRules(initialSources.lines, initialRules.lines, workspace, runner)
    }

    private fun expandCodeRules(
        initialSources: List<String>,
        initialRules: List<String>,
        workspace: Path,
        runner: BazelProcessRunner,
    ): BazelQueryOutcome {
        val sources = initialSources.toMutableList()
        val visited = mutableSetOf<String>()
        var wave = initialRules.filter(::isLabel).distinct()
        while (wave.isNotEmpty()) {
            wave = wave.filter(visited::add)
            if (wave.isEmpty()) break
            val targets = "set(${wave.joinToString(" ")})"
            val sourceResult =
                run(
                    "kind('source file', labels(srcs, $targets)) union " +
                        "kind('source file', labels(actual, $targets))",
                    workspace,
                    runner,
                )
            if (sourceResult.exitCode != 0) return sourceResult
            sources += sourceResult.lines
            val ruleResult =
                run(
                    "kind('(filegroup|alias) rule', labels(srcs, $targets)) union " +
                        "kind('(filegroup|alias) rule', labels(actual, $targets))",
                    workspace,
                    runner,
                )
            if (ruleResult.exitCode != 0) return ruleResult
            wave = ruleResult.lines.filter(::isLabel).distinct()
        }
        return BazelQueryOutcome(0, sources.distinct())
    }

    private fun run(query: String, workspace: Path, runner: BazelProcessRunner): BazelQueryOutcome =
        runner.run(query, workspace)

    private fun isLabel(line: String): Boolean = line.startsWith("//") || line.startsWith("@")
}
