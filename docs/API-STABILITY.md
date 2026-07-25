# API stability

Indexino has not published a release and currently exposes no supported embedded Kotlin API. This
is intentional: the first API will start from a reviewed clean slate instead of inheriting the CLI,
Xodus, topology, parser, producer, or persistence implementation as accidental compatibility
commitments.

## Current boundary

- Every production Kotlin declaration is `internal`.
- Strict Kotlin explicit API mode is enabled.
- No package in the current thin JAR is a supported Java or Kotlin embedding contract.
- CLI JSONL protocols and the on-disk schema are separate contracts documented in
  [CLI.md](CLI.md) and [INDEX-STORAGE.md](INDEX-STORAGE.md).
- Until the multi-artifact public API lands, KGP `abiValidation` / empty `api/indexino.api` may
  still run as a tripwire against accidental `public` declarations. The **target** compatibility
  stack (first embedded API / S1+) is Metalava-only — see below and
  [PUBLIC-API-DESIGN.html](PUBLIC-API-DESIGN.html).

Kotlin `internal` declarations still compile to JVM implementation bytecode because the CLI is a
single module. Their presence in the JAR does not make them supported API, and consumers must not
link to them from Java, reflection, or other JVM languages.

## Target packages (first embedded API)

| Artifact | Package | Role |
|----------|---------|------|
| `indexino-model` | `dev.sebastiano.indexino.model` | Shared IDs, locations, query pages, findings |
| `indexino` | `dev.sebastiano.indexino.api` | Client facade, refresh, snapshots |
| `indexino-plugin-api` | `dev.sebastiano.indexino.plugin.api` | Versioned SPI (depends on model, not on `indexino`) |
| `indexino-script-host` | `dev.sebastiano.indexino.script` | Optional Alpha script DSL |

Host-constructed SPI types use public constructors annotated `@IndexinoInternalApi`
(`@RequiresOptIn` at error level), not cross-module `internal` constructors.

## Adding the first API

The product boundary, tracer-bullet slices, plugin SPI, cache layout, and compatibility gates are
specified in [PUBLIC-API-DESIGN.html](PUBLIC-API-DESIGN.html) (**Accepted 2026-07-25**). Shared
opt-in annotations and fact types (`IndexinoInternalApi`, `BasicFactQueries`, `PluginFactValue`,
…) live in `indexino-model` as declared there. Before accepting declarations:

1. Design the complete initial operation and model boundary; do not expose implementation types.
2. Use explicit `public` visibility and explicit return/property types.
3. Prefer ordinary final classes with factories and structural `equals`/`hashCode`/`toString` for
   value/request/result types. Do not publish data classes or `@JvmInline` value classes.
4. Add Kotlin consumer compilation tests and Java linkage fixtures (forward fixtures in `check`).
5. Generate and review Metalava signatures (`api/<artifact>/current.txt`) with `--jdk-home` from
   the Gradle toolchain and an explicit `--format` pin.
6. Document whether the API is stable or requires an explicit experimental opt-in.
7. Run `./gradlew check` including Metalava lineage + `verifyMavenPublication` before publishing.
8. Cross-version linkage against the previously published artifact is **release-time only**.

Do not update signature dumps merely to make CI green.

## Compatibility policy after publication

Indexino follows Semantic Versioning for the declared public API:

- Patch releases preserve binary compatibility and aim to preserve source and behavioral
  compatibility.
- While the version is `0.y.z`, an incompatible public API change requires a new minor version.
- Starting with `1.0.0`, an incompatible public API change requires a new major version.
- Removals use a documented deprecation and replacement path whenever practical.
- Raising the minimum JDK is a major (floor is **25** for the first public API).

Source compatibility cannot be proven completely by signature dumps. Preserve it through compiled
consumer fixtures, overloads instead of signature mutation, stable return types, and explicit
migration tests. Avoid public data classes, public value classes, default parameter changes, and
public inline implementation details unless their compatibility cost has been reviewed.

## Enforcement stack (target)

1. **Metalava** — sole signature source and API lint (`ValueClassDefinition`, `MissingJvmstatic`,
   etc.). Pin format and always pass `--jdk-home`.
2. **detekt** (Jewel-adapted) — package-scoped equality members, no public data classes, API-status
   annotations. detekt **2.0.0-alpha.3**.
3. **Consumer fixtures** — forward in every `check`; cross-version linkage at release only.

KGP `abiValidation` and japicmp are **not** part of the target stack.
