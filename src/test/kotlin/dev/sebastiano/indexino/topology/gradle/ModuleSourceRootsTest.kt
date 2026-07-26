package dev.sebastiano.indexino.topology.gradle

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModuleSourceRootsTest {
    private val workspace = Path("src/test/resources/gradle-fixtures/multi-module")

    @Test
    fun `moduleDirectory rejects parent path segments`() {
        assertFailsWith<IllegalArgumentException> {
            ModuleSourceRoots.moduleDirectory(workspace, ":..:sibling")
        }
    }

    @Test
    fun `moduleDirectory rejects current-directory path segments`() {
        assertFailsWith<IllegalArgumentException> {
            ModuleSourceRoots.moduleDirectory(workspace, ":.:ui")
        }
    }

    @Test
    fun `moduleDirectory resolves ordinary modules under the workspace`() {
        val directory = ModuleSourceRoots.moduleDirectory(workspace, ":ui")
        assertEquals(workspace.resolve("ui"), directory)
    }

    @Test
    fun `moduleDirectory resolves the root module to the workspace`() {
        assertEquals(workspace, ModuleSourceRoots.moduleDirectory(workspace, ":"))
    }
}
