#!/usr/bin/env bash

set -euo pipefail

fail() {
  echo "native release readiness: $*" >&2
  exit 1
}

[[ -n "${NATIVE_RELEASE_APPROVED:-}" ]] || fail "repository approval variable is missing"
[[ "$NATIVE_RELEASE_APPROVED" == "true" ]] || fail "repository approval variable is not true"
[[ -f release/native-redistribution-manifest.json ]] || fail "redistribution manifest is missing"
[[ -f gradle/native-distributions.properties ]] || fail "native input pins are missing"

if command -v sha256sum >/dev/null 2>&1; then
  current_pins_sha="$(sha256sum gradle/native-distributions.properties | awk '{ print $1 }')"
else
  current_pins_sha="$(shasum -a 256 gradle/native-distributions.properties | awk '{ print $1 }')"
fi
public_key_path="$(jq -r '.releaseSigningPublicKeyPath' \
  release/native-redistribution-manifest.json)"
[[ "$public_key_path" == "release/indexino-release-signing-key.asc" ]] || \
  fail "release signing public key path is not approved"
[[ -f "$public_key_path" ]] || fail "approved release signing public key is missing"
if command -v sha256sum >/dev/null 2>&1; then
  current_public_key_sha="$(sha256sum "$public_key_path" | awk '{ print $1 }')"
else
  current_public_key_sha="$(shasum -a 256 "$public_key_path" | awk '{ print $1 }')"
fi
expected_fingerprint="$(jq -r '.releaseSigningKeyFingerprint' \
  release/native-redistribution-manifest.json)"
if [[ "${VERIFY_PUBLIC_KEY_FINGERPRINT:-false}" == "true" ]]; then
  command -v gpg >/dev/null 2>&1 || fail "gpg is required to verify the approved public key"
  public_key_fingerprint="$(
    gpg --batch --with-colons --show-keys "$public_key_path" | \
      awk -F: '$1 == "fpr" { print $10; exit }'
  )"
  [[ "$public_key_fingerprint" == "$expected_fingerprint" ]] || \
    fail "approved public key does not match the approved fingerprint"
fi

manifest_complete="$(jq -r \
  --arg current_pins_sha "$current_pins_sha" \
  --arg current_public_key_sha "$current_public_key_sha" '
  .approvalStatus == "APPROVED" and
  .nativeInputPinsSha256 == $current_pins_sha and
  (.releaseSigningKeyFingerprint | test("^([0-9A-F]{40}|[0-9A-F]{64})$")) and
  .releaseSigningPublicKeySha256 == $current_public_key_sha and
  .jbr.sourceMappingStatus == "APPROVED" and
  (.jbr.sourceTag | length > 0) and
  (.jbr.sourceCommit | test("^[0-9a-f]{40}$")) and
  (.jbr.correspondingSourceMechanism | startswith("https://")) and
  (.roast.source | startswith("https://")) and
  .windowsAuthenticodePolicy.policy == "UNSIGNED"
' release/native-redistribution-manifest.json)"
[[ "$manifest_complete" == "true" ]] || \
  fail "redistribution manifest is incomplete or does not match the immutable native pins"

echo "native release readiness: approved manifest matches immutable native pins"
