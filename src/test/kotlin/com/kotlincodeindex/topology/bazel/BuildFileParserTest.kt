package com.kotlincodeindex.topology.bazel

import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildFileParserTest {
    @Test
    fun `parses Java and XML files from source attributes`() {
        val workspace = createTempDirectory("build-file-parser-languages-")
        val packageDir = workspace.resolve("app")
        packageDir.resolve("src/App.kt").toFile().apply {
            parentFile.mkdirs()
            writeText("class App")
        }
        packageDir.resolve("src/Panel.java").toFile().writeText("class Panel {}")
        packageDir.resolve("res/layout/main.xml").toFile().apply {
            parentFile.mkdirs()
            writeText("<FrameLayout />")
        }
        packageDir
            .resolve("BUILD.bazel")
            .toFile()
            .writeText(
                """
                android_library(
                    name = "app",
                    srcs = glob(["src/**/*.kt", "src/**/*.java"]),
                    resource_files = glob(["res/**/*.xml"]),
                )
                """
                    .trimIndent()
            )

        val result =
            BuildFileParser.parseKotlinSources(packageDir.resolve("BUILD.bazel"), workspace)
        assertEquals(
            listOf("app/res/layout/main.xml", "app/src/App.kt", "app/src/Panel.java"),
            result.paths.sorted(),
        )
    }

    @Test
    fun `commented resource file entries are not indexed`() {
        val workspace = createTempDirectory("build-file-parser-commented-resources-")
        val packageDir = workspace.resolve("app")
        packageDir.resolve("res/layout/active.xml").toFile().apply {
            parentFile.mkdirs()
            writeText("<FrameLayout />")
        }
        packageDir
            .resolve("BUILD.bazel")
            .toFile()
            .writeText(
                """
                android_library(
                    name = "app",
                    resource_files = [
                        "res/layout/active.xml",
                        # "res/layout/deleted.xml",
                    ],
                )
                """
                    .trimIndent()
            )

        val result =
            BuildFileParser.parseKotlinSources(packageDir.resolve("BUILD.bazel"), workspace)
        assertEquals(listOf("app/res/layout/active.xml"), result.paths)
    }

    @Test
    fun `root BUILD resource paths remain workspace relative`() {
        val workspace = createTempDirectory("build-file-parser-root-resources-")
        workspace.resolve("res/layout/main.xml").toFile().apply {
            parentFile.mkdirs()
            writeText("<FrameLayout />")
        }
        workspace
            .resolve("BUILD.bazel")
            .toFile()
            .writeText(
                """
                android_library(
                    name = "app",
                    resource_files = ["res/layout/main.xml"],
                )
                """
                    .trimIndent()
            )

        val result = BuildFileParser.parseKotlinSources(workspace.resolve("BUILD.bazel"), workspace)
        assertEquals(listOf("res/layout/main.xml"), result.paths)
    }

    @Test
    fun `parses kt_jvm_library srcs from BUILD snippet`() {
        val workspace = Path("src/test/resources/fixtures/bazel")
        val buildFile = workspace.resolve("plugins/foo/ui/BUILD.bazel")
        val result = BuildFileParser.parseKotlinSources(buildFile, workspace)
        assertEquals(
            listOf(
                    "plugins/foo/ui/src/main/kotlin/Panel.kt",
                    "plugins/foo/ui/src/main/kotlin/Other.kt",
                )
                .sorted(),
            result.paths.sorted(),
        )
    }

    @Test
    fun `glob exclude patterns are not indexed as sources`() {
        val workspace = Path("src/test/resources/fixtures/bazel")
        val buildFile = workspace.resolve("plugins/foo/glob-exclude/BUILD.bazel")
        val result = BuildFileParser.parseKotlinSources(buildFile, workspace)
        assertEquals(
            listOf("plugins/foo/glob-exclude/src/main/kotlin/Main.kt"),
            result.paths.sorted(),
        )
    }

    @Test
    fun `glob expansion does not cross subpackage boundaries`() {
        val workspace = createTempDirectory("build-file-parser-subpackage-")
        val packageDir = workspace.resolve("pkg")
        packageDir.toFile().mkdirs()
        packageDir.resolve("keep/Main.kt").toFile().apply {
            parentFile.mkdirs()
            writeText("class Keep")
        }
        packageDir.resolve("sub/Main.kt").toFile().apply {
            parentFile.mkdirs()
            writeText("class Sub")
        }
        packageDir
            .resolve("sub/BUILD.bazel")
            .toFile()
            .writeText(
                """
                kt_jvm_library(
                    name = "sub",
                    srcs = ["Main.kt"],
                )
                """
                    .trimIndent()
            )
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "lib",
                    srcs = glob(["**/*.kt"]),
                )
                """
                    .trimIndent()
            )

        val result =
            BuildFileParser.parseKotlinSources(packageDir.resolve("BUILD.bazel"), workspace)
        assertEquals(listOf("pkg/keep/Main.kt"), result.paths)
    }

    @Test
    fun `named include argument in glob srcs is indexed`() {
        val workspace = createTempDirectory("build-file-parser-named-include-")
        val packageDir = workspace.resolve("pkg")
        packageDir.toFile().mkdirs()
        packageDir.resolve("Main.kt").toFile().writeText("class Main")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "lib",
                    srcs = glob(
                        include = ["**/*.kt"],
                    ),
                )
                """
                    .trimIndent()
            )

        val result =
            BuildFileParser.parseKotlinSources(packageDir.resolve("BUILD.bazel"), workspace)
        assertEquals(listOf("pkg/Main.kt"), result.paths)
    }

    @Test
    fun `hash in srcs path does not comment out later entries`() {
        val workspace = createTempDirectory("build-file-parser-hash-in-path-")
        val packageDir = workspace.resolve("pkg")
        packageDir.toFile().mkdirs()
        packageDir.resolve("foo#bar.kt").toFile().writeText("class FooBar")
        packageDir.resolve("Other.kt").toFile().writeText("class Other")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "lib",
                    srcs = ["foo#bar.kt", "Other.kt"],
                )
                """
                    .trimIndent()
            )

        val result =
            BuildFileParser.parseKotlinSources(packageDir.resolve("BUILD.bazel"), workspace)
        assertEquals(listOf("pkg/Other.kt", "pkg/foo#bar.kt").sorted(), result.paths.sorted())
    }

    @Test
    fun `hash in single-quoted srcs path does not comment out entry`() {
        val workspace = createTempDirectory("build-file-parser-single-quote-hash-")
        val packageDir = workspace.resolve("pkg")
        packageDir.toFile().mkdirs()
        packageDir.resolve("foo#bar.kt").toFile().writeText("class FooBar")
        packageDir.resolve("Other.kt").toFile().writeText("class Other")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "lib",
                    srcs = ['foo#bar.kt', 'Other.kt'],
                )
                """
                    .trimIndent()
            )

        val result =
            BuildFileParser.parseKotlinSources(packageDir.resolve("BUILD.bazel"), workspace)
        assertEquals(listOf("pkg/Other.kt", "pkg/foo#bar.kt").sorted(), result.paths.sorted())
    }

    @Test
    fun `hash in single-quoted glob pattern is indexed`() {
        val workspace = createTempDirectory("build-file-parser-single-quote-glob-hash-")
        val packageDir = workspace.resolve("pkg")
        packageDir.toFile().mkdirs()
        packageDir.resolve("foo#bar.kt").toFile().writeText("class FooBar")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "lib",
                    srcs = glob(['foo#bar.kt']),
                )
                """
                    .trimIndent()
            )

        val result =
            BuildFileParser.parseKotlinSources(packageDir.resolve("BUILD.bazel"), workspace)
        assertEquals(listOf("pkg/foo#bar.kt"), result.paths)
    }

    @Test
    fun `commented srcs entries are not indexed as sources`() {
        val workspace = createTempDirectory("build-file-parser-commented-srcs-")
        val packageDir = workspace.resolve("pkg")
        packageDir.toFile().mkdirs()
        packageDir.resolve("Active.kt").toFile().writeText("class Active")
        packageDir.resolve("Legacy.kt").toFile().writeText("class Legacy")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "lib",
                    srcs = [
                        "Active.kt",
                        # "Legacy.kt",
                    ],
                )
                """
                    .trimIndent()
            )

        val result =
            BuildFileParser.parseKotlinSources(packageDir.resolve("BUILD.bazel"), workspace)
        assertEquals(listOf("pkg/Active.kt"), result.paths)
    }

    @Test
    fun `non-srcs globs are not indexed as sources`() {
        val workspace = createTempDirectory("build-file-parser-non-srcs-glob-")
        val packageDir = workspace.resolve("pkg")
        packageDir.toFile().mkdirs()
        packageDir.resolve("src/main/kotlin/Main.kt").toFile().apply {
            parentFile.mkdirs()
            writeText("class Main")
        }
        packageDir.resolve("resources/Extra.kt").toFile().apply {
            parentFile.mkdirs()
            writeText("class Extra")
        }
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "lib",
                    srcs = ["src/main/kotlin/Main.kt"],
                    data = glob(["resources/**/*.kt"]),
                )
                """
                    .trimIndent()
            )

        val result =
            BuildFileParser.parseKotlinSources(packageDir.resolve("BUILD.bazel"), workspace)
        assertEquals(listOf("pkg/src/main/kotlin/Main.kt"), result.paths)
    }

    @Test
    fun `nested exclude glob patterns are not indexed as sources`() {
        val workspace = createTempDirectory("build-file-parser-exclude-glob-")
        val packageDir = workspace.resolve("pkg")
        packageDir.toFile().mkdirs()
        packageDir.resolve("src/main/kotlin/Main.kt").toFile().apply {
            parentFile.mkdirs()
            writeText("class Main")
        }
        packageDir.resolve("src/main/kotlin/MainTest.kt").toFile().writeText("class MainTest")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "lib",
                    srcs = glob(
                        ["**/*.kt"],
                        exclude = glob(["**/*Test.kt"]),
                    ),
                )
                """
                    .trimIndent()
            )

        val result =
            BuildFileParser.parseKotlinSources(packageDir.resolve("BUILD.bazel"), workspace)
        assertEquals(listOf("pkg/src/main/kotlin/Main.kt"), result.paths)
    }

    @Test
    fun `commented srcs glob assignment is not indexed as sources`() {
        val workspace = createTempDirectory("build-file-parser-commented-glob-")
        val packageDir = workspace.resolve("pkg")
        packageDir.toFile().mkdirs()
        packageDir.resolve("Active.kt").toFile().writeText("class Active")
        packageDir.resolve("Legacy.kt").toFile().writeText("class Legacy")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "lib",
                    srcs = ["Active.kt"],
                    # srcs = glob(["legacy/**/*.kt"]),
                )
                """
                    .trimIndent()
            )

        val result =
            BuildFileParser.parseKotlinSources(packageDir.resolve("BUILD.bazel"), workspace)
        assertEquals(listOf("pkg/Active.kt"), result.paths)
    }

    @Test
    fun `positional include wins over nested exclude glob include`() {
        val workspace = createTempDirectory("build-file-parser-nested-include-")
        val packageDir = workspace.resolve("pkg")
        packageDir.toFile().mkdirs()
        packageDir.resolve("Main.kt").toFile().writeText("class Main")
        packageDir.resolve("MainTest.kt").toFile().writeText("class MainTest")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "lib",
                    srcs = glob(
                        ["**/*.kt"],
                        exclude = glob(include = ["**/*Test.kt"]),
                    ),
                )
                """
                    .trimIndent()
            )

        val result =
            BuildFileParser.parseKotlinSources(packageDir.resolve("BUILD.bazel"), workspace)
        assertEquals(listOf("pkg/Main.kt"), result.paths)
    }

    @Test
    fun `commented include line does not override positional include`() {
        val workspace = createTempDirectory("build-file-parser-commented-include-")
        val packageDir = workspace.resolve("pkg")
        packageDir.toFile().mkdirs()
        packageDir.resolve("Active.kt").toFile().writeText("class Active")
        packageDir.resolve("Legacy.kt").toFile().writeText("class Legacy")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "lib",
                    srcs = glob(
                        # include = ["**/*.kt"],
                        ["Active.kt"],
                    ),
                )
                """
                    .trimIndent()
            )

        val result =
            BuildFileParser.parseKotlinSources(packageDir.resolve("BUILD.bazel"), workspace)
        assertEquals(listOf("pkg/Active.kt"), result.paths)
    }

    @Test
    fun `single-quoted glob patterns are indexed`() {
        val workspace = createTempDirectory("build-file-parser-single-quote-")
        val packageDir = workspace.resolve("pkg")
        packageDir.toFile().mkdirs()
        packageDir.resolve("Main.kt").toFile().writeText("class Main")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "lib",
                    srcs = glob(['**/*.kt']),
                )
                """
                    .trimIndent()
            )

        val result =
            BuildFileParser.parseKotlinSources(packageDir.resolve("BUILD.bazel"), workspace)
        assertEquals(listOf("pkg/Main.kt"), result.paths)
    }

    @Test
    fun `glob in concatenated srcs expression is indexed`() {
        val workspace = createTempDirectory("build-file-parser-concat-srcs-")
        val packageDir = workspace.resolve("pkg")
        packageDir.toFile().mkdirs()
        packageDir.resolve("Main.kt").toFile().writeText("class Main")
        packageDir.resolve("src/Extra.kt").toFile().apply {
            parentFile.mkdirs()
            writeText("class Extra")
        }
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "lib",
                    srcs = ["Main.kt"] + glob(["src/**/*.kt"]),
                )
                """
                    .trimIndent()
            )

        val result =
            BuildFileParser.parseKotlinSources(packageDir.resolve("BUILD.bazel"), workspace)
        assertEquals(listOf("pkg/Main.kt", "pkg/src/Extra.kt").sorted(), result.paths.sorted())
    }

    @Test
    fun `multiline concat glob in srcs is indexed`() {
        val workspace = createTempDirectory("build-file-parser-multiline-concat-")
        val packageDir = workspace.resolve("pkg")
        packageDir.toFile().mkdirs()
        packageDir.resolve("Main.kt").toFile().writeText("class Main")
        packageDir.resolve("src/Extra.kt").toFile().apply {
            parentFile.mkdirs()
            writeText("class Extra")
        }
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "lib",
                    srcs = ["Main.kt"]
                        + glob(["src/**/*.kt"]),
                )
                """
                    .trimIndent()
            )

        val result =
            BuildFileParser.parseKotlinSources(packageDir.resolve("BUILD.bazel"), workspace)
        assertEquals(listOf("pkg/Main.kt", "pkg/src/Extra.kt").sorted(), result.paths.sorted())
    }

    @Test
    fun `concat glob in non-srcs attribute is not indexed`() {
        val workspace = createTempDirectory("build-file-parser-concat-non-srcs-")
        val packageDir = workspace.resolve("pkg")
        packageDir.toFile().mkdirs()
        packageDir.resolve("Main.kt").toFile().writeText("class Main")
        packageDir.resolve("resources/Extra.kt").toFile().apply {
            parentFile.mkdirs()
            writeText("class Extra")
        }
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "lib",
                    srcs = ["Main.kt"],
                    data = ["README"] + glob(["resources/**/*.kt"]),
                )
                """
                    .trimIndent()
            )

        val result =
            BuildFileParser.parseKotlinSources(packageDir.resolve("BUILD.bazel"), workspace)
        assertEquals(listOf("pkg/Main.kt"), result.paths)
    }

    @Test
    fun `include after exclude in glob srcs is indexed`() {
        val workspace = createTempDirectory("build-file-parser-exclude-before-include-")
        val packageDir = workspace.resolve("pkg")
        packageDir.toFile().mkdirs()
        packageDir.resolve("Main.kt").toFile().writeText("class Main")
        packageDir.resolve("MainTest.kt").toFile().writeText("class MainTest")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "lib",
                    srcs = glob(
                        exclude = ["**/*Test.kt"],
                        include = ["**/*.kt"],
                    ),
                )
                """
                    .trimIndent()
            )

        val result =
            BuildFileParser.parseKotlinSources(packageDir.resolve("BUILD.bazel"), workspace)
        assertEquals(listOf("pkg/Main.kt"), result.paths)
    }

    @Test
    fun `literal srcs are kept when bracket body mentions glob`() {
        val workspace = createTempDirectory("build-file-parser-literal-with-glob-")
        val packageDir = workspace.resolve("pkg")
        packageDir.toFile().mkdirs()
        packageDir.resolve("Main.kt").toFile().writeText("class Main")
        packageDir.resolve("Other.kt").toFile().writeText("class Other")
        packageDir
            .resolve("BUILD.bazel")
            .writeText(
                """
                kt_jvm_library(
                    name = "lib",
                    srcs = [
                        "Main.kt",
                        # glob(["Legacy.kt"]),
                        "Other.kt",
                    ],
                )
                """
                    .trimIndent()
            )

        val result =
            BuildFileParser.parseKotlinSources(packageDir.resolve("BUILD.bazel"), workspace)
        assertEquals(listOf("pkg/Main.kt", "pkg/Other.kt").sorted(), result.paths.sorted())
    }
}
