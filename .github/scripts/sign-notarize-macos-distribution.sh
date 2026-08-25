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

codesign_macho() {
  local candidate="$1"
  local preserve_metadata=()
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
}

sign_nested_natives_in_jars() {
  local jar nest_dir entry
  # Apple notarization inspects Mach-O payloads inside JARs (JNA, Jansi, …).
  while IFS= read -r -d '' jar; do
    while IFS= read -r entry; do
      [[ -n "$entry" ]] || continue
      nest_dir="$(mktemp -d "$WORK_DIRECTORY/jar-native.XXXXXX")"
      mkdir -p "$nest_dir/$(dirname "$entry")"
      unzip -p "$jar" "$entry" > "$nest_dir/$entry"
      codesign_macho "$nest_dir/$entry"
      (
        cd "$nest_dir"
        zip -q -u "$jar" "$entry"
      )
      rm -rf "$nest_dir"
    done < <(zipinfo -1 "$jar" | grep -E '\.(jnilib|dylib)$' || true)
  done < <(find "$PAYLOAD_DIRECTORY" -type f -name '*.jar' -print0)
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
  -A \
  -t cert \
  -f pkcs12
# Temporary keychains are not on the default search list; codesign and
# set-key-partition-list need the keychain selected explicitly (Spectre pattern).
security list-keychains -d user -s "$KEYCHAIN"
security default-keychain -d user -s "$KEYCHAIN"
security set-key-partition-list \
  -S apple-tool:,apple:,codesign: \
  -s \
  -k "$KEYCHAIN_PASSWORD" \
  "$KEYCHAIN"
if ! security find-identity -v -p codesigning "$KEYCHAIN" | grep -F "$MACOS_SIGNING_IDENTITY"; then
  fail "signing identity not found in keychain: $MACOS_SIGNING_IDENTITY"
fi

readonly EXTRACTED_DIRECTORY="$WORK_DIRECTORY/extracted"
mkdir -p "$EXTRACTED_DIRECTORY"
/usr/bin/ditto -x -k "$INPUT_ARCHIVE" "$EXTRACTED_DIRECTORY"
readonly PAYLOAD_DIRECTORY="$EXTRACTED_DIRECTORY/indexino"
[[ -d "$PAYLOAD_DIRECTORY" ]] || fail "archive does not contain the indexino payload"
readonly LAUNCHER="$PAYLOAD_DIRECTORY/indexino"
[[ -f "$LAUNCHER" ]] || fail "archive does not contain the Indexino launcher"

sign_nested_natives_in_jars

find "$PAYLOAD_DIRECTORY" -type f -print0 | while IFS= read -r -d '' candidate; do
  if [[ "$candidate" != "$LAUNCHER" ]] && /usr/bin/file "$candidate" | grep -q 'Mach-O'; then
    codesign_macho "$candidate"
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

readonly SUBMISSION_JSON="$WORK_DIRECTORY/notary-submission.json"
xcrun notarytool submit "$OUTPUT_ARCHIVE" \
  --apple-id "$APPLE_ID" \
  --password "$APPLE_APP_SPECIFIC_PASSWORD" \
  --team-id "$APPLE_TEAM_ID" \
  --wait \
  --output-format json > "$SUBMISSION_JSON"
readonly NOTARY_STATUS="$(
  /usr/bin/python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["status"])' \
    "$SUBMISSION_JSON"
)"
readonly NOTARY_ID="$(
  /usr/bin/python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' \
    "$SUBMISSION_JSON"
)"
if [[ "$NOTARY_STATUS" != "Accepted" ]]; then
  xcrun notarytool log "$NOTARY_ID" \
    --apple-id "$APPLE_ID" \
    --password "$APPLE_APP_SPECIFIC_PASSWORD" \
    --team-id "$APPLE_TEAM_ID" >&2 || true
  fail "notarization status is $NOTARY_STATUS (expected Accepted); id=$NOTARY_ID"
fi

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
