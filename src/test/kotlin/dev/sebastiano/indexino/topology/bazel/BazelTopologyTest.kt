package dev.sebastiano.indexino.topology.bazel

import dev.sebastiano.indexino.topology.BuildSystem
import dev.sebastiano.indexino.topology.TopologyRequest
import dev.sebastiano.indexino.topology.TopologyResolver
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class BazelTopologyTest {
    @Test
    fun `mock query executor resolves kotlin source paths`() {
        val workspace = Path("src/test/resources/fixtures/bazel")
        val result =
            BazelTopology.resolveSources(
                target = "//plugins/foo/ui:ui",
                workspace = workspace,
                includeDeps = true,
                executor =
                    MockBazelQueryExecutor(
                        listOf(
                            "//plugins/foo/ui:src/main/kotlin/Panel.kt",
                            "//plugins/foo/ui:src/main/kotlin/Other.kt",
                        )
                    ),
            )
        assertEquals("bazel-query", result.topology)
        assertEquals(true, result.includeDeps)
        assertEquals(
            listOf(
                "plugins/foo/ui/src/main/kotlin/Panel.kt",
                "plugins/foo/ui/src/main/kotlin/Other.kt",
            ),
            result.sourceFiles,
        )
    }

    @Test
    fun `build parse fallback resolves sources without bazel`() {
        val workspace = Path("src/test/resources/fixtures/bazel")
        val labels = BazelTopology.degradedSourceLabels("//plugins/foo/ui:ui", workspace)
        val paths = BazelQueryResultParser.parseKotlinSourcePaths(labels)
        assertEquals(
            listOf(
                    "plugins/foo/ui/src/main/kotlin/Panel.kt",
                    "plugins/foo/ui/src/main/kotlin/Other.kt",
                )
                .sorted(),
            paths.sorted(),
        )
    }

    @Test
    fun `topology request without dependencies queries target source set`() {
        val queries = mutableListOf<String>()

        val result =
            TopologyResolver.resolve(
                project = Path("."),
                request =
                    TopologyRequest(
                        buildSystem = BuildSystem.BAZEL,
                        bazelTarget = "//plugins/foo/ui:ui",
                        includeDeps = false,
                    ),
                bazelProcessRunner = successfulRunner(queries),
            )

        assertEquals(
            listOf(
                "kind('source file', deps(labels(srcs, //plugins/foo/ui:ui))) union " +
                    "kind('source file', deps(labels(resource_files, //plugins/foo/ui:ui)))"
            ),
            queries,
        )
        assertEquals(false, result.includeDeps)
    }

    @Test
    fun `topology request with dependencies queries dependency closure`() {
        val queries = mutableListOf<String>()

        val result =
            TopologyResolver.resolve(
                project = Path("."),
                request =
                    TopologyRequest(
                        buildSystem = BuildSystem.BAZEL,
                        bazelTarget = "//plugins/foo/ui:ui",
                        includeDeps = true,
                    ),
                bazelProcessRunner = successfulRunner(queries),
            )

        assertEquals(listOf("kind('source file', deps(//plugins/foo/ui:ui))"), queries)
        assertEquals(true, result.includeDeps)
    }

    private fun successfulRunner(queries: MutableList<String>): BazelProcessRunner =
        BazelProcessRunner { query, _ ->
            queries += query
            BazelQueryOutcome(0, listOf("//plugins/foo/ui:src/main/kotlin/Panel.kt"))
        }

    @Test
    fun `build parse fallback selects only the requested target`() {
        val workspace = createTempDirectory("bazel-target-fallback-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir.resolve("src"))
        packageDir.resolve("src/A.kt").writeText("class A")
        packageDir.resolve("src/B.kt").writeText("class B")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "a",
                    srcs = ["src/A.kt"],
                )
                kt_jvm_library(
                    name = "b",
                    srcs = ["src/B.kt"],
                )
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(listOf("//pkg:src/A.kt"), labels)
    }

    @Test
    fun `build target selection ignores parentheses in strings and comments`() {
        val workspace = createTempDirectory("bazel-target-delimiters-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir.resolve("src"))
        packageDir.resolve("src/A.kt").writeText("class A")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "a",
                    tags = ["closing ) is data"],
                    # A comment may contain another ).
                    srcs = ["src/A.kt"],
                )
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(listOf("//pkg:src/A.kt"), labels)
    }

    @Test
    fun `build target selection expands referenced source filegroups only`() {
        val workspace = createTempDirectory("bazel-target-filegroup-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir.resolve("src"))
        packageDir.resolve("src/A.kt").writeText("class A")
        packageDir.resolve("src/B.kt").writeText("class B")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                filegroup(
                    name = "a_sources",
                    srcs = ["src/A.kt"],
                )
                kt_jvm_library(
                    name = "a",
                    srcs = [":a_sources"],
                )
                kt_jvm_library(
                    name = "b",
                    srcs = ["src/B.kt"],
                )
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(listOf("//pkg:src/A.kt"), labels)
    }

    @Test
    fun `build target selection matches standalone name attribute`() {
        val workspace = createTempDirectory("bazel-target-name-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir.resolve("src"))
        packageDir.resolve("src/A.kt").writeText("class A")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                custom_library(
                    module_name = "helper",
                    name = "a",
                    srcs = ["src/A.kt"],
                )
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(listOf("//pkg:src/A.kt"), labels)
    }

    @Test
    fun `build target selection expands path-like local source targets`() {
        val workspace = createTempDirectory("bazel-target-path-label-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir.resolve("src"))
        packageDir.resolve("src/A.kt").writeText("class A")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                filegroup(
                    name = "src/common",
                    srcs = ["src/A.kt"],
                )
                kt_jvm_library(
                    name = "a",
                    srcs = [":src/common"],
                )
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(listOf("//pkg:src/A.kt"), labels)
    }

    @Test
    fun `build target selection ignores name text inside strings`() {
        val workspace = createTempDirectory("bazel-target-name-string-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir.resolve("src"))
        packageDir.resolve("src/A.kt").writeText("class A")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                custom_library(
                    description = "name = 'helper'",
                    name = "a",
                    srcs = ["src/A.kt"],
                )
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(listOf("//pkg:src/A.kt"), labels)
    }
}
