package dev.sebastiano.indexino.api

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class AutoRefreshModeTest {
    @Test
    fun `workspace configuration enables auto refresh by default`() {
        val configuration = IndexinoConfiguration.forWorkspace(Path.of("/workspace"))

        assertEquals(AutoRefreshMode.ENABLED, configuration.autoRefreshMode)
    }

    @Test
    fun `workspace configuration can disable auto refresh`() {
        val configuration =
            IndexinoConfiguration.forWorkspace(Path.of("/workspace"))
                .withAutoRefresh(AutoRefreshMode.DISABLED)

        assertEquals(AutoRefreshMode.DISABLED, configuration.autoRefreshMode)
    }
}
