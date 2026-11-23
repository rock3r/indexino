#!/usr/bin/env python3
"""Wait until every expected release POM is publicly readable on Maven Central.

This is not a Central Portal status check. Portal VALIDATED and PUBLISHED states
do not prove that repo1.maven.org serves the coordinates.
"""

from __future__ import annotations

import argparse
import http.client
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


# 404 means the coordinate is not public yet. 408, 429 and 5xx are transient
# server answers. Anything else (401, 403, 410, ...) is a real error that more
# polling will not fix, so the gate fails fast instead of burning its window.
PENDING_STATUS = 404
TRANSIENT_STATUSES = frozenset({408, 429})
# Transport failures (DNS, reset, socket timeout) have no HTTP status.
TRANSPORT_FAILURE = 0
MAX_BACKOFF_SECONDS = 300


class ResolutionError(Exception):
    """A probe failed in a way that retrying cannot fix."""


class ResolutionTimeout(Exception):
    """The deadline passed before every coordinate resolved."""


def is_transient(status: int) -> bool:
    return status == TRANSPORT_FAILURE or status in TRANSIENT_STATUSES or 500 <= status <= 599


def resolve(repository: str, version: str, opener) -> dict[str, int]:
    statuses = {}
    for artifact in ARTIFACTS:
        url = artifact_url(repository, artifact, version)
        request = urllib.request.Request(url, method="GET")
        try:
            with opener.open(request, timeout=30) as response:
                statuses[artifact] = response.status
        except urllib.error.HTTPError as error:
            if error.code != PENDING_STATUS and not is_transient(error.code):
                raise ResolutionError(f"{url} returned HTTP {error.code}") from error
            statuses[artifact] = error.code
        except (urllib.error.URLError, http.client.HTTPException, ConnectionError, TimeoutError) as error:
            print(f"Transient failure probing {url}: {error}")
            statuses[artifact] = TRANSPORT_FAILURE
    return statuses


def next_delay(interval_seconds: int, transient_streak: int) -> int:
    if transient_streak == 0:
        return interval_seconds
    return min(interval_seconds * 2**transient_streak, max(interval_seconds, MAX_BACKOFF_SECONDS))


def wait_for_resolution(repository: str, version: str, timeout_seconds: int, interval_seconds: int, clock, sleeper, opener) -> dict[str, int]:
    deadline = clock() + timeout_seconds
    transient_streak = 0
    while True:
        last = resolve(repository, version, opener)
        if all(status == 200 for status in last.values()):
            return last
        remaining = deadline - clock()
        if remaining <= 0:
            missing = [f"{artifact} ({status or 'network error'})" for artifact, status in last.items() if status != 200]
            raise ResolutionTimeout(
                f"Maven Central did not resolve {', '.join(missing)} at {version} within {timeout_seconds}s"
            )
        transient_streak = transient_streak + 1 if any(is_transient(status) for status in last.values()) else 0
        sleeper(min(next_delay(interval_seconds, transient_streak), remaining))


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
    except (ResolutionError, ResolutionTimeout) as error:
        print(error)
        return 1
    print(json.dumps({"version": args.version, "resolved": statuses}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
