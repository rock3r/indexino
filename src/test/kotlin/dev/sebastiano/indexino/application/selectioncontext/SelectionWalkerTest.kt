package dev.sebastiano.indexino.application.selectioncontext

import dev.sebastiano.indexino.parse.KotlinPsiParser
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
    fun `nested selection containers`() {
        val content = fixture("nested-sc.kt")
        val file = parser.parseFile("nested-sc.kt", content)
        val context = walker.findCallAtLine(file, "nested-sc.kt", line = 8)

        assertEquals("ActionButton", context.callee)
        assertTrue(context.inSelectionContainer)
        assertEquals(2, context.selectionContainerCount)
        assertFalse(context.excludedByDisableSelection)
        assertEquals(2, context.selectionContainers.size)
    }

    @Test
    fun `disable selection excludes interactive site inside SC`() {
        val content = fixture("disable-selection.kt")
        val file = parser.parseFile("disable-selection.kt", content)
        val context = walker.findCallAtLine(file, "disable-selection.kt", line = 8)

        assertEquals("ActionButton", context.callee)
        assertTrue(context.inSelectionContainer)
        assertTrue(context.excludedByDisableSelection)
        assertEquals(1, context.selectionContainerCount)
    }

    @Test
    fun `no selection container`() {
        val content = fixture("no-sc.kt")
        val file = parser.parseFile("no-sc.kt", content)
        val context = walker.findCallAtLine(file, "no-sc.kt", line = 6)

        assertEquals("ActionButton", context.callee)
        assertFalse(context.inSelectionContainer)
        assertEquals(0, context.selectionContainerCount)
        assertFalse(context.excludedByDisableSelection)
    }

    @Test
    fun `local helper inside composable sees selection container`() {
        val content = fixture("local-helper.kt")
        val file = parser.parseFile("local-helper.kt", content)
        val context = walker.findCallAtLine(file, "local-helper.kt", line = 8)

        assertEquals("ActionButton", context.callee)
        assertTrue(context.inSelectionContainer)
        assertEquals(1, context.selectionContainerCount)
        assertFalse(context.excludedByDisableSelection)
    }

    @Test
    fun `import alias resolves selection container`() {
        val content = fixture("import-alias.kt")
        val file = parser.parseFile("import-alias.kt", content)
        val context = walker.findCallAtLine(file, "import-alias.kt", line = 9)

        assertEquals("ActionButton", context.callee)
        assertTrue(context.inSelectionContainer)
        assertEquals(1, context.selectionContainerCount)
        assertFalse(context.excludedByDisableSelection)
    }

    @Test
    fun `known wrapper markdown selectable provides selection via lambda origin`() {
        val content = fixture("markdown-selectable.kt")
        val file = parser.parseFile("markdown-selectable.kt", content)
        val context = walker.findCallAtLine(file, "markdown-selectable.kt", line = 7)

        assertEquals("Text", context.callee)
        assertTrue(context.inSelectionContainer)
        assertEquals("caller-chain", context.confidence)
    }

    private fun fixture(name: String): String =
        checkNotNull(
                javaClass.classLoader.getResourceAsStream("fixtures/selection-context/$name")
            ) {
                "Missing fixture: $name"
            }
            .bufferedReader()
            .readText()
}
