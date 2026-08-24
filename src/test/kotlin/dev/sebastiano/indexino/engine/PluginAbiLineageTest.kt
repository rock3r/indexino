package dev.sebastiano.indexino.engine

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class PluginAbiLineageTest {
    @TempDir lateinit var root: Path

    @Test
    fun `reconstructs compatible range incrementally across additive minors`() {
        dump("1.0.0", "base")
        dump("1.1.0", "base\nadditive")
        dump("1.2.0", "base\nadditive\nmore")

        val lineage =
            PluginAbiLineage.derive(root, "1.2.0") { older, newer ->
                if (newer.readText().startsWith(older.readText())) ApiEvolution.ADDITIVE
                else ApiEvolution.BREAKING
            }

        assertEquals(PluginAbiSupport.of("1.0.0", "1.2.0"), lineage.support)
    }

    @Test
    fun `missing current history fails`() {
        dump("1.0.0", "base")

        val failure =
            assertFailsWith<IllegalStateException> {
                PluginAbiLineage.derive(root, "1.1.0") { _, _ -> ApiEvolution.ADDITIVE }
            }

        assertTrue(failure.message.orEmpty().contains("missing"), failure.message)
    }

    @Test
    fun `missing required historical dump fails`() {
        dump("1.1.0", "additive")

        val failure =
            assertFailsWith<IllegalStateException> {
                PluginAbiLineage.derive(
                    root,
                    "1.1.0",
                    requiredVersions = listOf("1.0.0", "1.1.0"),
                ) { _, _ ->
                    ApiEvolution.ADDITIVE
                }
            }

        assertTrue(failure.message.orEmpty().contains("1.0.0"), failure.message)
    }

    @Test
    fun `additive change requires a minor ABI increment`() {
        dump("1.0.0", "base")
        dump("1.0.1", "base\nadditive")

        assertFailsWith<IllegalStateException> {
            PluginAbiLineage.derive(root, "1.0.1") { _, _ -> ApiEvolution.ADDITIVE }
        }
    }

    @Test
    fun `breaking change requires a major ABI increment`() {
        dump("1.0.0", "base")
        dump("1.1.0", "breaking")

        assertFailsWith<IllegalStateException> {
            PluginAbiLineage.derive(root, "1.1.0") { _, _ -> ApiEvolution.BREAKING }
        }
    }

    @Test
    fun `major increment must represent a breaking change`() {
        dump("1.2.0", "base")
        dump("2.0.0", "base\nadditive")

        assertFailsWith<IllegalStateException> {
            PluginAbiLineage.derive(root, "2.0.0") { _, _ -> ApiEvolution.ADDITIVE }
        }
    }

    private fun dump(version: String, contents: String): Path =
        root.resolve("$version.txt").also {
            it.parent.createDirectories()
            it.writeText(contents)
        }
}
