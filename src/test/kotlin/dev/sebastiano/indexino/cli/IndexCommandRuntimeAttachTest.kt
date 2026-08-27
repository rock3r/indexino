package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.api.RuntimeAttachMode
import kotlin.test.Test
import kotlin.test.assertEquals

class IndexCommandRuntimeAttachTest {
    @Test
    fun `closed world one-shot index attaches in process`() {
        assertEquals(
            RuntimeAttachMode.IN_PROCESS,
            IndexCommand.resolveRuntimeAttachForCli(
                hasRegisteredPlugins = false,
                requiresOutOfProcessExtensions = true,
            ),
        )
    }

    @Test
    fun `dynamic cli with trusted plugins attaches in process`() {
        assertEquals(
            RuntimeAttachMode.IN_PROCESS,
            IndexCommand.resolveRuntimeAttachForCli(
                hasRegisteredPlugins = true,
                requiresOutOfProcessExtensions = false,
            ),
        )
    }

    @Test
    fun `dynamic cli without plugins prefers daemon`() {
        assertEquals(
            RuntimeAttachMode.PREFER_DAEMON,
            IndexCommand.resolveRuntimeAttachForCli(
                hasRegisteredPlugins = false,
                requiresOutOfProcessExtensions = false,
            ),
        )
    }
}
