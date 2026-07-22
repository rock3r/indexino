package dev.sebastiano.indexino.distribution

import com.sun.jna.Native
import java.io.File
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
    val boundaryReady = Files.createTempFile("indexino-native-process-", ".ready")
    try {
        Files.delete(boundaryReady)
        val processBuilder =
            ProcessBuilder(boundaryCommand(boundaryReady, command))
                .directory(workingDirectory.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
        processBuilder.environment().putAll(environment)
        val process = processBuilder.start()
        if (!awaitBoundaryReady(process, boundaryReady)) {
            process.destroyForcibly()
            process.waitFor(BOUNDARY_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val startupError = String(Files.readAllBytes(stderr), Charsets.UTF_8)
            error(
                "Could not establish process boundary: ${command.joinToString(" ")}\n" +
                    "stderr:\n$startupError"
            )
        }
        if (!process.waitFor(timeout, timeoutUnit)) {
            val terminated =
                terminateProcessTree(process, terminationTimeout, terminationTimeoutUnit)
            process.outputStream.close()
            check(terminated) { "Could not terminate: ${command.joinToString(" ")}" }
            throw AssertionError("Process timed out: ${command.joinToString(" ")}")
        }
        return CapturedProcessResult(
            process.exitValue(),
            String(Files.readAllBytes(stdout), Charsets.UTF_8),
            String(Files.readAllBytes(stderr), Charsets.UTF_8),
        )
    } finally {
        deleteCaptureFile(stdout)
        deleteCaptureFile(stderr)
        deleteCaptureFile(boundaryReady)
    }
}

private fun boundaryCommand(boundaryReady: Path, command: List<String>): List<String> {
    val javaExecutable =
        Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java")
    val boundaryClasses =
        Path.of(CapturedProcessBoundary::class.java.protectionDomain.codeSource.location.toURI())
    val jnaJar = Path.of(Native::class.java.protectionDomain.codeSource.location.toURI())
    val classpath = listOf(boundaryClasses, jnaJar).joinToString(File.pathSeparator)
    return buildList {
        add(javaExecutable.toString())
        add("-cp")
        add(classpath)
        add(CapturedProcessBoundary::class.java.name)
        add(boundaryReady.toString())
        addAll(command)
    }
}

private fun awaitBoundaryReady(process: Process, boundaryReady: Path): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUNDARY_START_TIMEOUT_SECONDS)
    val expectedMarker = if (isWindows()) "windows-job" else "posix-process-group"
    while (System.nanoTime() < deadline) {
        if (hasBoundaryMarker(boundaryReady, expectedMarker)) return true
        if (!process.isAlive) return hasBoundaryMarker(boundaryReady, expectedMarker)
        Thread.sleep(POLL_MILLIS)
    }
    return hasBoundaryMarker(boundaryReady, expectedMarker)
}

private fun hasBoundaryMarker(boundaryReady: Path, expectedMarker: String): Boolean =
    Files.isRegularFile(boundaryReady) && Files.readString(boundaryReady) == expectedMarker

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
    if (!CapturedProcessBoundary.terminate(root.pid())) return false
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

private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

private const val BOUNDARY_START_TIMEOUT_SECONDS = 10L
private const val POLL_MILLIS = 10L
