package dev.sebastiano.indexino.engine

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.ptr.IntByReference
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Owns a Git process group from spawn through cancellation and reaping, without a shell or JVM. */
internal class PosixGitProcess private constructor(private val pid: Int) : AutoCloseable {
    fun awaitExit(): Int =
        Memory(SIGINFO_BYTES).use { info ->
            do {
                info.clear()
                if (libc.waitid(P_PID, pid, info, WEXITED or WNOHANG or wnowait) != 0) {
                    if (Native.getLastError() != EINTR) throw nativeFailure("waitid")
                    info.clear()
                }
                if (info.getInt(pidOffset) == 0) Thread.sleep(POLL_MILLIS)
            } while (info.getInt(pidOffset) == 0)
            val status = info.getInt(statusOffset)
            if (info.getInt(CODE_OFFSET) == CLD_EXITED) status else SIGNAL_EXIT_BASE + status
        }

    override fun close() {
        var interrupted = Thread.interrupted()
        try {
            // waitid(WNOWAIT) reserves the leader's PID until this signal, preventing group-ID
            // reuse. The kernel boundary also includes hooks forked during cancellation.
            val signalError = if (libc.kill(-pid, SIGKILL) == 0) null else Native.getLastError()
            when (signalError) {
                null,
                ESRCH,
                EPERM -> Unit
                else -> throw nativeFailure("kill Git process group")
            }
            val status = IntByReference()
            // Darwin returns EPERM when a group contains only zombies. Reap that leader, but
            // never block waiting for a live process after an unsuccessful group signal.
            var reaped: Int
            do {
                reaped = libc.waitpid(pid, status, if (signalError == null) 0 else WNOHANG)
                if (reaped == -1 && Native.getLastError() != EINTR) throw nativeFailure("waitpid")
            } while (reaped == -1)
            check(reaped == pid) { "Git process $pid survived group signal (errno $signalError)" }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REAP_SECONDS)
            while (true) {
                if (libc.kill(-pid, 0) != 0) {
                    when (Native.getLastError()) {
                        ESRCH -> return
                        EPERM -> Unit // Darwin's orphan zombies still await init's reaper.
                        else -> throw nativeFailure("check Git process group")
                    }
                }
                check(System.nanoTime() < deadline) { "Git process group $pid survived cleanup" }
                try {
                    Thread.sleep(POLL_MILLIS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    companion object {
        val isSupported: Boolean by lazy {
            (Platform.isMac() || Platform.isLinux()) &&
                Platform.is64Bit() &&
                (Platform.isIntel() || Platform.isARM()) &&
                (Platform.isMac() ||
                    try {
                        NativeLibrary.getInstance(Platform.C_LIBRARY_NAME)
                            .getFunction("posix_spawn_file_actions_addclosefrom_np")
                        true
                    } catch (_: UnsatisfiedLinkError) {
                        // Older libc retains the ProcessBuilder path, rather than leaking JVM FDs.
                        false
                    })
        }

        fun start(
            command: List<String>,
            output: Path,
            environment: Map<String, String>,
        ): PosixGitProcess {
            Memory(if (Platform.isMac()) Native.POINTER_SIZE.toLong() else LINUX_ACTIONS_BYTES)
                .use { actions ->
                    checkResult(libc.posix_spawn_file_actions_init(actions), "spawn actions init")
                    try {
                        checkResult(
                            libc.posix_spawn_file_actions_addopen(actions, 0, "/dev/null", 0, 0),
                            "spawn stdin",
                        )
                        // The caller owns an empty temporary output file. O_WRONLY is 1 on both
                        // supported libc ABIs; no creation/truncation flags or shell are needed.
                        checkResult(
                            libc.posix_spawn_file_actions_addopen(
                                actions,
                                1,
                                output.toString(),
                                1,
                                0,
                            ),
                            "spawn stdout",
                        )
                        checkResult(
                            libc.posix_spawn_file_actions_addopen(actions, 2, "/dev/null", 1, 0),
                            "spawn stderr",
                        )
                        if (Platform.isLinux()) {
                            checkResult(
                                libc.posix_spawn_file_actions_addclosefrom_np(actions, STDIO_FDS),
                                "spawn close inherited descriptors",
                            )
                        }
                        return spawn(command, environment, actions)
                    } finally {
                        libc.posix_spawn_file_actions_destroy(actions)
                    }
                }
        }

        private fun spawn(
            command: List<String>,
            environment: Map<String, String>,
            actions: Pointer,
        ): PosixGitProcess =
            Memory(if (Platform.isMac()) Native.POINTER_SIZE.toLong() else LINUX_ATTR_BYTES).use {
                attributes ->
                checkResult(libc.posix_spawnattr_init(attributes), "spawn attributes init")
                try {
                    checkResult(
                        libc.posix_spawnattr_setpgroup(attributes, 0),
                        "spawn process group",
                    )
                    checkResult(
                        libc.posix_spawnattr_setflags(
                            attributes,
                            (POSIX_SPAWN_SETPGROUP or
                                    if (Platform.isMac()) POSIX_SPAWN_CLOEXEC_DEFAULT else 0)
                                .toShort(),
                        ),
                        "spawn flags",
                    )
                    val pid = IntByReference()
                    val argv = StringArray(command.toTypedArray())
                    val envp =
                        StringArray(
                            environment.map { (key, value) -> "$key=$value" }.toTypedArray()
                        )
                    checkResult(
                        libc.posix_spawnp(pid, command.first(), actions, attributes, argv, envp),
                        "posix_spawnp",
                    )
                    PosixGitProcess(pid.value)
                } finally {
                    libc.posix_spawnattr_destroy(attributes)
                }
            }

        private fun checkResult(result: Int, operation: String) {
            if (result != 0) throw IOException("$operation failed with errno $result")
        }

        private fun nativeFailure(operation: String): IOException =
            IOException("$operation failed with errno ${Native.getLastError()}")

        // Darwin spawn types are opaque pointers. These Linux sizes and siginfo offsets are the
        // stable x86-64/aarch64 libc ABI from spawn.h and bits/types/siginfo_t.h.
        private const val LINUX_ACTIONS_BYTES = 80L
        private const val LINUX_ATTR_BYTES = 336L
        private const val SIGINFO_BYTES = 128L
        private const val CODE_OFFSET = 8L
        private val pidOffset = if (Platform.isMac()) 12L else 16L
        private val statusOffset = if (Platform.isMac()) 20L else 24L
        private val wnowait = if (Platform.isMac()) 0x20 else 0x01000000
        private const val POSIX_SPAWN_SETPGROUP = 2
        private const val POSIX_SPAWN_CLOEXEC_DEFAULT = 0x4000
        private const val P_PID = 1
        private const val WNOHANG = 1
        private const val WEXITED = 4
        private const val CLD_EXITED = 1
        private const val SIGKILL = 9
        private const val SIGNAL_EXIT_BASE = 128
        private const val STDIO_FDS = 3
        private const val EPERM = 1
        private const val EINTR = 4
        private const val ESRCH = 3
        private const val POLL_MILLIS = 25L
        private const val REAP_SECONDS = 5L
        private val libc: LibC by lazy { Native.load(Platform.C_LIBRARY_NAME, LibC::class.java) }
    }

    @Suppress("FunctionName", "TooManyFunctions")
    private interface LibC : Library {
        fun posix_spawnp(
            pid: IntByReference,
            executable: String,
            actions: Pointer,
            attributes: Pointer,
            argv: Pointer,
            envp: Pointer,
        ): Int

        fun posix_spawn_file_actions_init(actions: Pointer): Int

        fun posix_spawn_file_actions_destroy(actions: Pointer): Int

        fun posix_spawn_file_actions_addopen(
            actions: Pointer,
            fd: Int,
            path: String,
            flags: Int,
            mode: Int,
        ): Int

        fun posix_spawn_file_actions_addclosefrom_np(actions: Pointer, fd: Int): Int

        fun posix_spawnattr_init(attributes: Pointer): Int

        fun posix_spawnattr_destroy(attributes: Pointer): Int

        fun posix_spawnattr_setpgroup(attributes: Pointer, group: Int): Int

        fun posix_spawnattr_setflags(attributes: Pointer, flags: Short): Int

        fun waitid(type: Int, pid: Int, info: Pointer, options: Int): Int

        fun waitpid(pid: Int, status: IntByReference, options: Int): Int

        fun kill(pid: Int, signal: Int): Int
    }
}
