# Indexino 0.2.2

Patch release: first GitHub release with notarized Tier 1 native CLI ZIPs. Maven train
aligned at `0.2.2`.

## Why 0.2.2

`v0.2.1` published the Maven train to Central, but macOS codesign in CI failed (temporary
keychain was not selected on the search list after PKCS12 import). This patch fixes the
signing bootstrap and ships the native archives.

## Published scope

- **Maven Central** — `dev.sebastiano.indexino:*:0.2.2`
- **GitHub release assets** — `indexino-cli-{linux-x64,macos-arm64,windows-x64}-0.2.2.zip`
  with matching `.sha256` checksums and `bundled-dependencies.txt` (macOS notarized)

### Maven coordinates

```kotlin
dependencies {
    implementation(platform("dev.sebastiano.indexino:indexino-bom:0.2.2"))
    implementation("dev.sebastiano.indexino:indexino")
}
```

Minimum JDK for published library artifacts: **25**.

## Since 0.2.1

- macOS release signing: import Developer ID into a selected temporary keychain (Spectre
  pattern) so `codesign` / `set-key-partition-list` resolve the identity
- PKCS12 bootstrap: OpenSSL 3 exports use Apple-compatible PBES1 (`-legacy`) and verify
  import locally before uploading GitHub secrets

## Compatibility policy (0.y)

Patch releases preserve binary compatibility and aim to preserve source and behavioral
compatibility. Plugin ABI remains **1.0.0** unless explicitly noted in Metalava diffs.

## Reporting incompatibilities

Open an issue at https://github.com/rock3r/indexino with consumed coordinates, a minimal
reproducer, and any local Metalava diff if you changed public API.
