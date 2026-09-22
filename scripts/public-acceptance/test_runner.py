# SPDX-License-Identifier: UEL-1.0
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import runner


class FakeCommands:
    def __init__(self, actual):
        self.calls = []
        self.actual = actual

    def run(self, argv, cwd, timeout=120):
        self.calls.append((argv, cwd, timeout))
        if argv[-2:] == ["rev-parse", "HEAD"]:
            return self.actual + "\n"
        return ""


class RunnerTest(unittest.TestCase):
    def test_failure_diagnostic_discards_paths_secrets_and_unbounded_messages(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "stderr"
            path.write_text('Exception in thread "main" java.lang.IllegalStateException: Coverage mismatch: '
                            '/private/host/repo token=SECRET\n'
                            'Caused by: dev.sebastiano.indexino.engine.RuntimeProtocolException: '
                            '/private/source -> /private/store: Directory not empty\n' + 'untrusted SECRET\n' * 10000)
            diagnostic = runner.stderr_diagnostic(path)
        self.assertEqual(["java.lang.IllegalStateException", "dev.sebastiano.indexino.engine.RuntimeProtocolException"],
                         diagnostic["exceptionClasses"])
        self.assertEqual(["coverage-mismatch", "directory-not-empty"], diagnostic["signals"])
        self.assertNotIn("SECRET", json.dumps(diagnostic))
        self.assertNotIn("/private", json.dumps(diagnostic))
        self.assertLess(len(json.dumps(diagnostic)), 2048)

    def test_clone_verifies_pin_before_analysis(self):
        pin = "1" * 40
        commands = FakeCommands("2" * 40)
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(ValueError, "commit mismatch"):
                runner.clone(commands, {"url": "https://github.com/rock3r/spectre.git", "commit": pin},
                             Path(tmp) / "corpus")
        self.assertTrue(all("gradlew" not in " ".join(call[0]) for call in commands.calls))

    def test_clone_never_executes_target_build_and_uses_detached_pin(self):
        pin = "1" * 40
        commands = FakeCommands(pin)
        with tempfile.TemporaryDirectory() as tmp:
            runner.clone(commands, {"url": "https://github.com/rock3r/spectre.git", "commit": pin},
                         Path(tmp) / "corpus")
        self.assertTrue(any(call[0][-3:] == ["checkout", "--detach", pin] for call in commands.calls))
        self.assertTrue(all(0 < call[2] <= 1800 for call in commands.calls))

    def test_environment_drops_ambient_jvm_and_git_configuration(self):
        with tempfile.TemporaryDirectory() as tmp:
            env = runner.environment(Path(tmp), {"PATH": "/bin", "JAVA_TOOL_OPTIONS": "secret",
                                                 "GIT_CONFIG_GLOBAL": "/private/config",
                                                 "INDEXINO_CACHE_DIR": "/shared/index"})
            self.assertNotIn("JAVA_TOOL_OPTIONS", env)
            self.assertNotEqual("/private/config", env.get("GIT_CONFIG_GLOBAL"))
            self.assertTrue(env["HOME"].startswith(tmp))
            self.assertTrue(env["INDEXINO_CACHE_DIR"].startswith(tmp))

    def test_detached_timeout_is_never_claimed_supported_on_mac(self):
        self.assertFalse(runner.containment_supported("Darwin"))

    def test_partial_cgroup_initialization_fails_actionably_and_removes_only_child(self):
        parent, child = MagicMock(), MagicMock()
        parent.__truediv__.return_value = child
        files = {name: MagicMock() for name in
                 ("cgroup.kill", "memory.max", "memory.swap.max", "pids.max", "cpu.stat", "memory.peak")}
        child.__truediv__.side_effect = files.__getitem__
        files["memory.max"].write_text.side_effect = PermissionError("controller not delegated")
        with tempfile.TemporaryDirectory() as temporary, patch.object(runner.platform, "system", return_value="Linux"):
            try:
                runner.Commands(parent, Path(temporary))
            except Exception as error:
                self.assertIsInstance(error, RuntimeError)
                self.assertIn("delegation", str(error))
            else:
                self.fail("partial cgroup initialization succeeded")
        child.rmdir.assert_called_once()
        parent.rmdir.assert_not_called()
        self.assertTrue(parent.__truediv__.call_args.args[0].startswith("indexino-"))

    @unittest.skipUnless(sys.platform == "linux" and os.environ.get("INDEXINO_TEST_CGROUP_PARENT"),
                         "requires delegated Linux cgroup-v2; macOS cannot prove detached cleanup")
    def test_timeout_kills_detached_session_and_reports_empty_cgroup(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            child_pid = root / "detached.pid"
            code = ("import os,time,pathlib; pid=os.fork(); "
                    f"pathlib.Path({str(child_pid)!r}).write_text(str(pid)) if pid else os.setsid(); "
                    "time.sleep(120)")
            with runner.Commands(Path(os.environ["INDEXINO_TEST_CGROUP_PARENT"]), root) as commands:
                with self.assertRaises(subprocess.TimeoutExpired):
                    commands.run([sys.executable, "-c", code], root, timeout=1)
            self.assertTrue(commands.cleanup_verified)
            pid = int(child_pid.read_text())
            stat = Path(f"/proc/{pid}/stat")
            self.assertTrue(not stat.exists() or stat.read_text().split()[2] == "Z")


if __name__ == "__main__":
    unittest.main()
