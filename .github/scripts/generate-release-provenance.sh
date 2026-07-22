#!/usr/bin/env bash

set -euo pipefail

fail() {
  echo "release provenance: $*" >&2
  exit 1
}

[[ "$#" == 2 ]] || fail "usage: $0 <asset-directory> <output-directory>"
readonly ASSET_DIRECTORY="$1"
readonly OUTPUT_DIRECTORY="$2"
[[ -d "$ASSET_DIRECTORY" ]] || fail "missing asset directory $ASSET_DIRECTORY"
[[ -n "${GITHUB_SHA:-}" ]] || fail "missing GITHUB_SHA"
[[ -n "${GITHUB_RUN_ID:-}" ]] || fail "missing GITHUB_RUN_ID"
[[ -n "${GITHUB_REF_NAME:-}" ]] || fail "missing GITHUB_REF_NAME"
[[ -n "${SIGNING_IN_MEMORY_KEY:-}" ]] || fail "missing SIGNING_IN_MEMORY_KEY"
[[ -n "${SIGNING_IN_MEMORY_KEY_PASSWORD:-}" ]] || \
  fail "missing SIGNING_IN_MEMORY_KEY_PASSWORD"

mkdir -p "$OUTPUT_DIRECTORY"
readonly MANIFEST="$OUTPUT_DIRECTORY/indexino-release-provenance.txt"
readonly GNUPG_DIRECTORY="$(mktemp -d)"
readonly TEMPORARY_MANIFEST="$GNUPG_DIRECTORY/indexino-release-provenance.txt"
trap 'rm -rf "$GNUPG_DIRECTORY"' EXIT
chmod 700 "$GNUPG_DIRECTORY"

printf '%s' "$SIGNING_IN_MEMORY_KEY" | \
  gpg --batch --homedir "$GNUPG_DIRECTORY" --import
readonly SIGNING_FINGERPRINT="$(
  gpg --batch --homedir "$GNUPG_DIRECTORY" --with-colons --fingerprint --list-secret-keys | \
    awk -F: '$1 == "fpr" { print $10; exit }'
)"
readonly EXPECTED_FINGERPRINT="$(
  jq -r '.releaseSigningKeyFingerprint' \
    "$ASSET_DIRECTORY/native-redistribution-manifest.json"
)"
[[ "$SIGNING_FINGERPRINT" == "$EXPECTED_FINGERPRINT" ]] || \
  fail "signing key fingerprint does not match the approved redistribution manifest"
readonly PUBLIC_KEY_FINGERPRINT="$(
  gpg --batch --with-colons --show-keys release/indexino-release-signing-key.asc | \
    awk -F: '$1 == "fpr" { print $10; exit }'
)"
[[ "$PUBLIC_KEY_FINGERPRINT" == "$EXPECTED_FINGERPRINT" ]] || \
  fail "approved public key does not match the approved signing fingerprint"
cp release/indexino-release-signing-key.asc \
  "$ASSET_DIRECTORY/indexino-release-signing-key.asc"

while IFS= read -r checksum; do
  (
    cd "$(dirname "$checksum")"
    sha256sum -c "$(basename "$checksum")"
  )
done < <(find "$ASSET_DIRECTORY" -type f -name '*.sha256' | sort)

readonly DEPENDENCY_DECLARATIONS_SHA="$(
  sha256sum build.gradle.kts settings.gradle.kts gradle/libs.versions.toml | \
    sha256sum | \
    awk '{ print $1 }'
)"
readonly CONSTRUO_VERSION="$(awk -F= '$1 == "construo.version" { print $2 }' \
  gradle/native-distributions.properties)"
readonly ROAST_VERSION="$(awk -F= '$1 == "roast.version" { print $2 }' \
  gradle/native-distributions.properties)"
readonly JBR_VERSION="$(awk -F= '$1 == "jbr.version" { print $2 }' \
  gradle/native-distributions.properties)"

{
  echo "indexino-release-provenance-v1"
  echo "source_commit=$GITHUB_SHA"
  echo "release_tag=$GITHUB_REF_NAME"
  echo "ci_run_id=$GITHUB_RUN_ID"
  echo "dependency_lock_state=not-configured"
  echo "dependency_declarations_sha256=$DEPENDENCY_DECLARATIONS_SHA"
  echo "construo_version=$CONSTRUO_VERSION"
  echo "roast_version=$ROAST_VERSION"
  echo "jbr_version=$JBR_VERSION"
  echo "assets:"
  find "$ASSET_DIRECTORY" -type f ! -name '*.asc' -print0 | \
    sort -z | \
    while IFS= read -r -d '' asset; do
      digest="$(sha256sum "$asset" | awk '{ print $1 }')"
      printf '%s  %s\n' "$digest" "$(basename "$asset")"
    done
} > "$TEMPORARY_MANIFEST"
mv "$TEMPORARY_MANIFEST" "$MANIFEST"

printf '%s' "$SIGNING_IN_MEMORY_KEY_PASSWORD" | \
  gpg \
    --batch \
    --yes \
    --homedir "$GNUPG_DIRECTORY" \
    --pinentry-mode loopback \
    --passphrase-fd 0 \
    --local-user "$SIGNING_FINGERPRINT" \
    --armor \
    --detach-sign \
    "$MANIFEST"
