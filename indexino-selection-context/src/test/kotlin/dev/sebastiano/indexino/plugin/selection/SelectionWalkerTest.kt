package dev.sebastiano.indexino.plugin.selection

import dev.sebastiano.indexino.plugin.selection.parse.KotlinPsiParser
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectionWalkerTest {
    private lateinit var parser: KotlinPsiParser
    private lateinit var walker: SelectionWalker

    @BeforeTest
    fun setUp() {
        parser = KotlinPsiParser()
        walker = SelectionWalker()
    }

    @AfterTest
    fun tearDown() {
        parser.close()
    }

    @Test
    fun `nested selection containers count each ancestor`() {
        val context =
            analyze(
                """
                @Composable
                fun Panel() {
                    SelectionContainer {
                        SelectionContainer {
                            ActionButton()
                        }
                    }
                }
                """
                    .trimIndent(),
                line = 5,
            )

        assertEquals("ActionButton", context.callee)
        assertTrue(context.inSelectionContainer)
        assertEquals(2, context.selectionContainerCount)
        assertFalse(context.excludedByDisableSelection)
    }

    @Test
    fun `disable selection excludes interactive site`() {
        val context =
            analyze(
                """
                @Composable
                fun Panel() {
                    SelectionContainer {
                        DisableSelection {
                            ActionButton()
                        }
                    }
                }
                """
                    .trimIndent(),
                line = 5,
            )

        assertTrue(context.inSelectionContainer)
        assertTrue(context.excludedByDisableSelection)
        assertEquals(1, context.selectionContainerCount)
    }

    @Test
    fun `import alias resolves selection container`() {
        val context =
            analyze(
                """
                import androidx.compose.foundation.text.selection.SelectionContainer as SC

                @Composable
                fun Panel() {
                    SC {
                        ActionButton()
                    }
                }
                """
                    .trimIndent(),
                line = 6,
            )

        assertTrue(context.inSelectionContainer)
        assertEquals(1, context.selectionContainerCount)
    }

    private fun analyze(source: String, line: Int) =
        parser.parseFile("Sample.kt", source).let { file ->
            walker.findCallAtLine(file, "Sample.kt", line)
        }
}
