package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.RuntimeAttachMode
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class CliOneShotConnectTest {
    @Test
    fun `one-shot cli connect stays in process without auto refresh`() {
        val configuration = CliOneShotConnect.configuration(Path.of("/tmp/ws"))
        assertEquals(RuntimeAttachMode.IN_PROCESS, configuration.runtimeAttachMode)
        assertEquals(AutoRefreshMode.DISABLED, configuration.autoRefreshMode)
    }
}
