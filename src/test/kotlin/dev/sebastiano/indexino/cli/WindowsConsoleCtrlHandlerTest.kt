package dev.sebastiano.indexino.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsConsoleCtrlHandlerTest {
    @Test
    fun `windows interrupt handler halts with a nonzero conventional exit code`() {
        var installed: ((Int) -> Boolean)? = null
        var exitCode: Int? = null

        WindowsConsoleCtrlHandler.install(
            osName = "Windows 11",
            register = { installed = it },
            halt = { exitCode = it },
        )

        val handler = assertNotNull(installed)
        assertTrue(handler(0))
        assertEquals(130, exitCode)
    }

    @Test
    fun `windows handler ignores non-interrupt console events`() {
        var installed: ((Int) -> Boolean)? = null
        var halted = false

        WindowsConsoleCtrlHandler.install(
            osName = "Windows 11",
            register = { installed = it },
            halt = { halted = true },
        )

        assertFalse(assertNotNull(installed)(2))
        assertFalse(halted)
    }

    @Test
    fun `non-windows launch does not install a console interrupt handler`() {
        var registered = false
        var halted = false

        WindowsConsoleCtrlHandler.install(
            osName = "Linux",
            register = { registered = true },
            halt = { halted = true },
        )

        assertFalse(registered)
        assertFalse(halted)
    }
}
