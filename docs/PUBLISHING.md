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
runs the full check and publication verifier with that release version, signs every publication
artifact with the in-memory PGP key, and uploads to the Sonatype Central Portal.

The build uses `automaticRelease = false`, matching Spectre's cautious release flow. A successful
workflow leaves the validated deployment waiting for manual promotion in the Central Portal.

Required repository secrets:

| Secret | Purpose |
|--------|---------|
| `MAVEN_CENTRAL_USERNAME` | Central Portal user-token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user-token password |
| `SIGNING_IN_MEMORY_KEY` | ASCII-armored PGP private key |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | PGP private-key passphrase |

Before the first release, confirm that the Central Portal account can publish under the verified
`dev.sebastiano` namespace. Then push an already-reviewed release commit and its version tag:

```bash
git tag v0.2.0
git push origin v0.2.0
```

After the workflow succeeds, inspect the deployment in the Central Portal and promote it manually.
