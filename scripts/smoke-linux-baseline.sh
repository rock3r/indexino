#!/usr/bin/env bash

set -euo pipefail

if [[ "$#" != 2 ]]; then
  echo "usage: $0 <native-zip> <container-image>" >&2
  exit 2
fi

readonly ARCHIVE="$1"
readonly CONTAINER_IMAGE="$2"
[[ -f "$ARCHIVE" ]] || {
  echo "native archive does not exist: $ARCHIVE" >&2
  exit 1
}

readonly PROJECT_DIRECTORY="$(pwd)"
readonly ARCHIVE_DIRECTORY="$(cd "$(dirname "$ARCHIVE")" && pwd)"
readonly ARCHIVE_NAME="$(basename "$ARCHIVE")"

docker run --rm \
  --dns 8.8.8.8 \
  --dns 1.1.1.1 \
  --volume "$PROJECT_DIRECTORY:/workspace:ro" \
  --volume "$ARCHIVE_DIRECTORY:/native-archive:ro" \
  "$CONTAINER_IMAGE" \
  bash -euxo pipefail -c '
    export DEBIAN_FRONTEND=noninteractive
    updated=0
    for attempt in 1 2 3; do
      if apt-get update; then
        updated=1
        break
      fi
      sleep $((attempt * 5))
    done
    test "$updated" -eq 1
    apt-get install --yes --no-install-recommends git unzip
    mkdir -p /tmp/indexino-install /tmp/indexino-project /tmp/indexino-caller
    unzip -q "/native-archive/'"$ARCHIVE_NAME"'" -d /tmp/indexino-install
    cp -R /workspace/gradle/aot-training/fixture/. /tmp/indexino-project/
    git -C /tmp/indexino-project init -q
    git -C /tmp/indexino-project config user.name "Indexino CI"
    git -C /tmp/indexino-project config user.email "ci@localhost"
    git -C /tmp/indexino-project add .
    git -C /tmp/indexino-project commit -qm baseline
    cd /tmp/indexino-caller
    /tmp/indexino-install/indexino/indexino index \
      --project /tmp/indexino-project \
      --build-system gradle \
      --gradle-module :app \
      --applications dev.sebastiano.selection-context
    /tmp/indexino-install/indexino/indexino query \
      --project /tmp/indexino-project \
      --application dev.sebastiano.selection-context \
      --preset interactive-in-selection \
      --format jsonl > /tmp/query.jsonl
    test -s /tmp/query.jsonl
  '
