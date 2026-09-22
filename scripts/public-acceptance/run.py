# SPDX-License-Identifier: UEL-1.0
"""Manual public-corpus acceptance. Python 3.11+, JDK 25, Linux cgroup v2."""
import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import time
import zipfile

from harness import bazel_inventory, compare_inventory, phase_metrics, readiness, samples, verify_artifact
from runner import Commands, clone

HERE = Path(__file__).resolve().parent


def available_memory():
    if platform.system() == "Linux":
        rows = dict(line.split(":", 1) for line in Path("/proc/meminfo").read_text().splitlines())
        return int(rows["MemAvailable"].split()[0]) * 1024
    if platform.system() == "Darwin":
        # Physical capacity is NOT available-memory evidence; report separately.
        return None
    return None


def gradle_inventory(workspace, roots):
    workspace = workspace.resolve()
    result = []
    for root in roots:
        source_root = workspace / root
        if not source_root.is_dir():
            raise ValueError(f"missing independently specified module source root: {root}")
        for path in source_root.rglob("*"):
            relative = path.relative_to(source_root)
            if len(relative.parts) < 3 or "test" in relative.parts[0].lower():
                continue
            kind = relative.parts[1]
            if (kind in ("kotlin", "java") and path.suffix in (".kt", ".java") or
                    kind in ("res", "composeResources") and path.suffix):
                if path.is_file():
                    if path.is_symlink() or not path.resolve().is_relative_to(workspace):
                        raise ValueError("source escapes disposable corpus")
                    result.append(path.relative_to(workspace).as_posix())
    return sorted(result)


def bazel_setup(commands, workspace, root, corpus):
    pin = json.loads((HERE / "tools.json").read_text())[f"linux-{platform.machine()}"]
    tools = root / "tools"
    tools.mkdir()
    binary = tools / "bazelisk"
    commands.run(["curl", "--fail", "--location", "--proto", "=https", "--max-time", "300",
                  "--output", str(binary), pin["bazeliskUrl"]], root, timeout=320)
    verify_artifact(binary, pin["bazeliskSha256"])
    binary.chmod(0o700)
    commands.env.update(USE_BAZEL_VERSION=corpus["bazel"], BAZELISK_VERIFY_SHA256=pin["bazelSha256"],
                        BAZELISK_SKIP_WRAPPER="1")
    if (workspace / ".bazelversion").read_text().strip() != corpus["bazel"]:
        raise ValueError("public corpus Bazel version changed")
    # Preserve the pinned workspace rc but exclude host rc/config. Its sibling
    # try-import is inside this job's disposable root, never a host checkout.
    startup = [str(binary), f"--output_user_root={root / 'bazel-output'}", "--nosystem_rc", "--nohome_rc"]
    rc = root / "private.bazelrc"
    rc.write_text(f"common --repository_cache={root / 'bazel-repository-cache'}\n")
    startup.append(f"--bazelrc={rc}")
    wrapper = tools / "bazel"
    import shlex
    wrapper.write_text("#!/bin/sh\nexec " + shlex.join(startup) + ' "$@"\n')
    wrapper.chmod(0o700)
    commands.env["PATH"] = str(tools) + os.pathsep + commands.env["PATH"]
    commands.bazel_shutdown = (startup + ["shutdown"], workspace)
    return startup


def extract_driver(artifact, root):
    with zipfile.ZipFile(artifact) as archive:
        for entry in archive.infolist():
            path = Path(entry.filename)
            if path.is_absolute() or ".." in path.parts or entry.file_size > 512 << 20:
                raise ValueError("unsafe driver archive")
        archive.extractall(root)


@contextmanager
def tracked_commands(parent, root, report):
    commands = Commands(parent, root)
    try:
        with commands:
            yield commands
    finally:
        report["cleanupVerified"] = commands.cleanup_verified
        report["commands"] = commands.metrics
        report["finalDisposableLogicalBytes"] = sum(
            path.lstat().st_size for path in root.rglob("*") if not path.is_symlink() and path.is_file())


def execute(args, report):
    corpus = json.loads((HERE / "corpora.json").read_text())[args.corpus]
    report["corpus"] = corpus
    report["artifactSha256"] = verify_artifact(args.artifact, args.sha256)
    with tempfile.TemporaryDirectory(prefix="indexino-public-", dir=args.scratch) as temporary:
        root = Path(temporary)
        with tracked_commands(args.cgroup_parent, root, report) as commands:
            bootstrap = time.monotonic_ns()
            workspace = root / "corpus"
            driver = root / "driver"
            extract_driver(args.artifact, driver)
            report["java"] = commands.run(["java", "--version"], root)
            report["bootstrapWallNanos"] = time.monotonic_ns() - bootstrap
            report["scopes"] = []
            classpath = os.pathsep.join(str(driver / p) for p in ("test", "main", "lib/*"))
            java_isolation = [f"-Duser.home={root / 'home'}", f"-Djava.io.tmpdir={root / 'tmp'}"]
            report["fixtures"] = []
            for lane in ("manual", "watcher", "calls-manual", "calls-watcher"):
                output = root / f"fixture-{lane}.json"
                exit_code = 0
                caller_lane = lane.startswith("calls-")
                entry = ("CallLifecycleAcceptanceDriver" if caller_lane else "RetrievalAcceptanceDriver")
                inputs = ([] if caller_lane else [str(driver / "test/fixtures/retrieval-v1")])
                inputs += [str(root / f"fixture-{lane}"), lane.removeprefix("calls-"), str(output)]
                try:
                    commands.run(["java", "-Xmx2g"] + java_isolation + ["--enable-native-access=ALL-UNNAMED", "-cp", classpath,
                                  "dev.sebastiano.indexino.acceptance." + entry] + inputs, root, timeout=600)
                except subprocess.CalledProcessError as error:
                    exit_code = error.returncode
                fixture_result = (json.loads(output.read_text()) if output.exists() else
                                  {"status": "failed", "queries": [], "reason": "driver produced no checkpoint"})
                fixture_result.update(lane=lane, exitCode=exit_code)
                for query in fixture_result["queries"]:
                    query["apiNanos"] = (samples(query["apiNanos"]) if query["apiNanos"] else
                                         {"raw": [], "p50": None, "p95": None, "reason": "query did not complete"})
                report["fixtures"].append(fixture_result)
            if any(f["exitCode"] or f["status"] != "passed" for f in report["fixtures"]):
                raise AssertionError("invented fixture acceptance failed; retained all lane reports")
            acquisition = time.monotonic_ns()
            clone(commands, corpus, workspace)
            startup = bazel_setup(commands, workspace, root, corpus) if args.corpus == "intellij" else None
            report["corpusBootstrapWallNanos"] = time.monotonic_ns() - acquisition
            for include_deps in corpus["includeDependencies"]:
                scope_report = {"includeDependencies": include_deps, "repeats": []}
                enumeration = time.monotonic_ns()
                if startup:
                    # label_kind independently distinguishes generated outputs.
                    raw = commands.run(startup + ["query", "kind('source file|generated file', deps(//:main))",
                                       "--output=label_kind"], workspace, timeout=7200)
                    expected, excluded = bazel_inventory(raw.splitlines())
                    scope_report["exclusions"] = excluded
                else:
                    roots = corpus["sourceRoots"]["dependencies" if include_deps else "target"]
                    expected = gradle_inventory(workspace, roots)
                scope_report["enumerationWallNanos"] = time.monotonic_ns() - enumeration
                scope_report["inventorySha256"] = compare_inventory(expected, expected)
                contract = root / "contract.json"
                contract.write_text(json.dumps({"sources": expected, "symbols": corpus["symbols"]}))
                for repeat in range(4):
                    output = root / "driver-report.json"
                    instrumented = repeat == 3
                    commands.run(["java", "-Xmx12g"] + java_isolation + ["--enable-native-access=ALL-UNNAMED", "-cp", classpath,
                                  "dev.sebastiano.indexino.acceptance.PublicAcceptanceDriver", str(workspace),
                                  str(root / f"index-{include_deps}-{repeat}"), corpus["buildSystem"],
                                  corpus["target"], str(include_deps).lower(), str(contract), str(output)] +
                                 (["instrumented"] if instrumented else []),
                                 root, timeout=7200)
                    result = json.loads(output.read_text())
                    compare_inventory(expected, result["sources"])
                    result["warmRefreshApiNanos"] = samples(result["warmRefreshApiNanos"])
                    result["queryApiNanos"] = {k: samples(v) for k, v in result["queryApiNanos"].items()}
                    if instrumented:
                        result["phaseMetrics"] = phase_metrics(result["diagnostics"]["phaseEvents"])
                        if result["generation"] != scope_report["repeats"][0]["generation"]:
                            raise AssertionError("instrumented/public generation mismatch")
                        scope_report["instrumentedDiagnostic"] = result
                    else:
                        scope_report["repeats"].append(result)
                scope_report["coldApiNanos"] = samples([r["coldApiNanos"] for r in scope_report["repeats"]])
                report["scopes"].append(scope_report)
    report["status"] = "passed"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", choices=("spectre", "intellij"), required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--scratch", type=Path, default=Path(tempfile.gettempdir()))
    parser.add_argument("--cgroup-parent", type=Path)
    parser.add_argument("--artifact", type=Path)
    parser.add_argument("--sha256")
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    memory = available_memory()
    disk = shutil.disk_usage(args.scratch).free
    reasons = readiness(platform.system(), memory, disk, args.cgroup_parent)
    report = {"schema": 1, "status": "not-ready", "reasons": reasons,
              "machine": {"os": platform.system(), "release": platform.release(), "arch": platform.machine(),
                          "cpus": os.cpu_count(), "availableMemoryBytes": memory, "freeDiskBytes": disk},
              "cache": {"indexino": "cold per repeat", "osPageCache": "uncontrolled"},
              "performanceGate": "report-only", "cleanupVerified": None,
              "metrics": {"cpu": None, "rss": None, "diskPeak": None},
              "unsupportedMetrics": "per-phase CPU/RSS/disk peak collection not implemented"}
    try:
        if not reasons and args.execute:
            if not args.artifact or not args.sha256:
                raise ValueError("execute requires checksummed driver artifact")
            execute(args, report)
        elif not reasons:
            report["status"] = "preflight-only"
    except (Exception, KeyboardInterrupt) as error:
        report["status"] = "failed"
        report["reasons"].append(f"{type(error).__name__}: {error}")
    finally:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
    return 0 if report["status"] == "passed" else 2


if __name__ == "__main__":
    raise SystemExit(main())
