#!/usr/bin/env bash
# SPDX-License-Identifier: UEL-1.0
# CI-only Linux x64 toolchain, verified against the release toolchain pins.
set -euo pipefail
root="${1:?new toolchain destination required}"
test ! -e "$root"
mkdir -p "$root"
pins="$(dirname "$0")/../../gradle/native-distributions.properties"
url="$(sed -n 's/^linuxX64.jdkUrl=//p' "$pins")"
digest="$(sed -n 's/^linuxX64.jdkSha256=//p' "$pins")"
curl --fail --location --proto '=https' --max-time 300 "$url" -o "$root/jdk.tar.gz"
printf '%s  %s\n' "$digest" "$root/jdk.tar.gz" | sha256sum --check --strict
mkdir "$root/jdk"
tar -xzf "$root/jdk.tar.gz" -C "$root/jdk" --strip-components=1
printf 'JAVA_HOME=%s/jdk\n' "$root" >> "${GITHUB_ENV:?CI environment required}"
printf '%s/jdk/bin\n' "$root" >> "${GITHUB_PATH:?CI path required}"
