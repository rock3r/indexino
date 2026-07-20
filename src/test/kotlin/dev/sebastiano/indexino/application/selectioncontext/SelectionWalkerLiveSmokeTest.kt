package dev.sebastiano.indexino.application.selectioncontext

import dev.sebastiano.indexino.parse.KotlinPsiParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag

/**
 * Smoke-tests [SelectionWalker] against real Kotlin sources in intellij-community.
 *
 * Excluded from default `./gradlew test`; run explicitly:
 *
 *     ./gradlew test --tests "*SelectionWalkerLiveSmokeTest*" -PliveTests
 */
@Tag("live")
class SelectionWalkerLiveSmokeTest {
    private lateinit var parser: KotlinPsiParser
    private lateinit var walker: SelectionWalker
    private lateinit var intellijRoot: Path

    @BeforeTest
    fun setUp() {
        val configured = System.getProperty("intellij.community.path")?.let(Paths::get)
        intellijRoot = configured ?: Paths.get("../intellij-community").toAbsolutePath().normalize()
        assumeTrue(intellijRoot.exists()) {
            "intellij-community not found at $intellijRoot — set -Dintellij.community.path=..."
        }

        parser = KotlinPsiParser()
        walker = SelectionWalker()
    }

    @AfterTest
    fun tearDown() {
        parser.close()
    }

    @Test
    fun `ComposeShowcase Text inside SelectionContainer`() {
        val relative = "plugins/devkit/intellij.devkit.compose/src/showcase/ComposeShowcase.kt"
        val context = analyzeLine(relative, line = 223)

        assertEquals("Text", context.callee)
        assertTrue(
            context.inSelectionContainer,
            "Text in SelectableText() should be inside SelectionContainer",
        )
        assertEquals(1, context.selectionContainerCount)
        assertFalse(context.excludedByDisableSelection)
    }

    @Test
    fun `ComposeShowcase Text outside SelectionContainer`() {
        val relative = "plugins/devkit/intellij.devkit.compose/src/showcase/ComposeShowcase.kt"
        val context = analyzeLine(relative, line = 212)

        assertEquals("Text", context.callee)
        assertFalse(
            context.inSelectionContainer,
            "Label Text should not be inside SelectionContainer",
        )
        assertEquals(0, context.selectionContainerCount)
        assertFalse(context.excludedByDisableSelection)
    }

    @Test
    fun `Popup content excluded by DisableSelection`() {
        val relative = "platform/jewel/ui/src/main/kotlin/org/jetbrains/jewel/ui/component/Popup.kt"
        val context = analyzeLine(relative, line = 219)

        assertEquals("ComposePopup", context.callee)
        assertFalse(context.inSelectionContainer)
        assertTrue(
            context.excludedByDisableSelection,
            "Popup content should be excluded by DisableSelection",
        )
        assertEquals(206, context.disableSelection?.line)
        assertEquals("Popup", context.disableSelection?.function)
    }

    @Test
    fun `DefaultMarkdownBlockRenderer Text excluded by DisableSelection in FencedBlockInfo`() {
        val relative =
            "platform/jewel/markdown/core/src/main/kotlin/org/jetbrains/jewel/markdown/rendering/DefaultMarkdownBlockRenderer.kt"
        val context = analyzeLine(relative, line = 740)

        assertEquals("Text", context.callee)
        assertFalse(context.inSelectionContainer)
        assertTrue(context.excludedByDisableSelection)
        assertEquals("FencedBlockInfo", context.disableSelection?.function)
    }

    private fun analyzeLine(relativePath: String, line: Int) =
        walker.findCallAtLine(
            file = parser.parseFile(relativePath, readSource(relativePath)),
            relativeFile = relativePath,
            line = line,
        )

    private fun readSource(relativePath: String): String {
        val path = intellijRoot.resolve(relativePath)
        assumeTrue(Files.isRegularFile(path)) { "Missing source file: $path" }
        return path.readText()
    }
}
