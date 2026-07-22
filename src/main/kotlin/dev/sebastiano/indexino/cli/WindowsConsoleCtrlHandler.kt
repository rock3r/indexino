package dev.sebastiano.indexino.cli

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native

internal object WindowsConsoleCtrlHandler {
    fun install(
        roastLauncher: Boolean = System.getProperty(ROAST_LAUNCHER_PROPERTY).toBoolean(),
        osName: String = System.getProperty("os.name"),
        enableInterrupts: () -> Unit = ::enableConsoleInterrupts,
        register: (((Int) -> Boolean) -> Unit) = ::registerConsoleHandler,
        halt: (Int) -> Unit = Runtime.getRuntime()::halt,
    ) {
        if (!roastLauncher || !osName.startsWith("Windows", ignoreCase = true)) return
        enableInterrupts()
        register { controlType ->
            if (controlType !in INTERRUPT_CONTROL_TYPES) {
                false
            } else {
                halt(INTERRUPT_EXIT_CODE)
                true
            }
        }
    }

    private fun enableConsoleInterrupts() {
        check(kernel32.SetConsoleCtrlHandler(null, false)) {
            "Could not enable Windows console interrupts (error ${Native.getLastError()})"
        }
    }

    private fun registerConsoleHandler(onControl: (Int) -> Boolean) {
        val callback = HandlerRoutine(onControl)
        check(kernel32.SetConsoleCtrlHandler(callback, true)) {
            "Could not install the Windows console control handler (error ${Native.getLastError()})"
        }
        installedHandler = callback
    }

    private val kernel32: Kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }

    // SetConsoleCtrlHandler does not retain the callback on the JVM side.
    private var installedHandler: HandlerRoutine? = null

    private interface Kernel32 : Library {
        @Suppress("FunctionName")
        fun SetConsoleCtrlHandler(handler: HandlerRoutine?, add: Boolean): Boolean
    }

    private fun interface HandlerRoutine : Callback {
        fun invoke(controlType: Int): Boolean
    }

    private val INTERRUPT_CONTROL_TYPES = setOf(CTRL_C_EVENT, CTRL_BREAK_EVENT)
    private const val CTRL_C_EVENT = 0
    private const val CTRL_BREAK_EVENT = 1
    private const val INTERRUPT_EXIT_CODE = 130
    internal const val ROAST_LAUNCHER_PROPERTY = "indexino.roastLauncher"
}
