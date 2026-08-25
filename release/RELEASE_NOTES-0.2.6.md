# Indexino 0.2.6

Patch release: notarized Tier 1 native CLI ZIPs on the GitHub release.

`v0.2.5` passed Apple notarization but CI re-ran the unsigned-archive Gradle
verifier against signed bytes (JAR nested-native signing changes payloads). This
patch smoke-tests the signed launcher after notarization instead.

## Published scope

- **Maven Central** — `dev.sebastiano.indexino:*:0.2.6`
- **GitHub release assets** — notarized `indexino-cli-*-0.2.6.zip` plus checksums and
  `bundled-dependencies.txt`

### Maven coordinates

```kotlin
dependencies {
    implementation(platform("dev.sebastiano.indexino:indexino-bom:0.2.6"))
    implementation("dev.sebastiano.indexino:indexino")
}
```

Minimum JDK for published library artifacts: **25**.
