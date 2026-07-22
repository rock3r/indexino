# Publishing

`indexino` is configured to publish a thin JVM artifact to the Sonatype Central Portal with the
[`com.vanniktech.maven.publish`](https://github.com/vanniktech/gradle-maven-publish-plugin)
plugin. The Shadow `*-all.jar` remains the standalone CLI distribution and the `*-shrunk.jar`
remains an internal native-packaging input. Both are deliberately excluded from the Maven
publication.

## API publication state

No version has been published and the current snapshot deliberately has no supported embedded API.
All implementation declarations are Kotlin `internal`, strict explicit API mode is enabled, and
`api/indexino.api` is an empty ABI baseline. `checkKotlinAbi`, which is part of `check`, fails if a
public declaration is added without an explicit baseline review.

The CLI remains executable from the Shadow and R8 artifacts. Presence of implementation bytecode
in the thin JAR does not make packages outside the future `dev.sebastiano.indexino.api` namespace a
supported API. See [API-STABILITY.md](API-STABILITY.md).

## Future consumer coordinates

Consumers will need no publishing or Indexino-specific Gradle plugin. Add the artifact as a normal
dependency after the first embedded API is defined:

```kotlin
dependencies {
    implementation("dev.sebastiano.indexino:indexino:<version>")
}
```

Maven consumers use the equivalent coordinates:

```xml
<dependency>
  <groupId>dev.sebastiano.indexino</groupId>
  <artifactId>indexino</artifactId>
  <version>VERSION</version>
</dependency>
```

The generated POM supplies Kotlin, Clikt, kotlinx.serialization, Xodus, and SLF4J runtime
dependencies transitively. Consumers do not need to assemble a fat JAR or duplicate that list.

## Local verification

The default version on `main` is `0.2.0-SNAPSHOT`. No credentials or signing key are required to
verify the publication locally:

```bash
./gradlew verifyMavenPublication
```

The task publishes to an isolated repository under `build/test-maven-repository/` and checks:

- the main, sources, javadoc, POM, and Gradle module metadata artifacts exist
- the main artifact contains indexino classes but no bundled dependency classes
- the Shadow `*-all.jar`, R8 `*-shrunk.jar`, and optional Shadow runtime variant are absent from
  both artifacts and publication metadata
- the POM contains Central-required name, description, URL, license, SCM, developer, and
  dependency metadata

`./gradlew publishToMavenLocal` is also available for testing a consumer build against the local
Maven repository.

## Tag-driven release flow

`.github/workflows/release.yml` runs for tags shaped like `v<semver>`. It strips the leading `v`,
runs the full check, thin publication verifier, R8 verifier, and generated bundled-dependency
inventory with that release version, signs every Maven publication artifact with the in-memory PGP
key, and uploads to the Sonatype Central Portal.

The project does not currently enable Gradle dependency locking. The release provenance records that
state explicitly, binds the dependency-declaration files, and includes a generated inventory with
the resolved coordinate, filename, and SHA-256 of every JVM dependency bundled into the native JAR.

The build uses `automaticRelease = false`, matching Spectre's cautious release flow. A successful
workflow leaves the validated deployment waiting for manual promotion in the Central Portal.

Native release drafting is a separately gated continuation of the tag workflow. It remains skipped
unless `release/native-redistribution-manifest.json` has `approvalStatus` set to `APPROVED` by a
reviewed change and the repository variable `NATIVE_RELEASE_APPROVED` is exactly `true`. Once both
gates are present, the tag workflow calls the reusable Tier 1 matrix with the release version. The
macOS job signs all Mach-O payloads, creates the immutable final ZIP, submits those exact bytes for
notarization, exercises online Gatekeeper, and reruns the complete native verifier against the
signed archive before replacing its checksum. Only after Maven verification and every native job
pass does the workflow create a draft GitHub release. It never publishes that draft.

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

Before the first release, confirm that the Central Portal account can publish under the verified
`dev.sebastiano` namespace. Then push an already-reviewed release commit and its version tag:

```bash
git tag v0.2.0
git push origin v0.2.0
```

After the workflow succeeds, inspect the deployment in the Central Portal and promote it manually.
If native release approval was enabled, independently inspect the draft GitHub release, signed
aggregate provenance, checksums, legal manifest, and all three verification logs before publishing
the draft manually.
