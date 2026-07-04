package com.kotlincodeindex.topology

import java.nio.file.Path

data class TopologyResult(
    val sourceFiles: List<String>,
    val topology: String,
    val includeDeps: Boolean,
    val scope: String,
)

enum class BuildSystem {
    AUTO,
    BAZEL,
    GRADLE,
}

object BuildSystemDetector {
    fun detect(projectRoot: Path): BuildSystem? {
        val hasBazel = projectRoot.resolve("MODULE.bazel").toFile().exists() ||
            projectRoot.resolve("WORKSPACE").toFile().exists() ||
            projectRoot.resolve("WORKSPACE.bazel").toFile().exists()
        if (hasBazel) {
            return BuildSystem.BAZEL
        }
        val hasGradle = projectRoot.resolve("settings.gradle.kts").toFile().exists() ||
            projectRoot.resolve("settings.gradle").toFile().exists()
        if (hasGradle) {
            return BuildSystem.GRADLE
        }
        return null
    }
}
