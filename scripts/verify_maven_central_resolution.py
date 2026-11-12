#!/usr/bin/env python3
"""Wait until every expected release POM is publicly readable on Maven Central.

This is not a Central Portal status check. Portal VALIDATED and PUBLISHED states
do not prove that repo1.maven.org serves the coordinates.
"""

from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request

GROUP = "dev.sebastiano.indexino"
ARTIFACTS = (
    "indexino",
    "indexino-bom",
    "indexino-model",
    "indexino-plugin-api",
    "indexino-selection-context",
    "indexino-compose-decoration",
    "indexino-script-host",
)


def artifact_url(repository: str, artifact: str, version: str) -> str:
    group_path = GROUP.replace(".", "/")
    return f"{repository.rstrip('/')}/{group_path}/{artifact}/{version}/{artifact}-{version}.pom"


def resolve(repository: str, version: str, opener) -> dict[str, int]:
    statuses = {}
    for artifact in ARTIFACTS:
        request = urllib.request.Request(artifact_url(repository, artifact, version), method="GET")
        try:
            with opener.open(request, timeout=30) as response:
                statuses[artifact] = response.status
        except urllib.error.HTTPError as error:
            statuses[artifact] = error.code
    return statuses


def wait_for_resolution(repository: str, version: str, timeout_seconds: int, interval_seconds: int, clock, sleeper, opener) -> dict[str, int]:
    deadline = clock() + timeout_seconds
    last = {}
    while True:
        last = resolve(repository, version, opener)
        if all(status == 200 for status in last.values()):
            return last
        if clock() >= deadline:
            missing = [artifact for artifact, status in last.items() if status != 200]
            raise TimeoutError(
                f"Maven Central did not resolve {', '.join(missing)} at {version} within {timeout_seconds}s"
            )
        sleeper(interval_seconds)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--repository", default="https://repo1.maven.org/maven2")
    parser.add_argument("--timeout-seconds", type=int, default=1800)
    parser.add_argument("--interval-seconds", type=int, default=30)
    args = parser.parse_args()
    if args.timeout_seconds < 0 or args.interval_seconds < 0:
        raise SystemExit("timeouts must be non-negative")
    try:
        statuses = wait_for_resolution(
            args.repository,
            args.version,
            args.timeout_seconds,
            args.interval_seconds,
            time.monotonic,
            time.sleep,
            urllib.request.build_opener(),
        )
    except TimeoutError as error:
        print(error)
        return 1
    print(json.dumps({"version": args.version, "resolved": statuses}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
