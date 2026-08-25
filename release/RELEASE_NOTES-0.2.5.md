# Indexino 0.2.5

Patch release: first **notarized** Tier 1 native CLI ZIPs on the GitHub release.

`v0.2.4` notarization succeeded but CI failed on `spctl` for the bare CLI launcher
(`valid but does not seem to be an app`). This patch treats that assessment as
best-effort after Accepted notarization, matching Spectre's bare-helper policy.

## Published scope

- **Maven Central** — `dev.sebastiano.indexino:*:0.2.5`
- **GitHub release assets** — notarized `indexino-cli-*-0.2.5.zip` plus checksums and
  `bundled-dependencies.txt`

### Maven coordinates

```kotlin
dependencies {
    implementation(platform("dev.sebastiano.indexino:indexino-bom:0.2.5"))
    implementation("dev.sebastiano.indexino:indexino")
}
```

Minimum JDK for published library artifacts: **25**.
