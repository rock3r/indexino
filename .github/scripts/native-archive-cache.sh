#!/usr/bin/env bash

set -euo pipefail

readonly PINS_FILE="gradle/native-distributions.properties"
readonly SERVER_PORT="18080"

fail() {
  echo "native archive cache: $*" >&2
  exit 1
}

property() {
  local key="$1"
  local value
  value="$(awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$PINS_FILE")"
  [[ -n "$value" ]] || fail "missing property $key"
  printf '%s' "$value"
}

target_prefix() {
  case "$1" in
    linux-x64) printf 'linuxX64' ;;
    macos-arm64) printf 'macArm64' ;;
    windows-x64) printf 'windowsX64' ;;
    *) fail "unsupported target $1" ;;
  esac
}

target_environment_prefix() {
  case "$1" in
    linux-x64) printf 'LINUX_X64' ;;
    macos-arm64) printf 'MACOS_ARM64' ;;
    windows-x64) printf 'WINDOWS_X64' ;;
    *) fail "unsupported target $1" ;;
  esac
}

digest() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{ print $1 }'
  else
    shasum -a 256 "$1" | awk '{ print $1 }'
  fi
}

verify_archive() {
  local archive="$1"
  local expected="$2"
  local actual
  actual="$(digest "$archive")"
  [[ "$actual" == "$expected" ]] ||
    fail "SHA-256 mismatch for $archive: expected $expected, got $actual"
}

download_verified() {
  local url="$1"
  local expected="$2"
  local destination="$3"

  if [[ -f "$destination" ]]; then
    verify_archive "$destination" "$expected"
    return
  fi

  local partial="${destination}.part"
  rm -f "$partial"
  curl --fail --location --retry 3 --output "$partial" "$url"
  verify_archive "$partial" "$expected"
  mv "$partial" "$destination"
}

emit_pins() {
  local target="$1"
  local prefix
  prefix="$(target_prefix "$target")"
  local jdk_sha roast_sha
  jdk_sha="$(property "$prefix.jdkSha256")"
  roast_sha="$(property "$prefix.roastSha256")"
  [[ "$jdk_sha" =~ ^[0-9a-f]{64}$ ]] || fail "invalid JBR digest for $target"
  [[ "$roast_sha" =~ ^[0-9a-f]{64}$ ]] || fail "invalid Roast digest for $target"
  printf 'jdk_sha=%s\n' "$jdk_sha"
  printf 'roast_sha=%s\n' "$roast_sha"
  printf 'cache_key=%s-%s\n' "$jdk_sha" "$roast_sha"
  printf 'cache_path=.native-download-cache/%s\n' "$target"
}

run_with_cache() {
  local target="$1"
  local cache_root="$2"
  shift 2
  [[ "${1:-}" == "--" ]] || fail "expected -- before command"
  shift
  [[ "$#" -gt 0 ]] || fail "missing command"

  local prefix
  prefix="$(target_prefix "$target")"
  local cache_directory="$cache_root/$target"
  mkdir -p "$cache_directory"

  local jdk_url jdk_sha roast_base_url roast_version roast_asset roast_sha
  jdk_url="$(property "$prefix.jdkUrl")"
  jdk_sha="$(property "$prefix.jdkSha256")"
  roast_base_url="$(property "roast.baseUrl")"
  roast_version="$(property "roast.version")"
  roast_asset="$(property "$prefix.roastAsset")"
  roast_sha="$(property "$prefix.roastSha256")"
  local roast_url="${roast_base_url%/}/$roast_version/$roast_asset"

  local jdk_archive="$cache_directory/${jdk_url##*/}"
  local roast_archive="$cache_directory/$roast_asset"
  download_verified "$jdk_url" "$jdk_sha" "$jdk_archive"
  download_verified "$roast_url" "$roast_sha" "$roast_archive"

  local python_command
  if command -v python3 >/dev/null 2>&1; then
    python_command="python3"
  else
    python_command="python"
  fi
  mkdir -p build/native-cache-server
  "$python_command" -m http.server "$SERVER_PORT" --bind 127.0.0.1 \
    --directory "$cache_directory" >"build/native-cache-server/$target.log" 2>&1 &
  local server_pid=$!
  trap "kill $server_pid 2>/dev/null || true" EXIT

  local attempt ready="false"
  for attempt in {1..50}; do
    if curl --fail --silent "http://127.0.0.1:$SERVER_PORT/" >/dev/null; then
      ready="true"
      break
    fi
    sleep 0.1
  done
  [[ "$ready" == "true" ]] || fail "local archive server did not become ready"

  local environment_prefix
  environment_prefix="$(target_environment_prefix "$target")"
  env \
    "INDEXINO_NATIVE_${environment_prefix}_JDK_URL=http://127.0.0.1:$SERVER_PORT/${jdk_archive##*/}" \
    "INDEXINO_NATIVE_${environment_prefix}_ROAST_URL=http://127.0.0.1:$SERVER_PORT/${roast_archive##*/}" \
    "$@"
}

[[ -f "$PINS_FILE" ]] || fail "run from the repository root"

case "${1:-}" in
  emit-pins)
    [[ "$#" == 2 ]] || fail "usage: $0 emit-pins <target>"
    emit_pins "$2"
    ;;
  run)
    [[ "$#" -ge 5 ]] || fail "usage: $0 run <target> <cache-root> -- <command>"
    shift
    run_with_cache "$@"
    ;;
  *) fail "usage: $0 {emit-pins|run} ..." ;;
esac
