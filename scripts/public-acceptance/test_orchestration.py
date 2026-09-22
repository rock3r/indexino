# SPDX-License-Identifier: UEL-1.0
import hashlib
import json
from pathlib import Path
import tempfile
import subprocess
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import run


class FakeJob:
    instances = []
    def __init__(self, parent, root):
        self.root = root
        self.metrics = []
        self.cleanup_verified = False
        self.calls = []
        self.instances.append(self)

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.cleanup_verified = True

    def run(self, argv, cwd, timeout=120):
        self.calls.append(argv)
        if any(name in argv for name in ("dev.sebastiano.indexino.acceptance.RetrievalAcceptanceDriver",
                                         "dev.sebastiano.indexino.acceptance.CallLifecycleAcceptanceDriver")):
            Path(argv[-1]).write_text(json.dumps({"status": "passed", "queries": []}))
        elif "dev.sebastiano.indexino.acceptance.PublicAcceptanceDriver" in argv:
            instrumented = argv[-1] == "instrumented"
            report_path = Path(argv[-2] if instrumented else argv[-1])
            contract_path = Path(argv[-3] if instrumented else argv[-2])
            contract = json.loads(contract_path.read_text())
            report_path.write_text(json.dumps({"status": "passed", "generation": "same-input-generation",
                "sources": contract["sources"], "coldApiNanos": 7, "warmRefreshApiNanos": [1, 2, 3, 4, 5],
                "queryApiNanos": {"ComposeAutomator": list(range(100))},
                "diagnostics": {"phaseEvents": []}}))
        return "fake JDK 25"


def fake_clone(commands, corpus, workspace):
    if commands is not None:
        commands.calls.append(["clone-corpus"])
    for module in ("core", "input-coordinator", "input-coordinator-server"):
        source = workspace / module / "src/main/kotlin/Invented.kt"
        source.parent.mkdir(parents=True)
        source.write_text("class Invented\n")


class OrchestrationTest(unittest.TestCase):
    def test_controller_failure_stays_actionable_without_exporting_exception_text(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "report.json"
            argv = ["run.py", "--execute", "--corpus", "spectre", "--artifact", "driver.zip",
                    "--sha256", "digest", "--report", str(report)]
            with patch.object(sys, "argv", argv), patch.object(run, "readiness", return_value=[]), \
                    patch.object(run, "execute", side_effect=RuntimeError(
                        "not-ready: child cgroup delegation missing: /private/path SECRET")):
                self.assertEqual(2, run.main())
            result = json.loads(report.read_text())
        self.assertEqual("not-ready", result["status"])
        self.assertIn("delegated", " ".join(result["reasons"]))
        self.assertNotIn("SECRET", json.dumps(result))
        self.assertNotIn("/private/path", json.dumps(result))

    def test_failed_corpus_repeat_retains_checkpoint_after_disposable_cleanup(self):
        class FailedCorpus(FakeJob):
            def run(self, argv, cwd, timeout=120):
                result = super().run(argv, cwd, timeout)
                if "dev.sebastiano.indexino.acceptance.PublicAcceptanceDriver" in argv:
                    Path(argv[-1]).write_text(json.dumps({"status": "incomplete", "generation": "g-failed",
                        "sources": ["unexpected/Invented.kt"],
                        "diagnostics": {"phaseEvents": [{"event": "refresh_started", "observedNanoTime": 3}]}}))
                    self.metrics.append({"exitCode": 7, "stderrDiagnostic": {
                        "exceptionClasses": ["java.lang.IllegalStateException"], "signals": ["coverage-mismatch"]}})
                    raise subprocess.CalledProcessError(7, argv)
                return result
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "driver.zip"
            artifact.write_bytes(b"fake artifact")
            args = SimpleNamespace(corpus="spectre", artifact=artifact,
                sha256=hashlib.sha256(b"fake artifact").hexdigest(), scratch=root, cgroup_parent=root)
            report = {}
            with patch.object(run, "Commands", FailedCorpus), patch.object(run, "clone", fake_clone), \
                    patch.object(run, "extract_driver"):
                with self.assertRaises(subprocess.CalledProcessError):
                    run.execute(args, report)
            self.assertFalse(FailedCorpus.instances[-1].root.exists())
        self.assertTrue(report["cleanupVerified"])
        self.assertEqual(1, len(report["scopes"]))
        repeat = report["scopes"][0]["repeats"][0]
        self.assertEqual("g-failed", repeat["generation"])
        self.assertEqual(["unexpected/Invented.kt"], repeat["sources"])
        self.assertEqual("failed", repeat["status"])
        self.assertEqual(7, repeat["exitCode"])
        self.assertEqual("refresh_started", repeat["diagnostics"]["phaseEvents"][0]["event"])
        self.assertEqual(["coverage-mismatch"], report["commands"][-1]["stderrDiagnostic"]["signals"])

    def test_failed_fixture_retains_checkpoint_and_runs_other_lane(self):
        class FailedFixture(FakeJob):
            def run(self, argv, cwd, timeout=120):
                result = super().run(argv, cwd, timeout)
                if "dev.sebastiano.indexino.acceptance.RetrievalAcceptanceDriver" in argv:
                    Path(argv[-1]).write_text(json.dumps({"status": "incomplete", "queries": []}))
                    self.cleanup_verified = True
                    raise subprocess.CalledProcessError(1, argv)
                return result
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "driver.zip"
            artifact.write_bytes(b"fake artifact")
            args = SimpleNamespace(corpus="spectre", artifact=artifact,
                sha256=hashlib.sha256(b"fake artifact").hexdigest(), scratch=root, cgroup_parent=root)
            report = {}
            with patch.object(run, "Commands", FailedFixture), patch.object(run, "clone", fake_clone), \
                    patch.object(run, "extract_driver"):
                with self.assertRaises((AssertionError, subprocess.CalledProcessError)):
                    run.execute(args, report)
            self.assertEqual(4, len(report["fixtures"]))
            self.assertTrue(report["cleanupVerified"])
            self.assertEqual([1, 1, 0, 0], [f["exitCode"] for f in report["fixtures"]])
            self.assertNotIn(["clone-corpus"], FailedFixture.instances[-1].calls)

    def test_timed_out_fixture_retains_checkpoint_after_cleanup_and_runs_independent_lanes(self):
        class TimedOutFixture(FakeJob):
            failed = False
            def run(self, argv, cwd, timeout=120):
                result = super().run(argv, cwd, timeout)
                if (not self.failed and
                        "dev.sebastiano.indexino.acceptance.RetrievalAcceptanceDriver" in argv):
                    self.failed = True
                    Path(argv[-1]).write_text(json.dumps(
                        {"status": "passed", "queries": [], "evidence": "before-timeout"}))
                    self.cleanup_verified = True
                    raise subprocess.TimeoutExpired(argv, timeout)
                return result
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "driver.zip"
            artifact.write_bytes(b"fake artifact")
            args = SimpleNamespace(corpus="spectre", artifact=artifact,
                sha256=hashlib.sha256(b"fake artifact").hexdigest(), scratch=root, cgroup_parent=root)
            report = {}
            with patch.object(run, "Commands", TimedOutFixture), patch.object(run, "clone", fake_clone), \
                    patch.object(run, "extract_driver"):
                with self.assertRaises(AssertionError):
                    run.execute(args, report)
            disposable_root = TimedOutFixture.instances[-1].root
            self.assertFalse(disposable_root.exists())
        self.assertEqual(4, len(report["fixtures"]))
        self.assertEqual("failed", report["fixtures"][0]["status"])
        self.assertEqual("before-timeout", report["fixtures"][0]["evidence"])
        self.assertEqual("TimeoutExpired", report["fixtures"][0]["failureType"])
        self.assertIsNone(report["fixtures"][0]["exitCode"])
        self.assertNotIn(["clone-corpus"], TimedOutFixture.instances[-1].calls)

    def test_bounded_output_failure_runs_independent_lanes_but_resource_gate_stops_them(self):
        class GuardFailure(FakeJob):
            failure = "command output exceeds 64 MiB limit"
            exception = RuntimeError
            failed = False
            def run(self, argv, cwd, timeout=120):
                result = super().run(argv, cwd, timeout)
                if (not self.failed and
                        "dev.sebastiano.indexino.acceptance.RetrievalAcceptanceDriver" in argv):
                    self.failed = True
                    self.cleanup_verified = True
                    raise self.exception(self.failure)
                return result

        def execute_with(failure, exception=RuntimeError):
            GuardFailure.failure = failure
            GuardFailure.exception = exception
            GuardFailure.instances = []
            GuardFailure.failed = False
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                artifact = root / "driver.zip"
                artifact.write_bytes(b"fake artifact")
                args = SimpleNamespace(corpus="spectre", artifact=artifact,
                    sha256=hashlib.sha256(b"fake artifact").hexdigest(), scratch=root, cgroup_parent=root)
                report = {}
                with patch.object(run, "Commands", GuardFailure), patch.object(run, "clone", fake_clone), \
                        patch.object(run, "extract_driver"):
                    with self.assertRaises((AssertionError, RuntimeError, KeyboardInterrupt)):
                        run.execute(args, report)
                self.assertFalse(GuardFailure.instances[-1].root.exists())
            return report, GuardFailure.instances[-1]

        output_report, output_job = execute_with("command output exceeds 64 MiB limit")
        self.assertEqual(4, len(output_report["fixtures"]))
        self.assertEqual("failed", output_report["fixtures"][0]["status"])
        self.assertNotIn(["clone-corpus"], output_job.calls)

        resource_report, resource_job = execute_with("resource gate: fewer than 10 GiB free disk")
        self.assertEqual(1, len(resource_report["fixtures"]))
        self.assertEqual("failed", resource_report["fixtures"][0]["status"])
        self.assertNotIn(["clone-corpus"], resource_job.calls)

        interrupted_report, interrupted_job = execute_with("interrupted", KeyboardInterrupt)
        self.assertEqual(1, len(interrupted_report["fixtures"]))
        self.assertEqual("KeyboardInterrupt", interrupted_report["fixtures"][0]["failureType"])
        self.assertNotIn(["clone-corpus"], interrupted_job.calls)

    def test_fake_commands_exercise_both_scopes_three_cold_and_separate_diagnostic(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "driver.zip"
            artifact.write_bytes(b"fake artifact")
            args = SimpleNamespace(corpus="spectre", artifact=artifact,
                sha256=hashlib.sha256(b"fake artifact").hexdigest(), scratch=root, cgroup_parent=root)
            report = {}
            with patch.object(run, "Commands", FakeJob), patch.object(run, "clone", fake_clone), \
                    patch.object(run, "extract_driver"):
                run.execute(args, report)
        self.assertEqual("passed", report["status"])
        self.assertTrue(report["cleanupVerified"])
        self.assertEqual([False, True], [s["includeDependencies"] for s in report["scopes"]])
        self.assertEqual([1, 3], [s.get("sourceCount") for s in report["scopes"]])
        for scope in report["scopes"]:
            for repeat in scope["repeats"] + [scope["instrumentedDiagnostic"]]:
                self.assertNotIn("sources", repeat, "successful large inventories must not be duplicated in report")
                self.assertEqual(scope["sourceCount"], repeat["sourceCount"])
                self.assertEqual(scope["inventorySha256"], repeat["inventorySha256"])
        self.assertTrue(all(len(s["repeats"]) == 3 for s in report["scopes"]))
        self.assertTrue(all("instrumentedDiagnostic" in s for s in report["scopes"]))
        self.assertEqual(["manual", "watcher", "calls-manual", "calls-watcher"], [f["lane"] for f in report["fixtures"]])
        self.assertEqual(8, sum("dev.sebastiano.indexino.acceptance.PublicAcceptanceDriver" in c
                                for c in FakeJob.instances[-1].calls))

    def test_inventory_excludes_tests_but_keeps_all_three_independent_roots(self):
        with tempfile.TemporaryDirectory() as temporary:
            workspace = Path(temporary)
            fake_clone(None, None, workspace)
            decoy = workspace / "core/src/test/kotlin/Decoy.kt"
            decoy.parent.mkdir(parents=True)
            decoy.write_text("class Decoy")
            self.assertEqual(["core/src/main/kotlin/Invented.kt"],
                             run.gradle_inventory(workspace, ["core/src"]))


if __name__ == "__main__":
    unittest.main()
