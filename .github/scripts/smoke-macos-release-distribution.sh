#!/usr/bin/env bash

set -euo pipefail

fail() {
  echo "macOS release smoke: $*" >&2
  exit 1
}

[[ "$#" == 1 ]] || fail "usage: $0 <signed-macos-arm64.zip>"
readonly ARCHIVE="$1"
[[ -f "$ARCHIVE" ]] || fail "missing archive $ARCHIVE"

readonly WORK_DIRECTORY="$(mktemp -d)"
cleanup() {
  rm -rf "$WORK_DIRECTORY"
}
trap cleanup EXIT

/usr/bin/ditto -x -k "$ARCHIVE" "$WORK_DIRECTORY"
readonly PAYLOAD_DIRECTORY="$WORK_DIRECTORY/indexino"
[[ -d "$PAYLOAD_DIRECTORY" ]] || fail "archive does not contain the indexino payload"
readonly LAUNCHER="$PAYLOAD_DIRECTORY/indexino"
[[ -x "$LAUNCHER" ]] || fail "missing executable launcher"

/usr/bin/codesign --verify --strict --verbose=2 "$LAUNCHER"
help_output="$(
  HOME="$WORK_DIRECTORY/home" \
    "$LAUNCHER" --help 2>&1
)" || fail "launcher --help failed"
[[ -n "$help_output" ]] || fail "launcher --help produced no output"
echo "macOS release smoke: signed launcher verified and --help succeeded"
