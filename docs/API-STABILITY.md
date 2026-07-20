# API stability

Indexino has not published a release and currently exposes no supported embedded Kotlin API. This
is intentional: the first API will start from a reviewed clean slate instead of inheriting the CLI,
Xodus, topology, parser, producer, or persistence implementation as accidental compatibility
commitments.

## Current boundary

- Every production Kotlin declaration is `internal`.
- Strict Kotlin explicit API mode is enabled.
- `api/indexino.api` is the committed Kotlin ABI baseline and is intentionally empty.
- Kotlin Gradle Plugin ABI validation runs from `./gradlew check`.
- No package in the current thin JAR is a supported Java or Kotlin embedding contract.
- CLI JSONL protocols and the on-disk schema are separate contracts documented in
  [CLI.md](CLI.md) and [INDEX-STORAGE.md](INDEX-STORAGE.md).

Kotlin `internal` declarations still compile to JVM implementation bytecode because the CLI is a
single module. Their presence in the JAR does not make them supported API, and consumers must not
link to them from Java, reflection, or other JVM languages.

## Adding the first API

The first supported declarations belong under `dev.sebastiano.indexino.api`. Before accepting them:

1. Design the complete initial operation and model boundary; do not expose implementation types.
2. Use explicit `public` visibility and explicit return/property types.
3. Add Kotlin consumer compilation tests and behavioral contract tests.
4. Run `./gradlew updateKotlinAbi` and review the complete `api/indexino.api` change.
5. Document whether the API is stable or requires an explicit experimental opt-in.
6. Run `./gradlew check checkKotlinAbi verifyMavenPublication` before publishing.

Do not update the ABI baseline merely to make CI green.

## Compatibility policy after publication

Indexino follows Semantic Versioning for the declared public API:

- Patch releases preserve binary compatibility and aim to preserve source and behavioral
  compatibility.
- While the version is `0.y.z`, an incompatible public API change requires a new minor version.
- Starting with `1.0.0`, an incompatible public API change requires a new major version.
- Removals use a documented deprecation and replacement path whenever practical.

Source compatibility cannot be proven completely by ABI validation. Preserve it through compiled
consumer fixtures, overloads instead of signature mutation, stable return types, and explicit
migration tests. Avoid public data classes, public sealed hierarchies that may gain cases, default
parameter changes, and public inline implementation details unless their compatibility cost has
been reviewed.
