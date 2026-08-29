package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.IndexinoConfiguration
import dev.sebastiano.indexino.api.RuntimeAttachMode
import java.nio.file.Path

/** One-shot CLI attach: read/query without starting or preferring a local daemon. */
internal object CliOneShotConnect {
    fun configuration(workspace: Path): IndexinoConfiguration =
        IndexinoConfiguration.forWorkspace(workspace)
            .withAutoRefresh(AutoRefreshMode.DISABLED)
            .withRuntimeAttach(RuntimeAttachMode.IN_PROCESS)

    fun connect(workspace: Path): Indexino =
        Indexino.connectBlockingForCli(configuration(workspace))
}
