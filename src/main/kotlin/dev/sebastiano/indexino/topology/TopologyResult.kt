package dev.sebastiano.indexino.topology

import java.nio.file.Path

internal data class ExternalSourceMount(
    val root: Path,
    val sourceFiles: List<String>,
    val originId: String? = null,
    val expectedRevision: String? = null,
)

internal data class TopologyResult(
    val sourceFiles: List<String>,
    val topology: String,
    val includeDeps: Boolean,
    val scope: String,
    val externalMounts: List<Path> = emptyList(),
    val externalSources: List<ExternalSourceMount> = emptyList(),
)

internal enum class BuildSystem {
    AUTO,
    BAZEL,
    GRADLE,
    REPO,
}

internal object BuildSystemDetector {
    fun detect(projectRoot: Path): BuildSystem? {
        val hasBazel =
            projectRoot.resolve("MODULE.bazel").toFile().exists() ||
                projectRoot.resolve("WORKSPACE").toFile().exists() ||
                projectRoot.resolve("WORKSPACE.bazel").toFile().exists()
        if (hasBazel) {
            return BuildSystem.BAZEL
        }
        val hasGradle =
            projectRoot.resolve("settings.gradle.kts").toFile().exists() ||
                projectRoot.resolve("settings.gradle").toFile().exists()
        if (hasGradle) {
            return BuildSystem.GRADLE
        }
        if (projectRoot.resolve(".repo/manifest.xml").toFile().isFile) {
            return BuildSystem.REPO
        }
        return null
    }
}
