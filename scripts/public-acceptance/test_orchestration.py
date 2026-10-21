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
    def test_intellij_acquires_pinned_android_before_full_scope_enumeration(self):
        class BazelJob(FakeJob):
            def run(self, argv, cwd, timeout=120):
                if "query" in argv:
                    self.calls.append(argv)
                    return "source file //core/src/main/kotlin:Invented.kt\n"
                return super().run(argv, cwd, timeout)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "driver.zip"
            artifact.write_bytes(b"fake artifact")
            args = SimpleNamespace(corpus="intellij", artifact=artifact,
                sha256=hashlib.sha256(b"fake artifact").hexdigest(), scratch=root, cgroup_parent=root)
            with patch.object(run, "Commands", BazelJob), patch.object(run, "extract_driver"), \
                    patch.object(run, "bazel_setup", return_value=["bazel"]), \
                    patch.object(run, "clone", side_effect=fake_clone) as acquired:
                run.execute(args, {})
        self.assertEqual(2, acquired.call_count)
        companion = acquired.call_args_list[1].args
        self.assertEqual("https://github.com/JetBrains/android.git", companion[1]["url"])
        self.assertEqual("0c3aa8d0201b5f0d79e1952d2a472b7ed34b128c", companion[1]["commit"])
        self.assertEqual("android", companion[2].name)
        self.assertIn("kind('source file|generated file', deps(//:main))",
                      next(call for call in BazelJob.instances[-1].calls if "query" in call))

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

    def test_malformed_fixture_checkpoints_fail_closed_and_run_independent_lanes(self):
        checkpoints = [[], {"queries": 1}, {"status": "passed", "queries": [{}]}]
        for malformed in checkpoints:
            with self.subTest(checkpoint=malformed):
                class MalformedFixture(FakeJob):
                    def run(self, argv, cwd, timeout=120):
                        result = super().run(argv, cwd, timeout)
                        output = Path(argv[-1])
                        if output.name.startswith("fixture-") and output.suffix == ".json":
                            checkpoint = (malformed if output.name != "fixture-calls-watcher.json" else
                                          {"status": "passed", "queries": [{"apiNanos": [1, 2]}],
                                           "evidence": "independent-lane"})
                            output.write_text(json.dumps(checkpoint))
                        return result
                with tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary)
                    artifact = root / "driver.zip"
                    artifact.write_bytes(b"fake artifact")
                    args = SimpleNamespace(corpus="spectre", artifact=artifact,
                        sha256=hashlib.sha256(b"fake artifact").hexdigest(), scratch=root, cgroup_parent=root)
                    report = {}
                    with patch.object(run, "Commands", MalformedFixture), \
                            patch.object(run, "clone", fake_clone), patch.object(run, "extract_driver"):
                        with self.assertRaises(AssertionError):
                            run.execute(args, report)
                    self.assertFalse(MalformedFixture.instances[-1].root.exists())
                self.assertTrue(report["cleanupVerified"])
                self.assertEqual(["manual", "watcher", "calls-manual", "calls-watcher"],
                                 [f["lane"] for f in report["fixtures"]])
                for fixture in report["fixtures"][:3]:
                    self.assertEqual("failed", fixture["status"])
                    self.assertEqual([], fixture["queries"])
                    self.assertRegex(fixture["checkpointError"], r"^[A-Za-z]{1,64}$")
                    self.assertEqual(0, fixture["exitCode"])
                independent = report["fixtures"][3]
                self.assertEqual("passed", independent["status"])
                self.assertEqual("independent-lane", independent["evidence"])
                self.assertEqual([1, 2], independent["queries"][0]["apiNanos"]["raw"])
                self.assertNotIn(["clone-corpus"], MalformedFixture.instances[-1].calls)

    def test_large_valid_fixture_checkpoints_keep_bounded_failure_evidence(self):
        class LargeFixture(FakeJob):
            def run(self, argv, cwd, timeout=120):
                result = super().run(argv, cwd, timeout)
                output = Path(argv[-1])
                if output.name.startswith("fixture-") and output.suffix == ".json":
                    output.write_text(json.dumps({"status": "passed", "queries": [{"apiNanos": [1, 2]}],
                                                  "extra": "x" * (5 << 20)}))
                return result
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "driver.zip"
            artifact.write_bytes(b"fake artifact")
            report_path = root / "report.json"
            argv = ["run.py", "--execute", "--corpus", "spectre", "--artifact", str(artifact),
                    "--sha256", hashlib.sha256(b"fake artifact").hexdigest(),
                    "--scratch", str(root), "--cgroup-parent", str(root), "--report", str(report_path)]
            with patch.object(sys, "argv", argv), patch.object(run, "readiness", return_value=[]), \
                    patch.object(run, "Commands", LargeFixture), patch.object(run, "clone", fake_clone), \
                    patch.object(run, "extract_driver"):
                self.assertEqual(2, run.main())
            report = json.loads(report_path.read_text())
            self.assertLessEqual(report_path.stat().st_size, 16 << 20)
            self.assertEqual("failed", report["status"])
            self.assertTrue(report["cleanupVerified"])
            self.assertEqual(["manual", "watcher", "calls-manual", "calls-watcher"],
                             [f["lane"] for f in report["fixtures"]])
            for fixture in report["fixtures"]:
                self.assertEqual("failed", fixture["status"])
                self.assertEqual(0, fixture["exitCode"])
                self.assertGreater(fixture["checkpointBytes"], 5 << 20)
                self.assertRegex(fixture["checkpointSha256"], r"^[0-9a-f]{64}$")
                self.assertEqual([], fixture["queries"])
            self.assertNotIn(["clone-corpus"], LargeFixture.instances[-1].calls)

    def test_aggregate_report_overflow_keeps_lane_statuses_under_upload_limit(self):
        with tempfile.TemporaryDirectory() as temporary:
            report_path = Path(temporary) / "report.json"
            argv = ["run.py", "--execute", "--corpus", "spectre", "--artifact", "driver.zip",
                    "--sha256", "digest", "--report", str(report_path)]
            def oversized_report(_, report):
                report["fixtures"] = [{"lane": "manual", "status": "failed", "exitCode": 7,
                                       "queries": [], "diagnostics": "x" * (17 << 20)}]
                report["cleanupVerified"] = True
            with patch.object(sys, "argv", argv), patch.object(run, "readiness", return_value=[]), \
                    patch.object(run, "execute", side_effect=oversized_report):
                self.assertEqual(2, run.main())
            report = json.loads(report_path.read_text())
            self.assertLessEqual(report_path.stat().st_size, 16 << 20)
            self.assertEqual("failed", report["status"])
            self.assertTrue(report["cleanupVerified"])
            self.assertEqual("manual", report["fixtures"][0]["lane"])
            self.assertEqual(7, report["fixtures"][0]["exitCode"])
            self.assertNotIn("diagnostics", report["fixtures"][0])

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
        for call in FakeJob.instances[-1].calls:
            if "dev.sebastiano.indexino.acceptance.PublicAcceptanceDriver" in call:
                self.assertIn("-Xmx6g", call)

    def test_bazel_heap_override_is_shared_by_inventory_wrapper_and_shutdown(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            workspace = root / "corpus"
            workspace.mkdir()
            (workspace / ".bazelversion").write_text("pinned-bazel\n")
            commands = FakeJob(root, root)
            commands.env = {"PATH": "/bin"}
            with patch.object(run, "verify_artifact"), patch.object(Path, "chmod"):
                startup = run.bazel_setup(commands, workspace, root, {"bazel": "pinned-bazel"})
            self.assertIn("--host_jvm_args=-Xmx6g", startup)
            self.assertIn("--host_jvm_args=-Xmx6g", (root / "tools/bazel").read_text())
            self.assertEqual(startup + ["shutdown"], commands.bazel_shutdown[0])

    def test_inventory_excludes_tests_but_keeps_all_three_independent_roots(self):
        with tempfile.TemporaryDirectory() as temporary:
            workspace = Path(temporary)
            fake_clone(None, None, workspace)
            (workspace / "core/build.gradle.kts").write_text("plugins {}")
            (workspace / "input-coordinator/build.gradle.kts").write_text("plugins {}")
            decoy = workspace / "core/src/test/kotlin/Decoy.kt"
            decoy.parent.mkdir(parents=True)
            decoy.write_text("class Decoy")
            self.assertEqual(["core/build.gradle.kts", "core/src/main/kotlin/Invented.kt"],
                             run.gradle_inventory(workspace, ["core/src"]))


if __name__ == "__main__":
    unittest.main()
