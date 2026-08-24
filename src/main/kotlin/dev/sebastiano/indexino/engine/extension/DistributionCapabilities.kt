package dev.sebastiano.indexino.engine.extension

internal enum class DistributionMode {
    /** Thin/fat JVM: dynamic plugins may load in-process. */
    JVM_DYNAMIC,

    /** R8/native closed-world: dynamic plugin bytecode stays out of the workspace runtime. */
    CLOSED_WORLD,
}

internal object DistributionCapabilities {
    fun current(): DistributionMode =
        if (
            System.getProperty("indexino.roastLauncher") == "true" ||
                System.getProperty("indexino.closedWorld") == "true"
        ) {
            DistributionMode.CLOSED_WORLD
        } else {
            DistributionMode.JVM_DYNAMIC
        }

    fun requiresOutOfProcessExtensions(): Boolean = current() == DistributionMode.CLOSED_WORLD
}
