package dev.sebastiano.indexino.topology.bazel

import java.nio.file.Path
import kotlin.io.path.readText

internal data class BazelProject(val directories: List<String>, val targets: List<String>)

internal object BazelProjectFile {
    fun parse(path: Path): BazelProject {
        val directories = mutableListOf<String>()
        val targets = mutableListOf<String>()
        var section: String? = null

        for (line in path.readText().lines()) {
            val trimmed = line.trim()
            when {
                trimmed == "directories:" -> section = "directories"
                trimmed == "targets:" -> section = "targets"
                trimmed.isEmpty() || trimmed.startsWith("#") -> continue
                section == "directories" -> directories += trimmed
                section == "targets" -> targets += trimmed
            }
        }
        return BazelProject(directories = directories, targets = targets)
    }
}
