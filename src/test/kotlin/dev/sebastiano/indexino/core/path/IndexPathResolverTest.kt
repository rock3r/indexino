package dev.sebastiano.indexino.core.path

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class IndexPathResolverTest {
    @Test
    fun `resolves index directory under indexino store`() {
        val resolver = IndexPathResolver(Path("/workspace"))
        assertEquals(
            Path("/workspace/.indexino/index/deadbeef"),
            resolver.resolveIndexDirectory("deadbeef"),
        )
        assertEquals(
            Path("/workspace/.indexino/index/deadbeef/base.xodus"),
            resolver.resolveBaseStore("deadbeef"),
        )
        assertEquals(
            Path("/workspace/.indexino/index/deadbeef/manifest.json"),
            resolver.resolveManifest("deadbeef"),
        )
        assertEquals(
            Path("/workspace/.indexino/sessions/s1/delta.xodus"),
            resolver.resolveSessionDeltaStore("s1"),
        )
    }
}
