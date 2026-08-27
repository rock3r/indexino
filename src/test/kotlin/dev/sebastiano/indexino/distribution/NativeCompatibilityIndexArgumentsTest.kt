package dev.sebastiano.indexino.distribution

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class NativeCompatibilityIndexArgumentsTest {
    @Test
    fun `compatibility index workload disables auto refresh`() {
        assertTrue(
            "--no-auto-refresh" in NativeCompatibilityFixtures.indexArguments(Path.of("/tmp/ws")),
        )
    }
}
