package com.kotlincodeindex.parse

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.nio.file.Files

class IdeaHomeBootstrapTest {
    private val saved = mutableMapOf<String, String?>()

    @AfterTest
    fun restoreProperties() {
        for ((key, value) in saved) {
            if (value == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, value)
            }
        }
        saved.clear()
    }

    @Test
    fun `ensure sets idea paths when unset`() {
        clearIdeaProperties()

        IdeaHomeBootstrap.ensure()

        val home = System.getProperty("idea.home.path")
        assertNotNull(home)
        assertTrue(Files.isDirectory(java.nio.file.Path.of(home)))
        assertNotNull(System.getProperty("idea.config.path"))
        assertNotNull(System.getProperty("idea.system.path"))
        assertNotNull(System.getProperty("idea.plugins.path"))
    }

    @Test
    fun `ensure is no-op when idea home already set`() {
        clearIdeaProperties()
        System.setProperty("idea.home.path", "/custom/idea/home")

        IdeaHomeBootstrap.ensure()

        assertEquals("/custom/idea/home", System.getProperty("idea.home.path"))
    }

    private fun clearIdeaProperties() {
        for (key in listOf("idea.home.path", "idea.config.path", "idea.system.path", "idea.plugins.path")) {
            saved.putIfAbsent(key, System.getProperty(key))
            System.clearProperty(key)
        }
    }

    private fun assertEquals(expected: String, actual: String?) {
        kotlin.test.assertEquals(expected, actual)
    }
}
