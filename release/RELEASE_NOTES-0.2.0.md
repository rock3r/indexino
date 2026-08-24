# Indexino 0.2.0

First public **0.y** release of the Indexino platform on Maven Central.

## Published scope

This release publishes the aligned Maven train only. Native CLI ZIP redistribution remains
**withheld** while `release/native-redistribution-manifest.json` has
`approvalStatus: PENDING_COUNSEL_APPROVAL`. CI artifacts and local native verifiers remain available
for development; they are not part of this public release.

### Maven coordinates

Import the BOM to align every artifact:

```kotlin
dependencies {
    implementation(platform("dev.sebastiano.indexino:indexino-bom:0.2.0"))
    implementation("dev.sebastiano.indexino:indexino")
}
```

Published artifacts:

| Artifact | Role |
|----------|------|
| `indexino-bom` | Version alignment |
| `indexino-model` | Shared public model types |
| `indexino` | Embedded client facade |
| `indexino-plugin-api` | Versioned plugin SPI (ABI **1.0.0**) |
| `indexino-selection-context` | Reference compiled plugin |
| `indexino-script-host` | Optional Alpha script DSL |

The Shadow `*-all.jar` and R8 `*-shrunk.jar` remain **distribution-only** and are not on Maven
Central.

Minimum JDK for published library artifacts: **25**.

## Compatibility policy (0.y)

While the product remains on `0.y.z`, an incompatible public API change requires a new **minor**
version. Patch releases preserve binary compatibility and aim to preserve source and behavioral
compatibility. APIs explicitly marked experimental may change in a minor release without a major
product bump.

Plugin ABI is governed separately: host support is generated from the Metalava lineage starting at
**1.0.0**. Do not hand-edit supported ranges.

Cross-version consumer linkage against a previously published artifact is verified at **release
time**; there is no prior Central release before 0.2.0.

## Stable in 0.2.0

Tracer foundation through **S7** (in-process facade, durable refresh, plugin SPI, packaging,
daemon protocol, auto-refresh + large-repository gate, multi-origin topology adapters):

- Public packages in `dev.sebastiano.indexino.model`, `dev.sebastiano.indexino.api`, and
  `dev.sebastiano.indexino.plugin.api`
- User-local content-addressed cache (not under the worktree)
- Metalava-reviewed signatures and forward Kotlin/Java consumer fixtures
- `indexino-selection-context` reference plugin
- Resource query APIs (`findResources`, `findResourceUsages`) on the public model and snapshot surfaces

## Experimental / Alpha

- `indexino-script-host` (`dev.sebastiano.indexino.script`) — Alpha, non-blocking for embedders
- CLI JSONL protocols and on-disk storage layouts documented separately in `docs/CLI.md` and
  `docs/INDEX-STORAGE.md`

## Deferred (not release blockers)

- **S10 follow-on** — broader Android/CMP resource indexing coverage beyond the shipped query APIs
- **S11** — Compose decoration plugin
- Native CLI ZIP publication pending counsel approval of redistribution terms
- Post-v1 worktree overlay optimizations (#44)

## Reporting incompatibilities

Open an issue at https://github.com/rock3r/indexino with the consumed coordinates, failing consumer
fixture or minimal reproducer, and the Metalava diff if you changed public API locally.
