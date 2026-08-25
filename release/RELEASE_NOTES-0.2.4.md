# Indexino 0.2.4

Patch release fixing macOS nested-native codesign under `set -u` (empty
`preserve-metadata` argv). First intended notarized Tier 1 native GitHub release.

Prefer **0.2.4** over 0.2.1–0.2.3 for native CLI downloads. Maven trains at 0.2.1+
are published; use the latest patch BOM.

## Published scope

- **Maven Central** — `dev.sebastiano.indexino:*:0.2.4`
- **GitHub release assets** — notarized `indexino-cli-*-0.2.4.zip` plus checksums and
  `bundled-dependencies.txt`

### Maven coordinates

```kotlin
dependencies {
    implementation(platform("dev.sebastiano.indexino:indexino-bom:0.2.4"))
    implementation("dev.sebastiano.indexino:indexino")
}
```

Minimum JDK for published library artifacts: **25**.
