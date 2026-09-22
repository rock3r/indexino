# SPDX-License-Identifier: UEL-1.0
"""Bounded, job-owned public corpus processes. No persistent-host fallback."""

import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import time
import uuid


def clone(commands, corpus, destination):
    pin = corpus["commit"]
    if not re.fullmatch(r"[0-9a-f]{40}", pin):
        raise ValueError("corpus requires a full immutable commit")
    if corpus["url"] not in ("https://github.com/rock3r/spectre.git",
                             "https://github.com/JetBrains/intellij-community.git"):
        raise ValueError("corpus URL is not public-allowlisted")
    commands.run(["git", "init", str(destination)], destination.parent)
    commands.run(["git", "remote", "add", "origin", corpus["url"]], destination)
    commands.run(["git", "fetch", "--depth=1", "origin", pin], destination, timeout=1800)
    commands.run(["git", "-c", "advice.detachedHead=false", "checkout", "--detach", pin], destination,
                 timeout=600)
    actual = commands.run(["git", "rev-parse", "HEAD"], destination).strip()
    if actual != pin:
        raise ValueError(f"commit mismatch: {actual} != {pin}")


def environment(root, ambient):
    env = {key: ambient[key] for key in ("PATH", "JAVA_HOME", "LANG") if key in ambient}
    for key, directory in {"HOME": "home", "XDG_CACHE_HOME": "cache", "TMPDIR": "tmp",
                           "GRADLE_USER_HOME": "gradle", "BAZELISK_HOME": "bazelisk",
                           "INDEXINO_CACHE_DIR": "indexino"}.items():
        path = root / directory
        path.mkdir(parents=True, exist_ok=True)
        env[key] = str(path)
    env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull, GIT_TERMINAL_PROMPT="0")
    return env


def containment_supported(system):
    return system == "Linux"


def stderr_diagnostic(path):
    # Preserve only fixed vocabulary, never exception messages, arguments, paths or tokens.
    with path.open("rb") as stream:
        prefix = stream.read(65536).decode("utf-8", errors="replace")
    classes = ("java.lang.IllegalStateException", "java.lang.IllegalArgumentException",
               "java.lang.OutOfMemoryError", "java.lang.AssertionError", "java.io.IOException",
               "java.nio.file.NoSuchFileException", "kotlinx.coroutines.TimeoutCancellationException",
               "dev.sebastiano.indexino.api.IndexinoException",
               "dev.sebastiano.indexino.engine.RuntimeProtocolException")
    signals = {"Coverage mismatch": "coverage-mismatch", "scope_include_deps_mismatch": "scope-mismatch",
               "Unchanged refresh invoked": "unexpected-producer-work", "OutOfMemoryError": "out-of-memory",
               "environment is locked": "store-lock", "TimeoutCancellationException": "api-timeout",
               "Directory not empty": "directory-not-empty"}
    return {"exceptionClasses": [name for name in classes if name in prefix],
            "signals": [code for text, code in signals.items() if text in prefix],
            "prefixTruncated": path.stat().st_size > 65536,
            "policy": "fixed allowlist from first 64 KiB; raw messages discarded"}


class Commands:
    """One cgroup per job. setsid/double-fork cannot escape cgroup membership.

    Requires operator-delegated controllers on an ephemeral isolated runner. It
    does not create a privileged delegation or stop another job's Bazel server.
    """

    def __init__(self, parent, root, memory_bytes=28 << 30):
        if not containment_supported(platform.system()):
            raise RuntimeError("not-ready: detached-child containment requires Linux cgroup v2")
        self.root = root
        self.group = parent / ("indexino-" + uuid.uuid4().hex)
        self.group.mkdir()
        self.cleanup_verified = False
        self.metrics = []
        self.bazel_shutdown = None
        try:
            self.env = environment(root, os.environ)
            required = ("cgroup.kill", "memory.max", "memory.swap.max", "pids.max", "cpu.stat", "memory.peak")
            missing = [name for name in required if not (self.group / name).exists()]
            if missing:
                raise RuntimeError("not-ready: child cgroup delegation missing: " + ", ".join(missing))
            (self.group / "memory.max").write_text(str(memory_bytes))
            (self.group / "pids.max").write_text("1024")
            (self.group / "memory.swap.max").write_text("0")
        except BaseException as error:
            self.group.rmdir()
            if isinstance(error, OSError):
                raise RuntimeError("not-ready: cgroup controller delegation unavailable in job child; "
                                   "ask operator for an explicitly delegated ephemeral runner; "
                                   "parent controllers and permissions were not changed") from error
            raise

    def __enter__(self):
        return self

    def run(self, argv, cwd, timeout=120):
        if not 0 < timeout <= 7200:
            raise ValueError("command timeout outside 0..7200 seconds")
        ordinal = len(self.metrics)
        stdout_path, stderr_path = (self.root / f"command-{ordinal}.{suffix}" for suffix in ("out", "err"))
        started = time.monotonic_ns()
        cpu_before = self._cpu_micros()
        with stdout_path.open("wb") as stdout, stderr_path.open("wb") as stderr:
            # This runner is single-threaded. Child joins before exec, so no
            # target command can spawn outside the boundary in a startup race.
            def enter():
                (self.group / "cgroup.procs").write_text(str(os.getpid()))
            process = subprocess.Popen(argv, cwd=cwd, env=self.env, stdout=stdout, stderr=stderr,
                                       preexec_fn=enter)
            try:
                deadline = time.monotonic() + timeout
                while process.poll() is None:
                    if time.monotonic() >= deadline:
                        raise subprocess.TimeoutExpired(argv, timeout)
                    if stdout_path.stat().st_size > 64 << 20 or stderr_path.stat().st_size > 64 << 20:
                        raise RuntimeError("command output exceeds 64 MiB limit")
                    if shutil.disk_usage(self.root).free < 10 << 30:
                        raise RuntimeError("resource gate: fewer than 10 GiB free disk")
                    time.sleep(.1)
            except BaseException:
                self._kill_and_wait()
                process.wait(timeout=10)
                raise
            finally:
                self.metrics.append({"command": ordinal, "wallNanos": time.monotonic_ns() - started,
                                     "jobCpuMicros": self._cpu_micros() - cpu_before,
                                     "jobMemoryPeakBytes": int((self.group / "memory.peak").read_text()),
                                     "rssBytes": None, "rssReason": "cgroup memory.peak includes cache, not RSS",
                                     "exitCode": process.returncode,
                                     "stderrDiagnostic": stderr_diagnostic(stderr_path)})
        if stdout_path.stat().st_size > 64 << 20 or stderr_path.stat().st_size > 64 << 20:
            raise RuntimeError("command output exceeds 64 MiB limit")
        if process.returncode:
            raise subprocess.CalledProcessError(process.returncode, argv)
        return stdout_path.read_text(errors="replace")

    def _cpu_micros(self):
        values = dict(line.split() for line in (self.group / "cpu.stat").read_text().splitlines())
        return int(values["usage_usec"])

    def _kill_and_wait(self):
        (self.group / "cgroup.kill").write_text("1")
        deadline = time.monotonic() + 10
        while "populated 1" in (self.group / "cgroup.events").read_text():
            if time.monotonic() >= deadline:
                raise RuntimeError("cleanup failed: cgroup remains populated")
            time.sleep(.05)
        self.cleanup_verified = True

    def __exit__(self, exc_type, exc, traceback):
        try:
            if self.bazel_shutdown:
                argv, cwd = self.bazel_shutdown
                self.run(argv, cwd, timeout=60)
        finally:
            # Explicit private-server shutdown AND kernel boundary cleanup are
            # required, including after timeout or failed shutdown.
            self._kill_and_wait()
            self.group.rmdir()
