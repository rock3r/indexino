# SPDX-License-Identifier: UEL-1.0
"""Public corpus acceptance orchestration; expensive execution is opt-in."""

import hashlib
import math
from pathlib import Path, PurePosixPath


def compare_inventory(expected, actual):
    if len(set(expected)) != len(expected) or len(set(actual)) != len(actual):
        raise AssertionError("duplicate inventory entries")
    missing, extra = sorted(set(expected) - set(actual)), sorted(set(actual) - set(expected))
    if missing or extra:
        raise AssertionError(f"source coverage mismatch: missing={missing}; extra={extra}")
    return hashlib.sha256(("\n".join(sorted(expected)) + "\n").encode()).hexdigest()


def bazel_inventory(rows):
    paths = []
    excluded = {"external": 0, "generated": 0, "unsupported": 0}
    for row in rows:
        kind, label = row.rsplit(" ", 1)
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
            if PurePosixPath(path).suffix not in (".kt", ".java", ".xml"):
                excluded["unsupported"] += 1
            else:
                paths.append(path)
    return sorted(set(paths)), excluded


def samples(values):
    if not values or any(not math.isfinite(x) or x < 0 for x in values):
        raise ValueError("samples must be finite, nonnegative and nonempty")
    ordered = sorted(values)
    return {"raw": values, "p50": ordered[math.ceil(len(values) * .50) - 1],
            "p95": ordered[math.ceil(len(values) * .95) - 1], "method": "nearest-rank"}


def readiness(system, memory, disk, cgroup):
    reasons = []
    if memory is None:
        reasons.append("memory: available-memory measurement unsupported on this host (not a capacity failure)")
    elif memory < 32 << 30:
        reasons.append("memory: requires 32 GiB available; public Bazel rc alone requests 12 GiB heap")
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
