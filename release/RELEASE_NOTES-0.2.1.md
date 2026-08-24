# Indexino 0.2.1

Patch release on the **0.y** platform train: Maven Central plus Tier 1 native CLI ZIPs on the
GitHub release (macOS notarized).

## Published scope

- **Maven Central** — same artifact set as 0.2.0 at `0.2.1` coordinates
- **GitHub release assets** — `indexino-cli-{linux-x64,macos-arm64,windows-x64}-0.2.1.zip`
  with matching `.sha256` checksums and `bundled-dependencies.txt`

Each native archive bundles the pinned JBR 25 runtime with its `runtime/legal/` notices, Roast
(Apache 2.0), Indexino (UEL), and the dependency inventory.

### Maven coordinates

```kotlin
dependencies {
    implementation(platform("dev.sebastiano.indexino:indexino-bom:0.2.1"))
    implementation("dev.sebastiano.indexino:indexino")
}
```

Minimum JDK for published library artifacts: **25**.

## Since 0.2.0

- **Native release pipeline** — every `v*` tag now builds and drafts all three Tier 1 CLI ZIPs;
  macOS archives are Developer ID signed and notarized in CI
- **S11** — compiled Compose decoration plugin (`indexino-compose-decoration`)
- **Source-link federation** — provenance-aware dependency source links (#45)
- **Native dynamic extensions** — out-of-process protocol v1 tracer (#64)
- **Worktree overlays** — nested chains, deepest-base selection, resource/plugin/LKG coverage (#44,
  tranches #58–#62)

## Compatibility policy (0.y)

Patch releases preserve binary compatibility and aim to preserve source and behavioral compatibility.
Plugin ABI remains **1.0.0** unless explicitly noted in Metalava diffs.

## Reporting incompatibilities

Open an issue at https://github.com/rock3r/indexino with consumed coordinates, a minimal
reproducer, and any local Metalava diff if you changed public API.
