package dev.sebastiano.indexino.topology.gradle

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class GradleLiteralTopologyTest {
    @TempDir lateinit var workspace: Path

    @Test
    fun `Groovy literals exclude comments strings and computed includes`() {
        assertEquals(
            listOf(":shell", ":engine:codec", ":leaf"),
            SettingsParser.parseIncludes(
                """
                // include(":comment")
                /* include ':block' */
                def decoy = "include(':string')"
                include ':shell', ':engine:codec'
                include(':leaf')
                include(":computed" + suffix)
                """
                    .trimIndent()
            ),
        )
    }

    @Test
    fun `dependencies accept single quotes and Groovy shorthand without decoys`() {
        assertEquals(
            listOf(":one", ":two:nested", ":three"),
            BuildGradleParser.parseProjectDependencies(
                """
                api(project(':one'))
                implementation project(':two:nested')
                runtimeOnly(project(":three"))
                // api(project(":comment"))
                /* implementation(project(":block")) */
                val decoy = "api(project(':string'))"
                notapi(project(":suffix"))
                api(project(":computed" + suffix))
                """
                    .trimIndent()
            ),
        )
    }

    @Test
    fun `accessors resolve declared nested hyphenated modules and remapped build files`() {
        write(
            "settings.gradle.kts",
            """
            include(":shell", ":engine:wire-codec", ":leaf", ":unused")
            project(":engine:wire-codec").projectDir = file("parts/codec")
            """
                .trimIndent(),
        )
        write(
            "shell/build.gradle.kts",
            """
            dependencies {
                api(projects.engine.wireCodec)
                // api(projects.unused)
                val decoy = "api(projects.unused)"
            }
            """
                .trimIndent(),
        )
        write("parts/codec/build.gradle", "implementation project(':leaf')")
        write("leaf/build.gradle.kts", "api(project(\":shell\"))")
        write("shell/src/main/kotlin/Shell.kt", "class Shell")
        write("parts/codec/src/customMain/java/Codec.java", "class Codec {}")
        write("leaf/src/main/kotlin/Leaf.kt", "class Leaf")
        write("unused/src/main/kotlin/Unused.kt", "class Unused")
        write("parts/codec/src/commonTest/kotlin/Excluded.kt", "class Excluded")

        assertEquals(
            listOf("shell/src/main/kotlin/Shell.kt"),
            GradleTopology.resolveSources(":shell", workspace).sourceFiles,
        )
        assertEquals(
            listOf(
                "leaf/src/main/kotlin/Leaf.kt",
                "parts/codec/src/customMain/java/Codec.java",
                "shell/src/main/kotlin/Shell.kt",
            ),
            GradleTopology.resolveSources(":shell", workspace, includeDeps = true).sourceFiles,
        )
    }

    private fun write(path: String, content: String) {
        workspace.resolve(path).also { it.parent.createDirectories() }.writeText(content)
    }

    @Test
    fun `root and cyclic included builds honor Groovy remaps without generated or test trees`() {
        write(
            "settings.gradle",
            """
            include ':nested:api', ':missing'
            project(':nested:api').projectDir = file('relocated/api')
            includeBuild 'tools'
            """
                .trimIndent(),
        )
        write(
            "tools/settings.gradle.kts",
            """
            include(":convention")
            project(":convention").projectDir = file("relocated")
            includeBuild("..")
            """
                .trimIndent(),
        )
        write("src/main/kotlin/Root.kt", "class Root")
        write("relocated/api/src/desktopMain/kotlin/Api.kt", "class Api")
        write("relocated/api/src/main/res/values-night/strings.xml", "<resources />")
        write("relocated/api/src/main/composeResources/drawable/icon.svg", "<svg />")
        write("relocated/api/src/integrationTest/kotlin/Excluded.kt", "class Excluded")
        write("relocated/api/build/generated/kotlin/Generated.kt", "class Generated")
        write("relocated/api/custom/Elsewhere.kt", "class Elsewhere")
        // These declarations are deliberately unsupported: the independent inventory must flag
        // the generated/custom/missing sources, not claim that the conventional scan is complete.
        write(
            "relocated/api/build.gradle.kts",
            """
            sourceSets { main { java.srcDirs("custom", "build/generated/kotlin", "not-generated-yet") } }
            """
                .trimIndent(),
        )
        write("tools/relocated/src/main/java/Convention.java", "class Convention {}")
        val result = GradleTopology.resolveSources(":", workspace)
        assertEquals(
            listOf(
                "relocated/api/src/desktopMain/kotlin/Api.kt",
                "relocated/api/src/main/composeResources/drawable/icon.svg",
                "relocated/api/src/main/res/values-night/strings.xml",
                "src/main/kotlin/Root.kt",
            ),
            result.sourceFiles,
        )
        assertEquals(listOf(workspace.resolve("tools").toRealPath()), result.externalMounts)
        assertEquals(
            listOf("relocated/src/main/java/Convention.java"),
            result.externalSources.single().sourceFiles,
        )
    }

    @Test
    fun `rejects external projectDir instead of emitting parent paths`() {
        write(
            "settings.gradle.kts",
            "include(\":escape\"); project(\":escape\").projectDir = file(\"../outside\")",
        )
        assertFailsWith<IllegalArgumentException> {
            GradleTopology.resolveSources(":escape", workspace)
        }
    }

    @Test
    fun `project directory remaps preserve relative workspace paths`() {
        assertEquals(
            Path.of("relative-workspace", "relocated"),
            ModuleSourceRoots.moduleDirectory(
                Path.of("relative-workspace"),
                ":api",
                mapOf(":api" to "relocated"),
            ),
        )
    }

    @Test
    fun `rejects ambiguous accessor collisions and ignores undeclared accessors`() {
        assertFailsWith<IllegalArgumentException> {
            BuildGradleParser.parseProjectDependencies(
                "api(projects.wireCodec)",
                listOf(":wire-codec", ":wire_codec"),
            )
        }
        assertEquals(
            emptyList(),
            BuildGradleParser.parseProjectDependencies("api(projects.missing)", listOf(":present")),
        )
    }

    @Test
    fun `literal remaps do not accept computed expression prefixes or commented decoys`() {
        assertEquals(
            mapOf(":live" to "parts/live"),
            SettingsParser.parseProjectDirectories(
                """
                project(':live').projectDir = file('parts/live')
                project(':computed').projectDir = file('parts').resolve(suffix)
                project(':sum').projectDir = file('parts') + suffix
                /* outer /* nested */ project(':comment').projectDir = file('decoy') */
                val text = "project(':string').projectDir = file('decoy')"
                """
                    .trimIndent()
            ),
        )
    }

    @Test
    fun `accessors preserve the configuration whitelist rather than invent a production classpath`() {
        assertEquals(
            listOf(":input-coordinator", ":test-support"),
            BuildGradleParser.parseProjectDependencies(
                """
                api(projects.inputCoordinator)
                testImplementation(projects.testSupport)
                testRuntimeOnly(projects.runtimeProbe)
                /* outer /* nested */ api(projects.runtimeProbe) */
                val example = "api(projects.runtimeProbe)"
                """
                    .trimIndent(),
                listOf(":input-coordinator", ":test-support", ":runtime-probe"),
            ),
        )
    }
}
