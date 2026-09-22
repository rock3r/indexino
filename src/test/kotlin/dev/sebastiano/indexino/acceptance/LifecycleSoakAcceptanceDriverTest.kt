// SPDX-License-Identifier: UEL-1.0
package dev.sebastiano.indexino.acceptance

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

internal class LifecycleSoakAcceptanceDriverTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `short soak retains old pins across sibling mutations and reclaims after close`() {
        val root = temporary.resolve("invented")
        val output = temporary.resolve("report.json")
        LifecycleSoakAcceptanceDriver.main(arrayOf(root.toString(), "2", output.toString()))
        val report = Json.parseToJsonElement(Files.readString(output)).jsonObject
        assertEquals("passed", report.getValue("status").jsonPrimitive.content)
        val cycles = report.getValue("cycles").jsonArray
        assertEquals(2, cycles.size)
        val mutations = cycles.last().jsonObject.getValue("mutations").jsonArray
        assertEquals(2, mutations.size)
        mutations.forEachIndexed { tree, mutation ->
            val rows = mutation.jsonObject
            assertEquals("Tree${tree}Cycle1:2", rows.getValue("oldRow").jsonPrimitive.content)
            assertEquals("Tree${tree}Cycle2:2", rows.getValue("newRow").jsonPrimitive.content)
        }
        val afterClose = report.getValue("afterClose").jsonObject
        assertEquals("0", afterClose.getValue("refFiles").jsonPrimitive.content)
        assertEquals("0", afterClose.getValue("refMaterializations").jsonPrimitive.content)
        assertTrue(report.getValue("reclaimedPacks").jsonPrimitive.content.toLong() > 0)
        assertEquals("true", report.getValue("cleanupVerified").jsonPrimitive.content)
        assertEquals("not-implemented", report.getValue("agePolicy").jsonPrimitive.content)
        assertFalse(Files.exists(root))
        assertFalse(Files.readString(output).contains(temporary.toString()))
    }
}
