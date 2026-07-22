#!/usr/bin/env bash

set -euo pipefail

fail() {
  echo "macOS release signing: $*" >&2
  exit 1
}

require_environment() {
  local name="$1"
  [[ -n "${!name:-}" ]] || fail "missing environment variable $name"
}

[[ "$#" == 2 ]] || fail "usage: $0 <unsigned-zip> <signed-zip>"
readonly INPUT_ARCHIVE="$1"
readonly OUTPUT_ARCHIVE="$2"
[[ -f "$INPUT_ARCHIVE" ]] || fail "missing input archive $INPUT_ARCHIVE"
[[ "$INPUT_ARCHIVE" != "$OUTPUT_ARCHIVE" ]] || fail "input and output archives must differ"

require_environment MACOS_CERTIFICATE_P12
require_environment MACOS_CERTIFICATE_PASSWORD
require_environment MACOS_SIGNING_IDENTITY
require_environment APPLE_ID
require_environment APPLE_APP_SPECIFIC_PASSWORD
require_environment APPLE_TEAM_ID

readonly WORK_DIRECTORY="$(mktemp -d)"
readonly KEYCHAIN="$WORK_DIRECTORY/indexino-signing.keychain-db"
readonly KEYCHAIN_PASSWORD="$(openssl rand -hex 24)"

cleanup() {
  security delete-keychain "$KEYCHAIN" >/dev/null 2>&1 || true
  rm -rf "$WORK_DIRECTORY"
}
trap cleanup EXIT

printf '%s' "$MACOS_CERTIFICATE_P12" | \
  openssl base64 -d -A -out "$WORK_DIRECTORY/certificate.p12"
security create-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN"
security set-keychain-settings -lut 21600 "$KEYCHAIN"
security unlock-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN"
security import "$WORK_DIRECTORY/certificate.p12" \
  -k "$KEYCHAIN" \
  -P "$MACOS_CERTIFICATE_PASSWORD" \
  -T /usr/bin/codesign
security set-key-partition-list \
  -S apple-tool:,apple:,codesign: \
  -s \
  -k "$KEYCHAIN_PASSWORD" \
  "$KEYCHAIN"

readonly EXTRACTED_DIRECTORY="$WORK_DIRECTORY/extracted"
mkdir -p "$EXTRACTED_DIRECTORY"
/usr/bin/ditto -x -k "$INPUT_ARCHIVE" "$EXTRACTED_DIRECTORY"
readonly PAYLOAD_DIRECTORY="$EXTRACTED_DIRECTORY/indexino"
[[ -d "$PAYLOAD_DIRECTORY" ]] || fail "archive does not contain the indexino payload"
readonly LAUNCHER="$PAYLOAD_DIRECTORY/indexino"
[[ -f "$LAUNCHER" ]] || fail "archive does not contain the Indexino launcher"

find "$PAYLOAD_DIRECTORY" -type f -print0 | while IFS= read -r -d '' candidate; do
  if [[ "$candidate" != "$LAUNCHER" ]] && /usr/bin/file "$candidate" | grep -q 'Mach-O'; then
    preserve_metadata=()
    if /usr/bin/codesign --display "$candidate" >/dev/null 2>&1; then
      preserve_metadata+=(
        --preserve-metadata=identifier,entitlements,requirements,flags,runtime
      )
    fi
    /usr/bin/codesign \
      --force \
      --options runtime \
      --timestamp \
      --keychain "$KEYCHAIN" \
      "${preserve_metadata[@]}" \
      --sign "$MACOS_SIGNING_IDENTITY" \
      "$candidate"
  fi
done

/usr/bin/codesign \
  --force \
  --options runtime \
  --timestamp \
  --keychain "$KEYCHAIN" \
  --entitlements .github/macos/indexino.entitlements \
  --sign "$MACOS_SIGNING_IDENTITY" \
  "$LAUNCHER"

find "$PAYLOAD_DIRECTORY" -type f -print0 | while IFS= read -r -d '' candidate; do
  if /usr/bin/file "$candidate" | grep -q 'Mach-O'; then
    /usr/bin/codesign --verify --strict --verbose=2 "$candidate"
  fi
done

mkdir -p "$(dirname "$OUTPUT_ARCHIVE")"
readonly TEMPORARY_ARCHIVE="$OUTPUT_ARCHIVE.partial"
rm -f "$TEMPORARY_ARCHIVE"
/usr/bin/ditto -c -k --keepParent "$PAYLOAD_DIRECTORY" "$TEMPORARY_ARCHIVE"
mv "$TEMPORARY_ARCHIVE" "$OUTPUT_ARCHIVE"

xcrun notarytool submit "$OUTPUT_ARCHIVE" \
  --apple-id "$APPLE_ID" \
  --password "$APPLE_APP_SPECIFIC_PASSWORD" \
  --team-id "$APPLE_TEAM_ID" \
  --wait

readonly GATEKEEPER_DIRECTORY="$WORK_DIRECTORY/gatekeeper"
mkdir -p "$GATEKEEPER_DIRECTORY"
/usr/bin/ditto -x -k "$OUTPUT_ARCHIVE" "$GATEKEEPER_DIRECTORY"
xattr -r -w com.apple.quarantine "0081;$(printf '%x' "$(date +%s)");Indexino CI;" \
  "$GATEKEEPER_DIRECTORY/indexino"

gatekeeper_accepted="false"
for _ in 1 2 3 4 5; do
  if /usr/sbin/spctl --assess --type execute --verbose=4 \
    "$GATEKEEPER_DIRECTORY/indexino/indexino"; then
    gatekeeper_accepted="true"
    break
  fi
  sleep 5
done
[[ "$gatekeeper_accepted" == "true" ]] || fail "Gatekeeper did not accept the notarized launcher"

if xcrun stapler validate "$OUTPUT_ARCHIVE" >/dev/null 2>&1; then
  fail "notarized ZIP unexpectedly reports a stapled ticket"
fi
echo "macOS release signing: online Gatekeeper accepted; ZIP correctly has no stapled ticket"
