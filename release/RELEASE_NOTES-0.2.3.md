# Indexino 0.2.3

Patch release: notarized Tier 1 native CLI ZIPs. Signs Mach-O natives embedded in
bundled JARs (JNA / Jansi) so Apple notarization accepts the archive.

## Why 0.2.3

`v0.2.2` published Maven Central successfully, but notarization returned **Invalid**
because unsigned `.jnilib` payloads inside `indexino-cli.jar` and
`indexino-extension-worker.jar` were rejected. This patch signs those nested natives
before submission.

Prefer **0.2.3** coordinates and GitHub assets over 0.2.1/0.2.2 for native CLI use.

## Published scope

- **Maven Central** — `dev.sebastiano.indexino:*:0.2.3`
- **GitHub release assets** — notarized `indexino-cli-*-0.2.3.zip` plus checksums and
  `bundled-dependencies.txt`

### Maven coordinates

```kotlin
dependencies {
    implementation(platform("dev.sebastiano.indexino:indexino-bom:0.2.3"))
    implementation("dev.sebastiano.indexino:indexino")
}
```

Minimum JDK for published library artifacts: **25**.

## Reporting incompatibilities

Open an issue at https://github.com/rock3r/indexino with consumed coordinates and a
minimal reproducer.
