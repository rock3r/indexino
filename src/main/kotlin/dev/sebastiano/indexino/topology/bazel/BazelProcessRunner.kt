package dev.sebastiano.indexino.topology.bazel

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class BazelQueryOutcome(val exitCode: Int, val lines: List<String>)

internal class BazelClientCleanupException(cause: Throwable?) :
    IllegalStateException("Bazel client did not terminate", cause)

internal fun interface BazelProcessRunner {
    fun run(query: String, workspace: Path): BazelQueryOutcome
}

internal object LiveBazelProcessRunner : BazelProcessRunner {
    override fun run(query: String, workspace: Path): BazelQueryOutcome =
        runCommand(listOf("bazel", "query", query, "--output=label"), workspace)

    internal fun runCommand(
        command: List<String>,
        workspace: Path,
        timeoutMillis: Long? = null,
    ): BazelQueryOutcome {
        checkBazelInterrupted()
        val output = Files.createTempFile("indexino-bazel-", ".output")
        try {
            val process =
                ProcessBuilder(command)
                    .directory(workspace.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start()
            var commandFailure: Throwable? = null
            try {
                val exitCode =
                    if (timeoutMillis == null) {
                        process.waitFor()
                    } else {
                        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                            throw TimeoutException("Bazel command exceeded ${timeoutMillis}ms")
                        }
                        process.exitValue()
                    }
                checkBazelInterrupted()
                val lines = output.toFile().bufferedReader().use { it.readLines() }
                checkBazelInterrupted()
                return BazelQueryOutcome(exitCode, lines)
            } catch (interrupted: InterruptedException) {
                commandFailure = interrupted
                Thread.currentThread().interrupt()
                throw interrupted
            } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
                commandFailure = failure
                throw failure
            } finally {
                terminateClient(process, commandFailure)
            }
        } finally {
            try {
                Files.deleteIfExists(output)
            } catch (_: IOException) {
                // A failed client cleanup may still hold the file open on Windows. Do not mask
                // BazelClientCleanupException with a deletion failure interpreted as unavailable.
                output.toFile().deleteOnExit()
            }
        }
    }

    internal fun terminateClient(process: Process, cause: Throwable? = null) {
        // Never traverse descendants: a newly started Bazel server is shared, not refresh-owned.
        if (!process.isAlive) return
        process.destroy()
        if (!awaitExitUninterruptibly(process)) {
            process.destroyForcibly()
            if (!awaitExitUninterruptibly(process)) throw BazelClientCleanupException(cause)
        }
    }

    private fun awaitExitUninterruptibly(process: Process): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var interrupted = Thread.interrupted()
        try {
            while (process.isAlive) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                try {
                    if (process.waitFor(remaining, TimeUnit.NANOSECONDS)) return true
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            return true
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

internal fun checkBazelInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("Bazel discovery stopped")
}
