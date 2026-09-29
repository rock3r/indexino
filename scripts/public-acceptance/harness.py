# SPDX-License-Identifier: UEL-1.0
"""Public corpus acceptance orchestration; expensive execution is opt-in."""

import hashlib
import math
from pathlib import Path, PurePosixPath


JOB_MEMORY_BYTES = 16 << 30
MIN_AVAILABLE_MEMORY_BYTES = JOB_MEMORY_BYTES + (2 << 30)
JVM_HEAP = "6g"


def phase_metrics(events):
    starts, phases, discovery = {}, {}, []
    refresh_start = None
    for row in events:
        event, now = row["event"], row["observedNanoTime"]
        if event == "refresh_started":
            if refresh_start is not None:
                raise ValueError("overlapping refresh boundaries")
            refresh_start = now
        elif event == "discovery_completed":
            if refresh_start is None or now < refresh_start:
                raise ValueError("discovery lacks refresh start")
            discovery.append(now - refresh_start)
        elif event == "refresh_finished":
            if refresh_start is None or starts:
                raise ValueError("incomplete refresh or phase boundaries")
            refresh_start = None
        elif event == "phase_started":
            phase = row["phase"]
            if phase in starts:
                raise ValueError("overlapping phase boundaries")
            starts[phase] = now
        elif event == "phase_completed":
            phase = row["phase"]
            start = starts.pop(phase, None)
            if start is None or now < start:
                raise ValueError("incomplete or reversed phase boundaries")
            phases.setdefault(phase, []).append(now - start)
    if starts or refresh_start is not None:
        raise ValueError("incomplete phase or refresh boundaries")
    return {"preDiscoveryCompletedNanos": discovery, "phases": phases,
            "topologyOnlyNanos": None, "captureAndHashNanos": None,
            "unavailableReason": "discovery completion follows source capture; hash-preview is aggregate hashing only"}


def compare_inventory(expected, actual):
    if len(set(expected)) != len(expected) or len(set(actual)) != len(actual):
        raise AssertionError("duplicate inventory entries")
    missing, extra = sorted(set(expected) - set(actual)), sorted(set(actual) - set(expected))
    if missing or extra:
        raise AssertionError(f"source coverage mismatch: missing={missing}; extra={extra}")
    return hashlib.sha256(("\n".join(sorted(expected)) + "\n").encode()).hexdigest()


def bazel_inventory(rows, workspace=None):
    paths = []
    excluded = {"external": 0, "generated": 0, "unsupported": 0}
    if workspace is not None:
        excluded["directory"] = 0
    for row in rows:
        category, file_word, label = row.split(" ", 2)
        kind = f"{category} {file_word}"
        if label.startswith("@"):
            excluded["external"] += 1
        elif kind == "generated file":
            excluded["generated"] += 1
        elif kind != "source file":
            raise ValueError(f"unexpected Bazel label kind: {kind}")
        else:
            if not label.startswith("//") or label.count(":") != 1:
                raise ValueError(f"invalid local source label: {label}")
            package, name = label[2:].split(":")
            path = f"{package}/{name}" if package else name
            if any(x in ("", ".", "..") for x in path.split("/")) or "\\" in path:
                raise ValueError(f"unsafe source label: {label}")
            if workspace is not None and (workspace / path).is_dir():
                excluded["directory"] += 1
                continue
            resource = any(is_resource_directory(part)
                           for part in PurePosixPath(path).parts[:-1])
            if PurePosixPath(path).suffix not in (".kt", ".java", ".xml") and not resource:
                excluded["unsupported"] += 1
            else:
                paths.append(path)
    if workspace is not None:
        paths.extend(bazel_metadata_inventory(workspace, paths))
    return sorted(set(paths)), excluded


def is_resource_directory(name):
    return name in ("res", "resources", "composeResources") or name.endswith(
        ("_res", "-res", "_resources", "-resources"))


def bazel_metadata_inventory(workspace, sources):
    # Derive metadata candidates from the independent query, never the observed index.
    # Resource roots end in <resource-dir>/<type>/<file>; code roots use src/res or
    # the source's containing directory when no conventional module root is present.
    modules = set()
    for source in sources:
        path = PurePosixPath(source)
        parents = path.parts[:-1]
        if (len(path.parts) >= 3 and is_resource_directory(path.parts[-3]) and
                path.suffix[1:].isascii() and path.suffix[1:].isalnum()):
            module = path.parts[:-3]
            if len(module) >= 2 and module[-2] == "src":
                module = module[:-2]
        elif path.suffix in (".kt", ".java"):
            marker = "src" if "src" in parents else "res" if "res" in parents else None
            module = parents[:parents.index(marker)] if marker else parents
        else:
            continue
        modules.add(PurePosixPath(*module))
    result = []
    for module in modules:
        for metadata in ("build.gradle.kts", "build.gradle", "src/main/AndroidManifest.xml",
                         "src/androidMain/AndroidManifest.xml", "AndroidManifest.xml"):
            path = workspace / module / metadata
            if path.is_file():
                if path.is_symlink() or not path.resolve().is_relative_to(workspace.resolve()):
                    raise ValueError("metadata escapes disposable corpus")
                result.append((module / metadata).as_posix())
    return result


def samples(values):
    if not values or any(not math.isfinite(x) or x < 0 for x in values):
        raise ValueError("samples must be finite, nonnegative and nonempty")
    ordered = sorted(values)
    return {"raw": values, "p50": ordered[math.ceil(len(values) * .50) - 1],
            "p95": ordered[math.ceil(len(values) * .95) - 1], "method": "nearest-rank"}


def readiness(system, memory, disk, cgroup):
    reasons = []
    if system == "Linux" and cgroup is not None:
        try:
            for group in (Path(cgroup), *Path(cgroup).parents):
                if not (group / "memory.max").exists():
                    break
                limit = (group / "memory.max").read_text().strip()
                if limit != "max":
                    remaining = max(0, int(limit) - int((group / "memory.current").read_text()))
                    memory = min(memory, remaining) if memory is not None else None
        except (OSError, ValueError):
            reasons.append("memory: could not measure effective ancestor cgroup limit")
    if memory is None:
        reasons.append("memory: available-memory measurement unsupported on this host (not a capacity failure)")
    elif memory < MIN_AVAILABLE_MEMORY_BYTES:
        reasons.append("memory: serial profile requires 18 GiB available for a 16 GiB job plus headroom")
    if disk < 150 << 30:
        reasons.append("disk: requires 150 GiB disposable free space")
    if system != "Linux" or cgroup is None:
        reasons.append("containment: requires a delegated Linux cgroup v2 with cgroup.kill")
    elif not all((Path(cgroup) / x).exists() for x in ("cgroup.kill", "memory.max", "pids.max")):
        reasons.append("containment: missing delegated cgroup v2 controllers")
    return reasons


def verify_artifact(path, digest):
    with path.open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
    if actual != digest:
        raise ValueError(f"artifact digest mismatch: {actual} != {digest}")
    return actual
