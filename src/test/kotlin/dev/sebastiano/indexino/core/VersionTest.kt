package dev.sebastiano.indexino.core

import kotlin.test.Test
import kotlin.test.assertEquals

class VersionTest {
    @Test
    fun `version matches Gradle VERSION_NAME`() {
        val expected =
            checkNotNull(System.getProperty("indexino.expectedVersionName")) {
                "Gradle test task must inject indexino.expectedVersionName"
            }
        assertEquals(expected, Version.NAME)
    }
}
