package dev.sebastiano.indexino.api

import java.nio.file.Path

/** Immutable connection configuration for one workspace runtime. */
public class IndexinoConfiguration
private constructor(
    internal val workspace: Path,
    internal val runtimeAttachMode: RuntimeAttachMode,
) {
    public companion object {
        @JvmStatic
        public fun forWorkspace(workspace: Path): IndexinoConfiguration =
            IndexinoConfiguration(workspace, RuntimeAttachMode.PREFER_DAEMON)
    }

    public fun withRuntimeAttach(mode: RuntimeAttachMode): IndexinoConfiguration =
        IndexinoConfiguration(workspace, mode)
}
