# API stability

Indexino has not published a release. The reviewed embedded API is landing incrementally through
the tracer-bullet slices in [PUBLIC-API-DESIGN.html](PUBLIC-API-DESIGN.html), starting with the S1
model and in-process facade. CLI, Xodus, topology, parser, producer, and persistence implementation
types remain outside the compatibility boundary.

## Current boundary

- Public declarations exist only in the packages and artifacts listed below; other production
  declarations remain `internal`.
- Strict Kotlin explicit API mode is enabled.
- `dev.sebastiano.indexino.model` and `dev.sebastiano.indexino.api` are the supported S1 embedding
  packages.
- CLI JSONL protocols and the on-disk schema are separate contracts documented in
  [CLI.md](CLI.md) and [INDEX-STORAGE.md](INDEX-STORAGE.md).
- **Metalava** is the sole reviewed signature source for public packages
  (`api/<artifact>/current.txt`). Update dumps only via the explicit
  `metalavaUpdateSignature` / `:indexino-model:metalavaUpdateSignature` tasks after human review —
  never to silence CI.
- KGP `abiValidation` / `api/indexino.api` are **not** used. See
  [PUBLIC-API-DESIGN.html](PUBLIC-API-DESIGN.html).
- The plugin API additionally keeps immutable SemVer-named dumps under
  `api/indexino-plugin-api/history/`, records the ordered required versions in `abi-lineage.txt`,
  and declares its current ABI in `abi-version.txt`. The host
  support range is generated from this lineage; it is never handwritten.

Kotlin `internal` declarations still compile to JVM implementation bytecode because the CLI is a
single module. Their presence in the JAR does not make them supported API, and consumers must not
link to them from Java, reflection, or other JVM languages.

## Public and planned packages

| Artifact | Package | Role |
|----------|---------|------|
| `indexino-model` | `dev.sebastiano.indexino.model` | Shared IDs, locations, query pages, findings |
| `indexino` | `dev.sebastiano.indexino.api` | Client facade, refresh, snapshots |
| `indexino-plugin-api` | `dev.sebastiano.indexino.plugin.api` | Versioned SPI (depends on model, not on `indexino`) |
| `indexino-script-host` | `dev.sebastiano.indexino.script` | Optional Alpha/Beta-source script DSL |

Host-constructed SPI types use public constructors annotated `@IndexinoInternalApi`
(`@RequiresOptIn` at error level), not cross-module `internal` constructors.

## Script host compatibility (separate from plugin ABI)

`indexino-script-host` is an **optional** JVM-only surface. Its compatibility promise is **Beta /
source-level** for checked-in `.indexino.kts` scripts and the public DSL types in
`dev.sebastiano.indexino.script`. That promise is deliberately separate from the compiled-plugin ABI
lineage tracked for `indexino-plugin-api` (issue #39):

- Scripts are recompiled from source against the host/API version and the **locked allowed
  dependency set**. Cached compiled scripts are keyed by script content digest, host/API version,
  Kotlin version, and that dependency digest; stale or incompatible entries recompile and are not
  pinned into the index or daemon.
- There is **no** binary compatibility guarantee for previously compiled script bytecode across
  Indexino releases.
- Import/dependency policy stays explicit: default imports cover `script.*` and `model.*`; engine,
  CLI, producer, topology, and PSI packages are rejected before compilation. Do not broaden that
  set silently.
Time limits and cancellation are cooperative (interrupt flag and optional cancel token). Scripts
that ignore interruption may leave an evaluation worker running; after that happens the host
refuses new runs until the abandoned worker finishes, and the snapshot is closed so index
resources are not kept pinned. Native and R8-shrunk distributions do not host `.indexino.kts`
(see [DISTRIBUTIONS.md](DISTRIBUTIONS.md)).

## Adding the first API

The product boundary, tracer-bullet slices, plugin SPI, cache layout, and compatibility gates are
specified in [PUBLIC-API-DESIGN.html](PUBLIC-API-DESIGN.html) (**Accepted 2026-07-25**). Shared
opt-in annotations and fact types (`IndexinoInternalApi`, `BasicFactQueries`, `PluginFactValue`,
…) live in `indexino-model` as declared there. Before accepting declarations:

1. Design the complete initial operation and model boundary; do not expose implementation types.
2. Use explicit `public` visibility and explicit return/property types.
3. Prefer ordinary final classes with factories and structural `equals`/`hashCode`/`toString` for
   value/request/result types. Do not publish data classes or `@JvmInline` value classes.
4. Add Kotlin consumer compilation tests and Java linkage fixtures (forward fixtures in
   `check verifyMavenPublication`).
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
3. **Consumer fixtures** — forward in the pre-merge `check verifyMavenPublication` gate;
   cross-version linkage at release only.

KGP `abiValidation` and japicmp are **not** part of the target stack.

## Plugin ABI lineage

Plugin ABI starts at `1.0.0` even while the Indexino product remains on a `0.y` release line. Each
reviewed plugin ABI publishes an immutable Metalava dump named `<major>.<minor>.<patch>.txt`.
Publication compares the new source surface with the previous dump using Metalava, verifies that
the declared ABI evolution matches the result, and checks the current source against every older
dump advertised in the same-major host range.

- An unchanged surface may advance only the ABI patch.
- An additive compatible surface requires an ABI minor increment.
- A breaking surface requires the next ABI major at `x.0.0`.
- A missing current or required historical dump fails publication.

The generated metadata records the current ABI and the first compatible dump in its major lineage.
The loader uses only that generated fact. See [PLUGIN-AUTHORING.md](PLUGIN-AUTHORING.md) for the
compiled-plugin manifest contract.
