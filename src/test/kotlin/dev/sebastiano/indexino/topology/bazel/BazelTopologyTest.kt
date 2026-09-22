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
import kotlin.test.assertNotNull

class BazelTopologyTest {
    @Test
    fun `dependency role query excludes resource-only filegroup srcs`() {
        val queries = mutableListOf<String>()
        val inventory = listOf("//pkg:Main.java", "//pkg:config/preview.java", "//pkg:Dual.java")
        val result =
            BazelTopology.queryWithFallback(
                target = "//pkg:lib",
                workspace = Path("."),
                includeDeps = true,
                runner =
                    BazelProcessRunner { query, _ ->
                        queries += query
                        when {
                            query == "kind('source file', deps(//pkg:lib))" ->
                                BazelQueryOutcome(0, inventory)
                            query.startsWith("kind('source file', labels(srcs") ->
                                BazelQueryOutcome(0, listOf("//pkg:Main.java", "//pkg:Dual.java"))
                            query.contains("kind('filegroup rule'") ||
                                query.contains("kind('alias rule'") ->
                                BazelQueryOutcome(0, emptyList())
                            else -> error("unexpected query: $query")
                        }
                    },
                onStderr = {},
            )

        assertEquals(inventory, result.lines)
        assertEquals(listOf("//pkg:Main.java", "//pkg:Dual.java"), result.codeLines)
        assertEquals(
            false,
            queries.any { it == "kind('source file', labels(srcs, deps(//pkg:lib)))" },
        )
    }

    @Test
    fun `live target query classifies srcs independently from resources`() {
        val source = "//pkg:src/Main.java"
        val resourceNamedJava = "//pkg:resources/config/preview.java"
        val dualRole = "//pkg:src/Dual.java"
        val result =
            BazelTopology.resolveSources(
                target = "//pkg:lib",
                workspace = Path("."),
                includeDeps = false,
                processRunner =
                    BazelProcessRunner { query, _ ->
                        when {
                            query == "kind('alias rule', //pkg:lib)" ->
                                BazelQueryOutcome(0, emptyList())
                            query.contains("filegroup rule") -> BazelQueryOutcome(0, emptyList())
                            query.contains("labels(srcs, //pkg:lib)") &&
                                !query.contains("resource_files") ->
                                BazelQueryOutcome(0, listOf(source, dualRole))
                            query.contains("labels(srcs, //pkg:lib)") ->
                                BazelQueryOutcome(0, listOf(source, resourceNamedJava, dualRole))
                            else -> error("unexpected query: $query")
                        }
                    },
                onStderr = {},
            )

        assertEquals(setOf("pkg/src/Main.java", "pkg/src/Dual.java"), codeSourceFiles(result))
        assertEquals(
            listOf("pkg/src/Main.java", "pkg/resources/config/preview.java", "pkg/src/Dual.java"),
            result.sourceFiles,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun codeSourceFiles(result: Any): Set<String> {
        val getter =
            assertNotNull(result.javaClass.methods.singleOrNull { it.name == "getCodeSourceFiles" })
        return assertNotNull(getter.invoke(result) as Set<String>?)
    }

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
    fun `build parse uses BUILD roles rather than java filename for classification`() {
        val workspace = createTempDirectory("bazel-role-fallback-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir.resolve("src"))
        Files.createDirectories(
            packageDir.resolve("resources/codeVisionProviders/java.configuration")
        )
        packageDir.resolve("src/Main.java").writeText("class Main {}")
        packageDir
            .resolve("resources/codeVisionProviders/java.configuration/preview.java")
            .writeText("java=21.0.1-tem")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                jvm_library(
                    name = "lib",
                    srcs = glob(["src/**/*.java"]),
                    resources = glob(["resources/**/*"]),
                )
                """
                    .trimIndent()
            )

        val result =
            BazelTopology.resolveSources(
                target = "//pkg:lib",
                workspace = workspace,
                includeDeps = false,
                processRunner = BazelProcessRunner { _, _ -> BazelQueryOutcome(1, emptyList()) },
                onStderr = {},
            )

        assertEquals(
            listOf(
                "pkg/src/Main.java",
                "pkg/resources/codeVisionProviders/java.configuration/preview.java",
            ),
            result.sourceFiles,
        )
        assertEquals(setOf("pkg/src/Main.java"), result.codeSourceFiles)
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
                "kind('alias rule', //plugins/foo/ui:ui)",
                "kind('source file', labels(srcs, //plugins/foo/ui:ui)) union " +
                    "kind('source file', labels(resources, //plugins/foo/ui:ui)) union " +
                    "kind('source file', labels(resource_files, //plugins/foo/ui:ui)) union " +
                    "kind('source file', labels(data, //plugins/foo/ui:ui))",
                "kind('filegroup rule', labels(srcs, //plugins/foo/ui:ui)) union " +
                    "kind('alias rule', labels(srcs, //plugins/foo/ui:ui)) union " +
                    "kind('filegroup rule', labels(resources, //plugins/foo/ui:ui)) union " +
                    "kind('alias rule', labels(resources, //plugins/foo/ui:ui)) union " +
                    "kind('filegroup rule', labels(resource_files, //plugins/foo/ui:ui)) union " +
                    "kind('alias rule', labels(resource_files, //plugins/foo/ui:ui)) union " +
                    "kind('filegroup rule', labels(data, //plugins/foo/ui:ui)) union " +
                    "kind('alias rule', labels(data, //plugins/foo/ui:ui))",
                "kind('alias rule', //plugins/foo/ui:ui)",
                "kind('source file', labels(srcs, //plugins/foo/ui:ui))",
                "kind('filegroup rule', labels(srcs, //plugins/foo/ui:ui)) union " +
                    "kind('alias rule', labels(srcs, //plugins/foo/ui:ui))",
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

        assertEquals("kind('source file', deps(//plugins/foo/ui:ui))", queries.first())
        assertEquals(3, queries.size)
        assertEquals(true, queries[1].contains("except kind('filegroup rule'"))
        assertEquals(true, queries[1].contains("except kind('alias rule'"))
        assertEquals(true, result.includeDeps)
    }

    @Test
    fun `target-only query recursively expands filegroups without dependency closure`() {
        val queries = mutableListOf<String>()
        val target = "//plugins/foo/ui:ui"
        val filegroup = "//plugins/foo/ui:ui_sources"

        val result =
            BazelTopology.queryWithFallback(
                target = target,
                workspace = Path("."),
                includeDeps = false,
                runner =
                    BazelProcessRunner { query, _ ->
                        queries += query
                        when {
                            query.startsWith("kind('alias rule', //") ->
                                BazelQueryOutcome(0, emptyList())
                            query.contains("filegroup rule") &&
                                query.contains("labels(srcs, $target)") ->
                                BazelQueryOutcome(0, listOf("INFO: query completed", filegroup))
                            query.contains("filegroup rule") -> BazelQueryOutcome(0, emptyList())
                            query.contains(filegroup) ->
                                BazelQueryOutcome(
                                    0,
                                    listOf("//plugins/foo/ui:src/main/kotlin/Panel.kt"),
                                )
                            else -> BazelQueryOutcome(0, emptyList())
                        }
                    },
                onStderr = {},
            )

        assertEquals(
            listOf("plugins/foo/ui/src/main/kotlin/Panel.kt"),
            BazelQueryResultParser.parseKotlinSourcePaths(result.lines),
        )
        assertEquals(12, queries.size)
        assertEquals(false, queries.any { it.contains("deps(") })
    }

    @Test
    fun `target-only query follows aliases through actual edges`() {
        val alias = "//plugins/foo/ui:ui_sources_alias"

        val result =
            BazelTopology.queryWithFallback(
                target = "//plugins/foo/ui:ui",
                workspace = Path("."),
                includeDeps = false,
                runner =
                    BazelProcessRunner { query, _ ->
                        when {
                            query == "kind('alias rule', //plugins/foo/ui:ui)" ->
                                BazelQueryOutcome(0, emptyList())
                            query == "kind('alias rule', $alias)" ->
                                BazelQueryOutcome(0, listOf(alias))
                            query.contains("alias rule") && query.contains("ui:ui)") ->
                                BazelQueryOutcome(0, listOf(alias))
                            query.contains("alias rule") -> BazelQueryOutcome(0, emptyList())
                            query.contains("labels(actual, $alias)") ->
                                BazelQueryOutcome(
                                    0,
                                    listOf("//plugins/foo/ui:src/main/kotlin/Panel.kt"),
                                )
                            else -> BazelQueryOutcome(0, emptyList())
                        }
                    },
                onStderr = {},
            )

        assertEquals(
            listOf("plugins/foo/ui/src/main/kotlin/Panel.kt"),
            BazelQueryResultParser.parseKotlinSourcePaths(result.lines),
        )
    }

    @Test
    fun `target-only query follows alias actual ordinary rules`() {
        val alias = "//plugins/foo/ui:ui_alias"
        val actual = "//plugins/foo/ui:ui"

        val result =
            BazelTopology.queryWithFallback(
                target = alias,
                workspace = Path("."),
                includeDeps = false,
                runner =
                    BazelProcessRunner { query, _ ->
                        when {
                            query == "kind('alias rule', $alias)" ->
                                BazelQueryOutcome(0, listOf(alias))
                            query == "kind('alias rule', $actual)" ->
                                BazelQueryOutcome(0, emptyList())
                            query == "kind('rule', labels(actual, $alias))" ->
                                BazelQueryOutcome(0, listOf(actual))
                            query.contains("labels(srcs, $actual)") ->
                                BazelQueryOutcome(0, listOf("//plugins/foo/ui:Panel.kt"))
                            else -> BazelQueryOutcome(0, emptyList())
                        }
                    },
                onStderr = {},
            )

        assertEquals(
            listOf("plugins/foo/ui/Panel.kt"),
            BazelQueryResultParser.parseKotlinSourcePaths(result.lines),
        )
    }

    @Test
    fun `target-only query recognizes canonical label returned for shorthand alias`() {
        val alias = "//plugins/foo/ui"
        val canonicalAlias = "//plugins/foo/ui:ui"

        val result =
            BazelTopology.queryWithFallback(
                target = alias,
                workspace = Path("."),
                includeDeps = false,
                runner =
                    BazelProcessRunner { query, _ ->
                        when {
                            query == "kind('alias rule', $alias)" ->
                                BazelQueryOutcome(0, listOf(canonicalAlias))
                            query == "kind('source file', labels(actual, $alias))" ->
                                BazelQueryOutcome(0, listOf("//plugins/foo/ui:Panel.kt"))
                            else -> BazelQueryOutcome(0, emptyList())
                        }
                    },
                onStderr = {},
            )

        assertEquals(
            listOf("plugins/foo/ui/Panel.kt"),
            BazelQueryResultParser.parseKotlinSourcePaths(result.lines),
        )
    }

    private fun successfulRunner(queries: MutableList<String>): BazelProcessRunner =
        BazelProcessRunner { query, _ ->
            queries += query
            BazelQueryOutcome(
                0,
                if (query.contains("filegroup rule") || query.startsWith("kind('alias rule', //")) {
                    emptyList()
                } else {
                    listOf("//plugins/foo/ui:src/main/kotlin/Panel.kt")
                },
            )
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

    @Test
    fun `build target selection ignores commented local source targets`() {
        val workspace = createTempDirectory("bazel-target-commented-label-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir.resolve("src"))
        packageDir.resolve("src/A.kt").writeText("class A")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "a",
                    srcs = [
                        # ":removed_sources",
                        "src/A.kt",
                    ],
                )
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(listOf("//pkg:src/A.kt"), labels)
    }

    @Test
    fun `build target selection keeps direct file labels out of rule recursion`() {
        val workspace = createTempDirectory("bazel-target-direct-label-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir)
        packageDir.resolve("Foo.kt").writeText("class Foo")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "a",
                    srcs = [":Foo.kt"],
                )
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(listOf("//pkg:Foo.kt"), labels)
    }

    @Test
    fun `build target selection expands canonical same-package filegroups`() {
        val workspace = createTempDirectory("bazel-target-canonical-label-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir.resolve("src"))
        packageDir.resolve("src/A.kt").writeText("class A")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                filegroup(
                    name = "sources",
                    srcs = ["src/A.kt"],
                )
                kt_jvm_library(
                    name = "a",
                    srcs = ["//pkg:sources"],
                )
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(listOf("//pkg:src/A.kt"), labels)
    }

    @Test
    fun `build target selection expands shorthand same-package filegroups`() {
        val workspace = createTempDirectory("bazel-target-shorthand-label-")
        val packageDir = workspace.resolve("foo")
        Files.createDirectories(packageDir.resolve("src"))
        packageDir.resolve("src/A.kt").writeText("class A")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                filegroup(
                    name = "foo",
                    srcs = ["src/A.kt"],
                )
                kt_jvm_library(
                    name = "a",
                    srcs = ["//foo"],
                )
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//foo:a", workspace)

        assertEquals(listOf("//foo:src/A.kt"), labels)
    }

    @Test
    fun `build target selection follows actual for alias rules`() {
        val workspace = createTempDirectory("bazel-target-build-alias-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir.resolve("src"))
        packageDir.resolve("src/A.kt").writeText("class A")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                filegroup(
                    name = "sources",
                    srcs = ["src/A.kt"],
                )
                alias(
                    name = "a",
                    actual = ":sources",
                )
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(listOf("//pkg:src/A.kt"), labels)
    }

    @Test
    fun `build target selection follows aliases to ordinary rules`() {
        val workspace = createTempDirectory("bazel-target-build-alias-rule-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir)
        packageDir.resolve("Foo.kt").writeText("class Foo")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "lib",
                    srcs = ["Foo.kt"],
                )
                alias(
                    name = "a",
                    actual = ":lib",
                )
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(listOf("//pkg:Foo.kt"), labels)
    }

    @Test
    fun `build target selection preserves direct files referenced by aliases`() {
        val workspace = createTempDirectory("bazel-target-build-file-alias-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir)
        packageDir.resolve("Foo.kt").writeText("class Foo")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                alias(
                    name = "a",
                    actual = ":Foo.kt",
                )
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(listOf("//pkg:Foo.kt"), labels)
    }

    @Test
    fun `build target selection recognizes whitespace before alias parenthesis`() {
        val workspace = createTempDirectory("bazel-target-build-spaced-alias-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir.resolve("src"))
        packageDir.resolve("src/A.kt").writeText("class A")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                filegroup(
                    name = "sources",
                    srcs = ["src/A.kt"],
                )
                alias (
                    name = "a",
                    actual = ":sources",
                )
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(listOf("//pkg:src/A.kt"), labels)
    }

    @Test
    fun `build target selection does not recurse into generated rules`() {
        val workspace = createTempDirectory("bazel-target-build-generated-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir)
        packageDir.resolve("Input.kt").writeText("class Input")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                genrule(
                    name = "generated",
                    srcs = ["Input.kt"],
                    outs = ["Generated.kt"],
                    cmd = "cp ${'$'}< ${'$'}@",
                )
                kt_jvm_library(
                    name = "a",
                    srcs = [":generated"],
                )
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(emptyList(), labels)
    }

    @Test
    fun `build target selection preserves inline direct file aliases`() {
        val workspace = createTempDirectory("bazel-target-build-inline-file-alias-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir)
        packageDir.resolve("Foo.kt").writeText("class Foo")
        packageDir.resolve("BUILD.bazel").writeText("alias(name = \"a\", actual = \":Foo.kt\")")

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(listOf("//pkg:Foo.kt"), labels)
    }

    @Test
    fun `build target selection ignores commented direct file alias actuals`() {
        val workspace = createTempDirectory("bazel-target-build-commented-file-alias-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir)
        packageDir.resolve("Foo.kt").writeText("class Foo")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                alias(
                    name = "a",
                    # actual = ":Old.kt",
                    actual = ":Foo.kt",
                )
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(listOf("//pkg:Foo.kt"), labels)
    }

    @Test
    fun `build target selection ignores rule calls inside multiline strings`() {
        val workspace = createTempDirectory("bazel-target-build-string-rule-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir)
        packageDir.resolve("Foo.kt").writeText("class Foo")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "a",
                    srcs = ["Foo.kt"],
                    description = '''
                kt_jvm_library(
                    name = "a",
                    srcs = ["Wrong.kt"],
                )
                    ''',
                )
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(listOf("//pkg:Foo.kt"), labels)
    }

    @Test
    fun `build target selection normalizes cross-package direct files`() {
        val workspace = createTempDirectory("bazel-target-cross-package-file-")
        val packageDir = workspace.resolve("pkg")
        val sharedDir = workspace.resolve("shared")
        Files.createDirectories(packageDir)
        Files.createDirectories(sharedDir)
        sharedDir.resolve("Foo.kt").writeText("class Foo")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "a",
                    srcs = ["//shared:Foo.kt"],
                )
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(listOf("//shared:Foo.kt"), labels)
    }

    @Test
    fun `build target selection preserves canonical root direct files`() {
        val workspace = createTempDirectory("bazel-target-root-file-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir)
        workspace.resolve("Foo.kt").writeText("class Foo")
        packageDir
            .resolve("BUILD.bazel")
            .writeText("kt_jvm_library(name = \"a\", srcs = ['//:Foo.kt'])")

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(listOf("//:Foo.kt"), labels)
    }

    @Test
    fun `build target selection prefers declared file-like aggregators over files`() {
        val workspace = createTempDirectory("bazel-target-file-like-filegroup-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir)
        packageDir.resolve("Foo.kt").writeText("class Foo")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                filegroup(name = "sources.kt", srcs = ["Foo.kt"])
                kt_jvm_library(name = "a", srcs = [":sources.kt"])
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(listOf("//pkg:Foo.kt"), labels)
    }

    @Test
    fun `build target selection keeps rule boundaries across triple quoted strings`() {
        val workspace = createTempDirectory("bazel-target-triple-boundary-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir)
        packageDir.resolve("Foo.kt").writeText("class Foo")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "a",
                    description = '''don't stop ) here''',
                    srcs = ["Foo.kt"],
                )
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(listOf("//pkg:Foo.kt"), labels)
    }

    @Test
    fun `build target selection ignores non-indexable direct resource labels`() {
        val workspace = createTempDirectory("bazel-target-image-label-")
        val packageDir = workspace.resolve("pkg")
        Files.createDirectories(packageDir.resolve("src"))
        packageDir.resolve("src/A.kt").writeText("class A")
        packageDir.resolve("icon.png").writeText("png")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                android_library(
                    name = "a",
                    srcs = ["src/A.kt"],
                    resource_files = [":icon.png"],
                )
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//pkg:a", workspace)

        assertEquals(listOf("//pkg:src/A.kt"), labels)
    }

    @Test
    fun `build target selection accepts canonical labels in root package`() {
        val workspace = createTempDirectory("bazel-target-root-label-")
        workspace.resolve("Foo.kt").writeText("class Foo")
        workspace
            .resolve("BUILD.bazel")
            .writeText(
                """
                filegroup(
                    name = "sources",
                    srcs = ["//:Foo.kt"],
                )
                kt_jvm_library(
                    name = "a",
                    srcs = ["//:sources"],
                )
                """
                    .trimIndent()
            )

        val labels = BazelTopology.degradedSourceLabels("//:a", workspace)

        assertEquals(listOf("//:Foo.kt"), labels)
    }
}
