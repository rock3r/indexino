package dev.sebastiano.indexino.engine

import com.sun.jna.Platform
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

internal class PosixGitProcessTest {
    @TempDir lateinit var root: Path

    @Test
    fun `successful Git cleans up an fsmonitor helper without an external reaper`() {
        runAdopter(reaping = false, cancel = false)
    }

    @Test
    fun `successful Git also cleans up with an external reaper`() {
        runAdopter(reaping = true, cancel = false)
    }

    @Test
    fun `cancellation kills a live Git group while its hook forks helpers`() {
        runAdopter(reaping = false, cancel = true)
    }

    private fun runAdopter(reaping: Boolean, cancel: Boolean) {
        assumeTrue(Platform.isLinux() && PosixGitProcess.isSupported)
        assumeTrue(
            runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }
                .getOrDefault(false),
            "The isolated Linux orphan adopter requires Python 3",
        )
        val adopter = root.resolve("adopter.py")
        Files.writeString(adopter, ADOPTER)
        val output = root.resolve("adopter.log")
        val process =
            ProcessBuilder(
                    "python3",
                    adopter.toString(),
                    reaping.toString(),
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    System.getProperty("java.class.path"),
                    PosixGitProcessProbe::class.java.name,
                    root.toString(),
                    cancel.toString(),
                )
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Orphan adopter timed out")
            assertEquals(0, process.exitValue(), Files.readString(output))
            assertTrue(Files.readString(output).contains("independent cleanup complete"))
            println(Files.readString(output))
        } finally {
            // The adopter's own watchdog and finally block also cover a failing probe JVM.
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    companion object {
        // Subreaping belongs only to this disposable external controller, never the test JVM.
        private val ADOPTER =
            """
            import ctypes, os, pathlib, signal, subprocess, sys, time
            libc = ctypes.CDLL(None, use_errno=True)
            assert libc.prctl(36, 1, 0, 0, 0) == 0, 'PR_SET_CHILD_SUBREAPER failed'
            reaping = sys.argv[1] == 'true'
            root = pathlib.Path(sys.argv[-2])
            child = subprocess.Popen([sys.argv[2], '-cp', sys.argv[3], *sys.argv[4:]])
            deadline = time.monotonic() + 20
            def helpers():
                path = root / 'helpers'
                return [int(p) for p in path.read_text().split()] if path.exists() else []
            def reap():
                for pid in helpers():
                    try:
                        got, status = os.waitpid(pid, os.WNOHANG)
                        if got:
                            assert os.WIFSIGNALED(status) and os.WTERMSIG(status) == signal.SIGKILL
                    except ChildProcessError:
                        pass
            try:
                while child.poll() is None:
                    assert time.monotonic() < deadline, 'probe watchdog expired'
                    if reaping:
                        reap()
                    time.sleep(.005)
                assert child.returncode == 0, 'probe JVM failed'
                group = int((root / 'group').read_text())
                assert not pathlib.Path(f'/proc/{group}').exists(), 'Git leader was not reaped'
                if reaping:
                    while any(pathlib.Path(f'/proc/{pid}').exists() for pid in helpers()):
                        assert time.monotonic() < deadline, 'external reaper watchdog expired'
                        reap()
                        time.sleep(.005)
                    try:
                        os.killpg(group, 0)
                    except ProcessLookupError:
                        print('reaping control: group is absent', flush=True)
                    else:
                        raise AssertionError('reaping control still has group members')
                else:
                    members = []
                    for path in pathlib.Path('/proc').glob('[0-9]*/stat'):
                        try:
                            raw = path.read_text()
                        except FileNotFoundError:
                            continue
                        fields = raw[raw.rfind(') ') + 2:].split()
                        if int(fields[2]) == group:
                            assert fields[0] in ('Z', 'X') and fields[17] == '1', raw
                            members.append((path.parent.name, fields[0]))
                    assert members, 'non-reaping control must retain zombies'
                    os.killpg(group, 0)
                    print(f'non-reaping control: group exists with dead members {members}', flush=True)
            finally:
                if child.poll() is None:
                    child.kill()
                    child.wait()
                cleanup_deadline = time.monotonic() + 5
                children = pathlib.Path(f'/proc/self/task/{os.getpid()}/children')
                while children.read_text().split():
                    assert time.monotonic() < cleanup_deadline, 'independent cleanup timed out'
                    for pid in children.read_text().split():
                        try:
                            os.kill(int(pid), signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                    try:
                        while os.waitpid(-1, os.WNOHANG)[0]:
                            pass
                    except ChildProcessError:
                        break
                    time.sleep(.005)
                print('independent cleanup complete', flush=True)
            """
                .trimIndent()
    }
}

/** Runs inside a private orphan adopter; its children cannot become the test JVM's zombies. */
internal object PosixGitProcessProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Path.of(args[0])
        val cancel = args[1].toBoolean()
        val repository = Files.createDirectory(root.resolve("repository"))
        fun git(vararg arguments: String) {
            val process =
                ProcessBuilder(listOf("git", "-C", repository.toString()) + arguments).start()
            check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0)
        }
        git("init", "-q")
        Files.writeString(repository.resolve("tracked"), "fixture\n")
        git("add", ".")
        val helpers = root.resolve("helpers")
        val hook = root.resolve("hook")
        val spawn = "sleep 120 </dev/null >/dev/null 2>&1 &\necho $! >> '$helpers'\n"
        Files.writeString(
            hook,
            "#!/bin/sh\n" +
                if (cancel) "while :; do\n${spawn}sleep .01\ndone\n"
                else "${spawn}printf 'fixture-token\\000'\n",
        )
        check(hook.toFile().setExecutable(true))
        val output = Files.createFile(root.resolve("git-output"))
        val child =
            PosixGitProcess.start(
                listOf(
                    "git",
                    "-C",
                    repository.toString(),
                    "-c",
                    "core.fsmonitor=$hook",
                    "-c",
                    "core.fsmonitorHookVersion=2",
                    "status",
                    "--porcelain",
                ),
                output,
                System.getenv() + ("GIT_OPTIONAL_LOCKS" to "0"),
            )
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!Files.exists(helpers) || Files.readAllLines(helpers).size < if (cancel) 3 else 1) {
            check(System.nanoTime() < deadline) { "Git did not spawn fsmonitor helpers" }
            Thread.sleep(5)
        }
        val pid = Files.readAllLines(helpers).first().toLong()
        val stat = Files.readString(Path.of("/proc/$pid/stat"))
        val fields = stat.substringAfterLast(") ").split(' ')
        check(fields[0] != "Z") { "Helper must be live before cleanup" }
        Files.writeString(root.resolve("group"), fields[2])
        if (!cancel) {
            check(child.awaitExit() == 0)
            check(Files.readString(output) == "A  tracked\n")
        } else {
            Thread.currentThread().interrupt()
        }
        try {
            child.close()
            if (cancel) check(Thread.interrupted()) { "close lost caller interruption" }
            Files.readAllLines(helpers).forEach { helper ->
                val path = Path.of("/proc/$helper/stat")
                try {
                    val after = Files.readString(path).substringAfterLast(") ").split(' ')
                    check(after[0] == "Z" || after[0] == "X") { "Live helper survived: $after" }
                } catch (_: NoSuchFileException) {
                    // The reaping control may remove an already killed helper during this check.
                }
            }
            println("cleanup returned with no live helpers; cancel=$cancel")
        } finally {
            Thread.interrupted()
        }
    }
}
