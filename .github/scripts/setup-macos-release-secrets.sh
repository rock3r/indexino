#!/usr/bin/env bash
# Populate Indexino GitHub Actions secrets for macOS codesign + notarization.
#
# Source items (1Password):
#   - "Compose Pi Apple signing cert" — credential (app-specific password) and attached
#     developerID_application.cer + developer_id_application.key → MACOS_CERTIFICATE_P12
#   - "Apple ID" — username (email) → APPLE_ID; team ID → APPLE_TEAM_ID
#
# Spectre uses App Store Connect API keys for notarytool; Indexino follows the same
# Developer ID certificate pattern but authenticates notarization with the app-specific
# password stored on this item (matching notarytool's --apple-id flow).
#
# Usage:
#   .github/scripts/setup-macos-release-secrets.sh [owner/repo]
#
# Optional:
#   P12_EXPORT_PASSWORD   password for the generated .p12 (random if unset)
#   OP_APPLE_ITEM         override 1Password item title

set -euo pipefail

REPO="${1:-rock3r/indexino}"
ITEM="${OP_APPLE_ITEM:-Compose Pi Apple signing cert}"
VAULT="${OP_APPLE_VAULT:-Private}"

fail() {
  echo "setup-macos-release-secrets: $*" >&2
  exit 1
}

command -v op >/dev/null 2>&1 || fail "1Password CLI 'op' is required"
command -v gh >/dev/null 2>&1 || fail "GitHub CLI 'gh' is required"
command -v openssl >/dev/null 2>&1 || fail "openssl is required"
command -v jq >/dev/null 2>&1 || fail "jq is required"

readonly WORK_DIRECTORY="$(mktemp -d)"
cleanup() {
  rm -rf "$WORK_DIRECTORY"
}
trap cleanup EXIT

APPLE_ID_ITEM="${APPLE_ID_ITEM:-Apple ID}"
APPLE_ID="$(op item get "$APPLE_ID_ITEM" --vault "$VAULT" --fields label=username --reveal)"
APPLE_APP_SPECIFIC_PASSWORD="$(op item get "$ITEM" --vault "$VAULT" --fields label=credential --reveal)"
APPLE_TEAM_ID="$(op item get "$APPLE_ID_ITEM" --vault "$VAULT" --fields label="team ID" --reveal)"
[[ -n "$APPLE_ID" ]] || fail "missing Apple ID email on ${APPLE_ID_ITEM}"
[[ "$APPLE_ID" == *"@"* ]] || fail "Apple ID must be an email address"
[[ -n "$APPLE_APP_SPECIFIC_PASSWORD" ]] || fail "missing credential on 1Password item"
[[ -n "$APPLE_TEAM_ID" ]] || fail "missing team ID on 1Password item"

ITEM_ID="$(op item get "$ITEM" --vault "$VAULT" --format json | jq -r '.id')"
[[ -n "$ITEM_ID" && "$ITEM_ID" != null ]] || fail "could not resolve 1Password item id"

op read \
  --out-file "$WORK_DIRECTORY/developerID_application.cer" \
  --force \
  "op://${VAULT}/${ITEM_ID}/developerID_application.cer"
op read \
  --out-file "$WORK_DIRECTORY/developer_id_application.key" \
  --force \
  "op://${VAULT}/${ITEM_ID}/developer_id_application.key"

detect_certificate_format() {
  local certificate_path="$1"
  if openssl x509 -inform DER -in "$certificate_path" -noout >/dev/null 2>&1; then
    printf '%s' DER
  elif openssl x509 -inform PEM -in "$certificate_path" -noout >/dev/null 2>&1; then
    printf '%s' PEM
  else
    fail "could not parse Developer ID certificate attachment"
  fi
}

readonly CERTIFICATE_FORMAT="$(
  detect_certificate_format "$WORK_DIRECTORY/developerID_application.cer"
)"

# OpenSSL 3 defaults to PBES2/AES PKCS#12, which macOS `security import` rejects with
# "MAC verification failed (wrong password?)". Export with Apple-compatible PBES1.
readonly CERTIFICATE_PEM="$WORK_DIRECTORY/developerID_application.pem"
openssl x509 \
  -inform "$CERTIFICATE_FORMAT" \
  -in "$WORK_DIRECTORY/developerID_application.cer" \
  -out "$CERTIFICATE_PEM"

P12_EXPORT_PASSWORD="${P12_EXPORT_PASSWORD:-$(openssl rand -base64 24)}"
openssl pkcs12 -export \
  -inkey "$WORK_DIRECTORY/developer_id_application.key" \
  -in "$CERTIFICATE_PEM" \
  -out "$WORK_DIRECTORY/developer-id.p12" \
  -passout "pass:${P12_EXPORT_PASSWORD}" \
  -name "Indexino Developer ID" \
  -legacy \
  -certpbe PBE-SHA1-3DES \
  -keypbe PBE-SHA1-3DES \
  -macalg sha1

# Prove the archive imports with the password before uploading secrets.
readonly VERIFY_KEYCHAIN="$WORK_DIRECTORY/verify.keychain-db"
security create-keychain -p "$P12_EXPORT_PASSWORD" "$VERIFY_KEYCHAIN"
security set-keychain-settings -lut 21600 "$VERIFY_KEYCHAIN"
security unlock-keychain -p "$P12_EXPORT_PASSWORD" "$VERIFY_KEYCHAIN"
security import "$WORK_DIRECTORY/developer-id.p12" \
  -k "$VERIFY_KEYCHAIN" \
  -P "$P12_EXPORT_PASSWORD" \
  -T /usr/bin/codesign \
  -f pkcs12 \
  >/dev/null
security delete-keychain "$VERIFY_KEYCHAIN" >/dev/null 2>&1 || true

MACOS_SIGNING_IDENTITY="$(
  openssl x509 \
    -inform PEM \
    -in "$CERTIFICATE_PEM" \
    -noout \
    -subject \
    -nameopt RFC2253,sep_multiline |
    awk -F= '/CN=Developer ID Application/ { print $2; exit }'
)"
[[ -n "$MACOS_SIGNING_IDENTITY" ]] || \
  fail "could not derive MACOS_SIGNING_IDENTITY from the Developer ID certificate"

echo "Setting macOS release secrets on ${REPO} ..."
echo "  MACOS_SIGNING_IDENTITY=${MACOS_SIGNING_IDENTITY}"
echo "  APPLE_ID=${APPLE_ID}"
echo "  APPLE_TEAM_ID=${APPLE_TEAM_ID}"

base64 < "$WORK_DIRECTORY/developer-id.p12" | tr -d '\n' | \
  gh secret set MACOS_CERTIFICATE_P12 --repo "$REPO"
printf '%s' "$P12_EXPORT_PASSWORD" | \
  gh secret set MACOS_CERTIFICATE_PASSWORD --repo "$REPO"
printf '%s' "$MACOS_SIGNING_IDENTITY" | \
  gh secret set MACOS_SIGNING_IDENTITY --repo "$REPO"
printf '%s' "$APPLE_ID" | gh secret set APPLE_ID --repo "$REPO"
printf '%s' "$APPLE_APP_SPECIFIC_PASSWORD" | \
  gh secret set APPLE_APP_SPECIFIC_PASSWORD --repo "$REPO"
printf '%s' "$APPLE_TEAM_ID" | gh secret set APPLE_TEAM_ID --repo "$REPO"

echo "Done. Configured 6 macOS release secrets on ${REPO}."
