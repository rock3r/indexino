# Publishing

The release train is published with
[`com.vanniktech.maven.publish`](https://github.com/vanniktech/gradle-maven-publish-plugin):
`indexino-bom`, `indexino-model`, `indexino`, `indexino-plugin-api`,
`indexino-selection-context`, `indexino-compose-decoration`, and the optional Alpha `indexino-script-host` share Central metadata,
signing, and one release version. The Shadow
`*-all.jar` remains the standalone CLI distribution and the `*-shrunk.jar` remains an internal
native-packaging input. Both are deliberately excluded from Maven publication.

## API publication state

No version has been published. The current snapshot exposes the reviewed S1 API from
`indexino-model` and `dev.sebastiano.indexino.api`; implementation packages remain unsupported.
Metalava signature dumps under `api/<artifact>/`, detekt declaration rules, and external consumer
fixtures enforce that boundary. See [API-STABILITY.md](API-STABILITY.md) and
[PUBLIC-API-DESIGN.html](PUBLIC-API-DESIGN.html).

The plugin API is governed separately by `api/indexino-plugin-api/abi-version.txt`, the ordered
`abi-lineage.txt` ledger, and immutable versioned dumps under `api/indexino-plugin-api/history/`.
`verifyPluginAbiLineage` uses Metalava to
classify the new surface, rejects SemVer/ABI declarations that disagree with that comparison, and
generates the host's supported interval. A release must never edit an old dump or substitute a
handwritten optimistic range.

The CLI remains executable from the Shadow and R8 artifacts. Presence of implementation bytecode
in the thin JAR does not make packages outside the future public packages a supported API.

Minimum JDK for published library artifacts is **25** (aligned with the JBR 25 native pin).

## Consumer coordinates

Consumers need no Indexino-specific Gradle plugin. Import the BOM to align the release train:

```kotlin
dependencies {
    implementation(platform("dev.sebastiano.indexino:indexino-bom:<version>"))
    implementation("dev.sebastiano.indexino:indexino")
    // optional:
    // implementation("dev.sebastiano.indexino:indexino-selection-context")
    // implementation("dev.sebastiano.indexino:indexino-script-host")
}
```

Maven consumers use the equivalent `dependencyManagement` import for `indexino-bom`. The aligned
artifacts are `indexino-model`, `indexino`, `indexino-plugin-api`,
`indexino-selection-context`, and `indexino-script-host`.

The library POM must **not** expose Clikt. Coroutines are an `api` dependency of the client because
`suspend`/`Flow` appear in signatures. Xodus, the Kotlin compiler embeddable, and serialization
remain implementation dependencies of the engine/client JAR.

## Local verification

The default version on `main` is `0.3.0-SNAPSHOT`. No credentials or signing key are required to
verify the publication locally:

```bash
./gradlew verifyMavenPublication
```

The task publishes to an isolated repository under `build/test-maven-repository/` and checks:

- the aligned BOM plus each thin artifact's main, sources, javadoc, and POM artifacts exist;
  library modules also publish Gradle module metadata, while `indexino` deliberately remains
  POM-only so consumers resolve its filtered dependency graph
- the main artifact contains indexino classes but no bundled dependency classes
- the Shadow `*-all.jar`, R8 `*-shrunk.jar`, and optional Shadow runtime variant are absent from
  both artifacts and publication metadata
- the POM contains Central-required name, description, URL, license, SCM, developer, and
  dependency metadata
- `indexino-plugin-api` contains generated current/range metadata, the host advertises the same
  generated ABI and supported range from its host-owned
  `META-INF/indexino/host-plugin-abi.properties` resource (distinct from the plugin API metadata),
  and every compiled reference plugin declares
  `Indexino-Plugin-ABI-Target`

`./gradlew publishToMavenLocal` is also available for testing a consumer build against the local
Maven repository.

## Tag-driven release flow

`.github/workflows/release.yml` runs for tags shaped like `v<semver>`. It strips the leading `v`,
runs the full check (including `verifyPluginAbiLineage`), thin publication verifier, R8 verifier,
and generated bundled-dependency
inventory with that release version, signs every Maven publication artifact with the in-memory PGP
key, and uploads to the Sonatype Central Portal.

The project does not currently enable Gradle dependency locking. The release workflow generates a
`bundled-dependencies.txt` inventory with the resolved coordinate, filename, and SHA-256 of every
JVM dependency bundled into the native JAR and attaches it to the GitHub release draft.

The build uses `automaticRelease = false`, matching Spectre's cautious release flow. A successful
workflow leaves the validated deployment waiting for manual promotion in the Central Portal.

Every release tag runs the full check suite, uploads the Maven train, builds the Tier 1 native CLI
ZIPs, and creates a **draft** GitHub release with `release/RELEASE_NOTES-<version>.md`, the three
native archives, their SHA-256 sidecars, and `bundled-dependencies.txt`. Publish that draft only
after Central promotion and external resolution checks succeed.

The macOS job signs all Mach-O payloads, creates the immutable final ZIP, submits those exact bytes
for notarization, exercises online Gatekeeper, and reruns the complete native verifier against the
signed archive before replacing its checksum. The workflow never auto-publishes the GitHub draft.

Required repository secrets:

| Secret | Purpose |
|--------|---------|
| `MAVEN_CENTRAL_USERNAME` | Central Portal user-token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user-token password |
| `SIGNING_IN_MEMORY_KEY` | ASCII-armored PGP private key |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | PGP private-key passphrase |
| `MACOS_CERTIFICATE_P12` | Base64-encoded Developer ID Application certificate and private key |
| `MACOS_CERTIFICATE_PASSWORD` | Password protecting the certificate archive |
| `MACOS_SIGNING_IDENTITY` | Exact Developer ID Application identity passed to `codesign` |
| `APPLE_ID` | Apple account used by `notarytool` |
| `APPLE_APP_SPECIFIC_PASSWORD` | App-specific notarization password |
| `APPLE_TEAM_ID` | Apple Developer team identifier |

Configure the macOS secrets once per repository. Indexino reuses the same Developer ID
certificate material as other `dev.sebastiano` projects; notarization uses the app-specific
password flow (`notarytool --apple-id`) rather than Spectre's App Store Connect API key flow.

From a machine with `op`, `gh`, `jq`, and `openssl` authenticated:

```bash
.github/scripts/setup-macos-release-secrets.sh rock3r/indexino
```

The script reads:

- **Compose Pi Apple signing cert** — app-specific password (`credential`) and attached
  `.cer`/`.key` → base64 `.p12`
- **Apple ID** — account email (`username` → `APPLE_ID`) and `team ID` → `APPLE_TEAM_ID`

Before the first release, confirm that the Central Portal account can publish under the verified
`dev.sebastiano` namespace. Then push an already-reviewed release commit and its version tag:

```bash
git tag v0.2.0
git push origin v0.2.0
```

After the workflow succeeds, inspect the deployment in the Central Portal and promote it manually.
Verify public Maven coordinates from a clean consumer, then publish the GitHub draft
(`gh release edit <tag> --draft=false`). Before publishing, inspect the draft release assets,
checksum sidecars, bundled-dependency inventory, and the native verification logs from CI.
