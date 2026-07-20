package dev.sebastiano.indexino.topology.bazel

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class BazelProjectFileTest {
    @Test
    fun `parses directories and targets from bazelproject`() {
        val parsed = BazelProjectFile.parse(Path("src/test/resources/fixtures/bazel/.bazelproject"))
        assertEquals(listOf(".", "plugins/foo"), parsed.directories)
        assertEquals(listOf("//plugins/foo/ui:ui", "//plugins/foo/ui:ui-test"), parsed.targets)
    }
}
