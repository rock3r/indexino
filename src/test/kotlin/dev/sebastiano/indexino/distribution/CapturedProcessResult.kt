package dev.sebastiano.indexino.distribution

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal data class CapturedProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

internal fun runCapturedProcess(
    workingDirectory: Path,
    command: List<String>,
    environment: Map<String, String> = emptyMap(),
    timeout: Long,
    timeoutUnit: TimeUnit,
    terminationTimeout: Long,
    terminationTimeoutUnit: TimeUnit,
): CapturedProcessResult {
    val stdout = Files.createTempFile("indexino-native-process-", ".stdout")
    val stderr = Files.createTempFile("indexino-native-process-", ".stderr")
    try {
        val processBuilder =
            ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
        processBuilder.environment().putAll(environment)
        val process = processBuilder.start()
        if (!process.waitFor(timeout, timeoutUnit)) {
            val terminated =
                terminateProcessTree(process, terminationTimeout, terminationTimeoutUnit)
            process.outputStream.close()
            check(terminated) { "Could not terminate: ${command.joinToString(" ")}" }
            throw AssertionError("Process timed out: ${command.joinToString(" ")}")
        }
        return CapturedProcessResult(
            process.exitValue(),
            Files.readString(stdout),
            Files.readString(stderr),
        )
    } finally {
        deleteCaptureFile(stdout)
        deleteCaptureFile(stderr)
    }
}

private fun deleteCaptureFile(path: Path) {
    try {
        Files.deleteIfExists(path)
    } catch (_: IOException) {
        path.toFile().deleteOnExit()
    }
}

internal fun terminateProcessTree(process: Process, timeout: Long, timeoutUnit: TimeUnit): Boolean {
    val root = process.toHandle()
    val handles = linkedMapOf<Long, ProcessHandle>()
    val timeoutNanos = timeoutUnit.toNanos(timeout)
    val finalDeadline = System.nanoTime() + timeoutNanos

    refreshProcessTree(root, handles)
    root.destroyForcibly()
    terminateDescendants(root, handles)
    return awaitTermination(root, handles, finalDeadline)
}

private fun refreshProcessTree(root: ProcessHandle, handles: MutableMap<Long, ProcessHandle>) {
    handles[root.pid()] = root
    if (root.isAlive) {
        root.descendants().use { descendants ->
            descendants.forEach { descendant -> handles[descendant.pid()] = descendant }
        }
    }
}

private fun terminateDescendants(root: ProcessHandle, handles: MutableMap<Long, ProcessHandle>) {
    refreshProcessTree(root, handles)
    handles.values
        .filter { it.pid() != root.pid() }
        .sortedByDescending(::processDepth)
        .filter(ProcessHandle::isAlive)
        .forEach(ProcessHandle::destroyForcibly)
}

private fun processDepth(handle: ProcessHandle): Int {
    var depth = 0
    var parent = handle.parent()
    while (parent.isPresent) {
        depth += 1
        parent = parent.get().parent()
    }
    return depth
}

private fun awaitTermination(
    root: ProcessHandle,
    handles: MutableMap<Long, ProcessHandle>,
    deadlineNanos: Long,
): Boolean {
    while (true) {
        refreshProcessTree(root, handles)
        terminateDescendants(root, handles)
        if (handles.values.none(ProcessHandle::isAlive)) return true
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) return false
        Thread.sleep(minOf(TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1L, POLL_MILLIS))
    }
}

private const val POLL_MILLIS = 10L
