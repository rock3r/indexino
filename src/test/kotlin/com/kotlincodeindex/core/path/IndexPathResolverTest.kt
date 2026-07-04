package com.kotlincodeindex.core.path

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.io.path.Path

class IndexPathResolverTest {
    @Test
    fun `resolves index directory under kotlin-index store`() {
        val resolver = IndexPathResolver(Path("/workspace"))
        assertEquals(
            Path("/workspace/.kotlin-index/index/deadbeef"),
            resolver.resolveIndexDirectory("deadbeef"),
        )
        assertEquals(
            Path("/workspace/.kotlin-index/index/deadbeef/base.xodus"),
            resolver.resolveBaseStore("deadbeef"),
        )
        assertEquals(
            Path("/workspace/.kotlin-index/index/deadbeef/manifest.json"),
            resolver.resolveManifest("deadbeef"),
        )
        assertEquals(
            Path("/workspace/.kotlin-index/sessions/s1/delta.xodus"),
            resolver.resolveSessionDeltaStore("s1"),
        )
    }
}
