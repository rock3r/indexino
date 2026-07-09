# Index storage

Persistent on-disk layout for **kotlin-code-index**.

## Why `.kotlin-index/` (not `.agent/` or `.agents/`)

| Path | Purpose |
|------|---------|
| **`.kotlin-index/`** | This tool's index store in a workspace being indexed |
| **`.agents/`** | Agent skills in the **tool repo** (unrelated) |

Upstream in-app code-index (#814) uses `.agent/` — we deliberately use a **distinct directory** to avoid confusion. Key schema and record shapes stay compatible; only the root folder name differs. Optional future: `--store-dir` override or import from `.agent/` if both exist.

## Workspace layout

```
<project-root>/
  .kotlin-index/
    index/
      <git-commit-hash>/
        base.xodus/       # immutable after seal
        manifest.json     # scope, hashes, indexer version
    sessions/
      <session-id>/
        delta.xodus/      # optional overlay (future)
```

**Gitignore:** add `.kotlin-index/` to audited monorepos (CLI prints a hint on first `index`).

## Manifest

See [kotlin-code-index-core.md](../.plans/kotlin-code-index-core.md). Skip rebuild when commit, scope, sources hash, and indexer version match.

## Key namespaces

| Prefix | Owner | Purpose |
|--------|-------|---------|
| `sym:` | Core | Kotlin/Java symbol definitions |
| `ref:` | Core | Kotlin/Java calls/imports and XML resource references |
| `res:` | Core | Android XML resources by type and name |
| `file:` | Core | Path + content hash |
| `compose:` | selection-context | Selection site facts |
| `meta:` | Core | Indexer metadata |

All keys via `CodeIndexKey` — no ad hoc concatenation.

Definitions use location-qualified keys so overloads and duplicate resource configurations do
not overwrite one another:

```text
sym:<language-neutral-id>:<relative-file>:<line>:<column>
ref:<target-id>:<relative-file>:<line>:<column>
res:<type>:<name>:<relative-file>:<line>
```

`SymbolRecord.fqn` is the stable lookup identity. Types use `package.Type`; members use
`package.Type#member`; local Android resources use `res:type:name`. References to resources from
another package retain that namespace as `res:package:type:name` (for example,
`res:android:color:white`) so they cannot be mistaken for same-named local resources. Callable records retain source
signature and arity metadata while their persisted key preserves every overload.

`ReferenceRecord` stores the source language, referenced name, source qualifier, call arity, and
candidate language-neutral target IDs. This lets clients reconstruct Java-to-Kotlin and
Kotlin-to-Java edges without requiring classpath attribution. Syntactically recoverable receiver
types (parameters, locals, constructors, imports, and package-local types) produce the same
`Owner#member` identity in both language producers.

## Xodus

Embedded store (v2.0.1 via `xodus-environment`); `prefixScan` for preset queries. Dependency in Core C0:

```kotlin
// gradle/libs.versions.toml
xodus = "2.0.1"
// build.gradle.kts
implementation(libs.xodus.environment)
```

Store opens use a bounded 30-second Xodus log-lock wait. Concurrent CLI processes therefore
serialize briefly at the environment boundary instead of failing immediately when agent tools
query the same base index at once.

## Default path constant

```kotlin
object IndexPaths {
    const val STORE_DIR_NAME = ".kotlin-index"
}
```

## Query path

1. Resolve manifest for commit + scope
2. Open `base.xodus` read-only
3. Application scans keys

## Invalidation

| Event | Action |
|-------|--------|
| New `git commit` | new `index/<hash>/` |
| File edit | re-run producers for changed files |
| Scope change | rebuild with new manifest |
| Indexer version bump | rebuild |

## Deprecated paths

- `.compose-selection-index/` — early sketch, do not use
- `.agent/` — upstream #814 name only; not used by this CLI unless `--store-dir .agent` added later
