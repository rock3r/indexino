package dev.sebastiano.indexino.topology

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class TopologyCacheTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `unchanged build definitions reuse the cached topology without resolving`() {
        val workspace = bazelWorkspace("unchanged")
        var resolutions = 0
        fun resolve(): TopologyResult =
            cached(workspace) {
                resolutions++
                fresh(workspace)
            }

        val first = resolve()
        assertEquals(1, resolutions)
        val second = resolve()
        assertEquals(1, resolutions, "Unchanged build definitions re-ran discovery")
        assertEquals(first, second)
        assertEquals(fresh(workspace), second)
    }

    @Test
    fun `editing a BUILD file invalidates the cached topology`() {
        val workspace = bazelWorkspace("build-edit")
        var resolutions = 0
        fun resolve(): TopologyResult =
            cached(workspace) {
                resolutions++
                fresh(workspace)
            }

        val first = resolve()
        workspace.resolve("app/BUILD.bazel").writeText(buildFile("Second.kt"))
        val second = resolve()

        assertEquals(2, resolutions)
        assertNotEquals(first, second)
        assertEquals(listOf("app/Second.kt"), second.sourceFiles)
        assertEquals(fresh(workspace), second)
    }

    @Test
    fun `editing a bzl or workspace file invalidates the cached topology`() {
        val workspace = bazelWorkspace("other-inputs")
        var resolutions = 0
        fun resolve(): TopologyResult =
            cached(workspace) {
                resolutions++
                fresh(workspace)
            }

        resolve()
        workspace.resolve("defs/toolchain.bzl").writeText("# changed\n")
        resolve()
        assertEquals(2, resolutions)

        workspace.resolve("MODULE.bazel").writeText("module(name = \"fixture\", version = \"2\")\n")
        resolve()
        assertEquals(3, resolutions)
    }

    @Test
    fun `adding a source file invalidates a glob-sensitive topology`() {
        val workspace = bazelWorkspace("new-source")
        var resolutions = 0
        fun resolve(): TopologyResult =
            cached(workspace) {
                resolutions++
                fresh(workspace)
            }

        resolve()
        workspace.resolve("app/Added.kt").writeText("class Added\n")
        val second = resolve()

        assertEquals(2, resolutions, "A new file can change a BUILD glob without a BUILD edit")
        assertEquals(fresh(workspace), second)
    }

    @Test
    fun `a toolchain change invalidates the cached topology`() {
        val workspace = bazelWorkspace("toolchain")
        var resolutions = 0
        var toolchain = ToolchainIdentity.of("bazel 8.0.0", "25", "2.4.10")
        fun resolve(): TopologyResult =
            TopologyCache.resolve(
                project = workspace,
                request = bazelRequest(),
                buildSystem = BuildSystem.BAZEL,
                cacheRoot = tempDir.resolve("cache"),
                toolchain = toolchain,
                resolveFresh = {
                    resolutions++
                    fresh(workspace)
                },
            )

        resolve()
        resolve()
        assertEquals(1, resolutions)
        toolchain = ToolchainIdentity.of("bazel 8.1.0", "25", "2.4.10")
        resolve()
        assertEquals(2, resolutions)
    }

    @Test
    fun `request scope changes use a separate cache entry`() {
        val workspace = bazelWorkspace("requests")
        val resolutions = mutableListOf<TopologyRequest>()
        fun resolve(request: TopologyRequest): TopologyResult =
            TopologyCache.resolve(
                project = workspace,
                request = request,
                buildSystem = request.buildSystem,
                cacheRoot = tempDir.resolve("cache"),
                toolchain = TOOLCHAIN,
                resolveFresh = {
                    resolutions += request
                    fresh(workspace, request)
                },
            )

        val targetOnly = resolve(bazelRequest(includeDeps = false))
        val withDeps = resolve(bazelRequest(includeDeps = true))
        resolve(bazelRequest(includeDeps = false))

        assertEquals(2, resolutions.size)
        assertNotEquals(targetOnly, withDeps)
    }

    @Test
    fun `a corrupt cache entry falls back to discovery`() {
        val workspace = bazelWorkspace("corrupt")
        var resolutions = 0
        fun resolve(): TopologyResult =
            cached(workspace) {
                resolutions++
                fresh(workspace)
            }

        resolve()
        Files.walk(tempDir.resolve("cache")).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { it.writeText("{not json") }
        }

        val recovered = resolve()
        assertEquals(2, resolutions)
        assertEquals(fresh(workspace), recovered)
    }

    @Test
    fun `gradle included build edits invalidate the cached topology`() {
        val workspace = Files.createDirectories(tempDir.resolve("gradle-workspace")).toRealPath()
        val included = Files.createDirectories(tempDir.resolve("gradle-included")).toRealPath()
        workspace
            .resolve("settings.gradle.kts")
            .writeText("include(\":app\")\nincludeBuild(\"../gradle-included\")\n")
        workspace.resolve("build.gradle.kts").writeText("plugins {}\n")
        workspace.resolve("app").createDirectories()
        workspace.resolve("app/build.gradle.kts").writeText("plugins {}\n")
        included.resolve("settings.gradle.kts").writeText("include(\":tool\")\n")
        included.resolve("build.gradle.kts").writeText("plugins {}\n")
        val request = TopologyRequest(buildSystem = BuildSystem.GRADLE, gradleModule = ":app")
        var resolutions = 0
        fun resolve(): TopologyResult =
            TopologyCache.resolve(
                project = workspace,
                request = request,
                buildSystem = request.buildSystem,
                cacheRoot = tempDir.resolve("cache"),
                toolchain = TOOLCHAIN,
                preDiscoveryRoots = { listOf(included) },
                resolveFresh = {
                    resolutions++
                    TopologyResult(
                        sourceFiles = listOf("app/src/main/kotlin/App.kt"),
                        codeSourceFiles = setOf("app/src/main/kotlin/App.kt"),
                        topology = "gradle-parse",
                        includeDeps = false,
                        scope = ":app",
                        externalMounts = listOf(included),
                        externalSources =
                            listOf(
                                ExternalSourceMount(
                                    root = included,
                                    sourceFiles = listOf("tool/src/main/kotlin/Tool.kt"),
                                    codeSourceFiles = setOf("tool/src/main/kotlin/Tool.kt"),
                                )
                            ),
                    )
                },
            )

        val first = resolve()
        resolve()
        assertEquals(1, resolutions)
        assertEquals(listOf(included), first.externalMounts)

        included.resolve("build.gradle.kts").writeText("plugins { kotlin(\"jvm\") }\n")
        val afterEdit = resolve()
        assertEquals(2, resolutions)
        assertEquals(first.externalSources, afterEdit.externalSources)
    }

    @Test
    fun `a bazel query fallback is not cached while the client is healthy`() {
        val workspace = bazelWorkspace("fallback")
        var resolutions = 0
        fun resolve(toolchain: ToolchainIdentity): TopologyResult =
            TopologyCache.resolve(
                project = workspace,
                request = bazelRequest(),
                buildSystem = BuildSystem.BAZEL,
                cacheRoot = tempDir.resolve("cache"),
                toolchain = toolchain,
                resolveFresh = {
                    resolutions++
                    TopologyResult(
                        sourceFiles = listOf("app/Main.kt"),
                        codeSourceFiles = setOf("app/Main.kt"),
                        topology = "build-parse",
                        includeDeps = false,
                        scope = "//app:app",
                    )
                },
            )

        resolve(ToolchainIdentity.of("bazel 8.0.0", "25", "2.4.10", bazelAvailable = true))
        resolve(ToolchainIdentity.of("bazel 8.0.0", "25", "2.4.10", bazelAvailable = true))
        assertEquals(2, resolutions, "A transient query failure must be retried")

        resolve(ToolchainIdentity.of("bazel unavailable", "25", "2.4.10", bazelAvailable = false))
        resolve(ToolchainIdentity.of("bazel unavailable", "25", "2.4.10", bazelAvailable = false))
        assertEquals(
            3,
            resolutions,
            "build-parse is the real environment when the client is absent",
        )
    }

    @Test
    fun `gradle discovery reuses the cache and reflects a settings edit`() {
        val workspace = Files.createDirectories(tempDir.resolve("gradle-cached")).toRealPath()
        workspace.resolve("settings.gradle.kts").writeText("include(\":app\")\n")
        workspace.resolve("build.gradle.kts").writeText("plugins {}\n")
        workspace.resolve("app").createDirectories()
        workspace.resolve("app/build.gradle.kts").writeText("plugins {}\n")
        workspace.resolve("app/src/main/kotlin").createDirectories()
        workspace.resolve("app/src/main/kotlin/App.kt").writeText("class App\n")
        val request = TopologyRequest(buildSystem = BuildSystem.GRADLE, gradleModule = ":app")
        val cacheRoot = tempDir.resolve("cache")
        val messages = mutableListOf<String>()

        val first =
            TopologyResolver.resolve(
                project = workspace,
                request = request,
                onStderr = messages::add,
                cacheRoot = cacheRoot,
            )
        assertTrue(messages.none { it.contains("topology=cached") }, "$messages")

        messages.clear()
        val second =
            TopologyResolver.resolve(
                project = workspace,
                request = request,
                onStderr = messages::add,
                cacheRoot = cacheRoot,
            )
        assertTrue(messages.any { it.contains("index topology=cached") }, "$messages")
        assertEquals(first, second)

        workspace.resolve("settings.gradle.kts").writeText("include(\":app\", \":lib\")\n")
        messages.clear()
        val afterSettingsEdit =
            TopologyResolver.resolve(
                project = workspace,
                request = request,
                onStderr = messages::add,
                cacheRoot = cacheRoot,
            )
        assertTrue(messages.none { it.contains("topology=cached") }, "$messages")
        assertEquals(first, afterSettingsEdit)
    }

    @Test
    fun `gradle inventory covers sources under build-named directories`() {
        val workspace = Files.createDirectories(tempDir.resolve("gradle-build-dir")).toRealPath()
        workspace.resolve("settings.gradle.kts").writeText("include(\":app\")\n")
        workspace.resolve("build.gradle.kts").writeText("plugins {}\n")
        workspace.resolve("app").createDirectories()
        workspace.resolve("app/build.gradle.kts").writeText("plugins {}\n")
        workspace.resolve("app/src/main/kotlin/com/example/build").createDirectories()
        workspace
            .resolve("app/src/main/kotlin/com/example/build/Initial.kt")
            .writeText("class Initial\n")
        val request = TopologyRequest(buildSystem = BuildSystem.GRADLE, gradleModule = ":app")
        val cacheRoot = tempDir.resolve("cache")
        val messages = mutableListOf<String>()
        fun resolve(): TopologyResult =
            TopologyResolver.resolve(
                project = workspace,
                request = request,
                onStderr = messages::add,
                cacheRoot = cacheRoot,
            )

        val first = resolve()
        assertTrue(
            first.sourceFiles.contains("app/src/main/kotlin/com/example/build/Initial.kt"),
            "${first.sourceFiles}",
        )

        workspace.resolve("app/src/main/kotlin/com/example/build/New.kt").writeText("class New\n")
        messages.clear()
        val second = resolve()
        assertTrue(messages.none { it.contains("topology=cached") }, "$messages")
        assertTrue(
            second.sourceFiles.contains("app/src/main/kotlin/com/example/build/New.kt"),
            "${second.sourceFiles}",
        )

        workspace.resolve("app/build").createDirectories()
        workspace.resolve("app/build/Generated.kt").writeText("class Generated\n")
        messages.clear()
        resolve()
        assertTrue(messages.any { it.contains("topology=cached") }, "$messages")
    }

    @Test
    fun `a dependency-inclusive request retries a target-only fallback`() {
        val workspace = bazelWorkspace("dependency-fallback")
        var resolutions = 0
        var degraded = true
        fun resolve(): TopologyResult =
            TopologyCache.resolve(
                project = workspace,
                request = bazelRequest(includeDeps = true),
                buildSystem = BuildSystem.BAZEL,
                cacheRoot = tempDir.resolve("cache"),
                toolchain = TOOLCHAIN,
                resolveFresh = {
                    resolutions++
                    TopologyResult(
                        sourceFiles = listOf("app/Main.kt"),
                        codeSourceFiles = if (degraded) null else setOf("app/Main.kt"),
                        topology = "bazel-query",
                        includeDeps = !degraded,
                        scope = "//app:app",
                    )
                },
            )

        resolve()
        resolve()
        assertEquals(2, resolutions, "A degraded dependency closure must be retried")

        degraded = false
        resolve()
        resolve()
        assertEquals(3, resolutions, "A complete closure is cacheable")
    }

    @Test
    fun `a target-only build-parse result is cached when bazel is absent`() {
        val workspace = bazelWorkspace("no-bazel")
        val toolchain =
            ToolchainIdentity.capture(workspace, BuildSystem.BAZEL) { "unavailable:IOException" }
        assertFalse(toolchain.bazelAvailable)
        var resolutions = 0
        fun resolve(): TopologyResult =
            TopologyCache.resolve(
                project = workspace,
                request = bazelRequest(includeDeps = false),
                buildSystem = BuildSystem.BAZEL,
                cacheRoot = tempDir.resolve("cache"),
                toolchain = toolchain,
                resolveFresh = {
                    resolutions++
                    TopologyResult(
                        sourceFiles = listOf("app/Main.kt"),
                        codeSourceFiles = setOf("app/Main.kt"),
                        topology = "build-parse",
                        includeDeps = false,
                        scope = "//app:app",
                    )
                },
            )

        resolve()
        resolve()
        assertEquals(1, resolutions, "A no-Bazel build-parse result must be cacheable")
    }

    @Test
    fun `editing bazelignore invalidates the cached topology`() {
        val workspace = bazelWorkspace("bazelignore")
        val ignore = workspace.resolve(".bazelignore")
        ignore.writeText("ignored\n")
        var resolutions = 0
        fun resolve(): TopologyResult =
            cached(workspace) {
                resolutions++
                fresh(workspace)
            }

        resolve()
        resolve()
        assertEquals(1, resolutions)
        ignore.writeText("other-ignored\n")
        resolve()
        assertEquals(2, resolutions, "Editing .bazelignore must invalidate the topology")
    }

    @Test
    fun `a bazel result with unknown code roles is not cached`() {
        val workspace = bazelWorkspace("code-roles")
        var resolutions = 0
        var classified = false
        fun resolve(): TopologyResult =
            TopologyCache.resolve(
                project = workspace,
                request = bazelRequest(),
                buildSystem = BuildSystem.BAZEL,
                cacheRoot = tempDir.resolve("cache"),
                toolchain = TOOLCHAIN,
                resolveFresh = {
                    resolutions++
                    TopologyResult(
                        sourceFiles = listOf("app/Main.kt"),
                        codeSourceFiles = if (classified) setOf("app/Main.kt") else null,
                        topology = "bazel-query",
                        includeDeps = false,
                        scope = "//app:app",
                    )
                },
            )

        resolve()
        resolve()
        assertEquals(2, resolutions, "A failed code-role query must be retried")

        classified = true
        resolve()
        resolve()
        assertEquals(3, resolutions, "A classified result is cacheable")
    }

    @Test
    fun `an included build change during discovery is not cached`() {
        val workspace = Files.createDirectories(tempDir.resolve("race-workspace")).toRealPath()
        val included = Files.createDirectories(tempDir.resolve("race-included")).toRealPath()
        workspace
            .resolve("settings.gradle.kts")
            .writeText("include(\":app\")\nincludeBuild(\"../race-included\")\n")
        workspace.resolve("build.gradle.kts").writeText("plugins {}\n")
        workspace.resolve("app").createDirectories()
        workspace.resolve("app/build.gradle.kts").writeText("plugins {}\n")
        included.resolve("settings.gradle.kts").writeText("include(\":tool\")\n")
        included.resolve("build.gradle.kts").writeText("plugins {}\n")
        var resolutions = 0
        var mutate = true
        fun resolve(): TopologyResult =
            TopologyCache.resolve(
                project = workspace,
                request = TopologyRequest(buildSystem = BuildSystem.GRADLE, gradleModule = ":app"),
                buildSystem = BuildSystem.GRADLE,
                cacheRoot = tempDir.resolve("cache"),
                toolchain = TOOLCHAIN,
                preDiscoveryRoots = { listOf(included) },
                resolveFresh = {
                    resolutions++
                    if (mutate) {
                        mutate = false
                        included.resolve("New.kt").writeText("class New\n")
                    }
                    TopologyResult(
                        sourceFiles = listOf("app/App.kt", "tool/Tool.kt", "tool/New.kt"),
                        codeSourceFiles = linkedSetOf("app/App.kt", "tool/Tool.kt", "tool/New.kt"),
                        topology = "gradle-parse",
                        includeDeps = false,
                        scope = ":app",
                        externalMounts = listOf(included),
                        externalSources =
                            listOf(
                                ExternalSourceMount(
                                    root = included,
                                    sourceFiles = listOf("tool/Tool.kt", "tool/New.kt"),
                                    codeSourceFiles = linkedSetOf("tool/Tool.kt", "tool/New.kt"),
                                )
                            ),
                    )
                },
            )

        resolve()
        resolve()
        resolve()
        assertEquals(2, resolutions, "A mount changed during discovery must not be cached")
    }

    @Test
    fun `a symlinked source directory bypasses the topology cache`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val workspace = bazelWorkspace("symlinked")
        val real = Files.createDirectories(workspace.resolve("app/real"))
        real.resolve("Inside.kt").writeText("class Inside\n")
        val created =
            runCatching { Files.createSymbolicLink(workspace.resolve("app/linked"), real) }
                .isSuccess
        assumeTrue(created, "Symbolic links are unavailable")
        var resolutions = 0
        fun resolve(): TopologyResult =
            cached(workspace) {
                resolutions++
                fresh(workspace)
            }

        resolve()
        resolve()
        assertEquals(2, resolutions, "A symlinked directory must not be cached")
    }

    @Test
    fun `root bazel output symlinks do not disable the cache`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val workspace = bazelWorkspace("bazel-output-link")
        val output = Files.createDirectories(workspace.resolve("bazel-out"))
        val created =
            runCatching { Files.createSymbolicLink(workspace.resolve("bazel-bin"), output) }
                .isSuccess
        assumeTrue(created, "Symbolic links are unavailable")
        var resolutions = 0
        fun resolve(): TopologyResult =
            cached(workspace) {
                resolutions++
                fresh(workspace)
            }

        resolve()
        resolve()
        assertEquals(1, resolutions, "Bazel's own output links must remain cacheable")
    }

    @Test
    fun `injected bazel executors always run and never read the cache`() {
        val workspace = bazelWorkspace("bypass")
        var queries = 0
        val executor =
            dev.sebastiano.indexino.topology.bazel.BazelQueryExecutor { _, _ ->
                queries++
                listOf("//app:Main.kt")
            }
        val messages = mutableListOf<String>()
        val request = bazelRequest()

        repeat(2) {
            TopologyResolver.resolve(
                project = workspace,
                request = request,
                bazelQueryExecutor = executor,
                onStderr = messages::add,
                cacheRoot = tempDir.resolve("cache"),
            )
        }

        assertEquals(2, queries)
        assertTrue(messages.none { it.contains("topology=cached") }, "$messages")
    }

    private fun bazelWorkspace(name: String): Path {
        val workspace = Files.createDirectories(tempDir.resolve(name)).toRealPath()
        workspace.resolve("MODULE.bazel").writeText("module(name = \"fixture\")\n")
        workspace.resolve(".bazelproject").writeText("directories:\n  app\n")
        workspace.resolve("defs").createDirectories()
        workspace.resolve("defs/toolchain.bzl").writeText("# toolchain\n")
        workspace.resolve("app").createDirectories()
        workspace.resolve("app/BUILD.bazel").writeText(buildFile("Main.kt"))
        workspace.resolve("app/Main.kt").writeText("class Main\n")
        return workspace
    }

    private fun buildFile(vararg sources: String): String =
        "kt_jvm_library(\n    name = \"app\",\n    srcs = [" +
            sources.joinToString { "\"$it\"" } +
            "],\n)\n"

    private fun bazelRequest(includeDeps: Boolean = false): TopologyRequest =
        TopologyRequest(
            buildSystem = BuildSystem.BAZEL,
            bazelTarget = "//app:app",
            includeDeps = includeDeps,
        )

    private fun cached(workspace: Path, fresh: () -> TopologyResult): TopologyResult =
        TopologyCache.resolve(
            project = workspace,
            request = bazelRequest(),
            buildSystem = BuildSystem.BAZEL,
            cacheRoot = tempDir.resolve("cache"),
            toolchain = TOOLCHAIN,
            resolveFresh = fresh,
        )

    /** Discovery output derived from BUILD content so an invalidation is observable. */
    private fun fresh(workspace: Path, request: TopologyRequest = bazelRequest()): TopologyResult {
        val build = workspace.resolve("app").resolve("BUILD.bazel").readText()
        val sources = Regex("\"([^\"]+\\.kt)\"").findAll(build).map { it.groupValues[1] }.toList()
        return TopologyResult(
            sourceFiles = sources.map { "app/$it" },
            codeSourceFiles = sources.mapTo(linkedSetOf()) { "app/$it" },
            topology = "bazel-query",
            includeDeps = request.includeDeps,
            scope = checkNotNull(request.bazelTarget),
        )
    }

    private companion object {
        val TOOLCHAIN = ToolchainIdentity.of("bazel 8.0.0", "25.0.5", "2.4.10")
    }
}
