package dev.sebastiano.indexino.engine

import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean

/** Suppresses noisy Kotlin/PSI registry warnings on stderr during CLI indexing. */
internal object RegistryWarningStderr {
    private val installed = AtomicBoolean()

    fun installIfNeeded(delegate: PrintStream = System.err) {
        if (installed.compareAndSet(false, true)) {
            System.setErr(FilteringPrintStream(delegate))
        }
    }

    private class FilteringPrintStream(private val delegate: PrintStream) :
        PrintStream(delegate, true) {
        override fun println(value: String?) {
            if (value?.startsWith(REGISTRY_WARNING_PREFIX) != true) delegate.println(value)
        }

        override fun println(value: Any?) = println(value?.toString())

        private companion object {
            const val REGISTRY_WARNING_PREFIX = "WARN: Attempt to load key '"
        }
    }
}
