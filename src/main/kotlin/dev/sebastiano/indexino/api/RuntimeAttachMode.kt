package dev.sebastiano.indexino.api

/** Selects whether a client attaches to the shared local runtime or runs only in this process. */
public enum class RuntimeAttachMode {
    PREFER_DAEMON,
    IN_PROCESS,
}
