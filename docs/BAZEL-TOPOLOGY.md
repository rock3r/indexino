# Bazel Topology

Primary project-discovery backend for IntelliJ Platform and Android Studio monorepos.

## Detection

Auto-select Bazel when `MODULE.bazel` or `WORKSPACE` exists at `--project` root. Override with
`--build-system bazel|gradle|auto`.

## Manifest fields

Written to `<project>/.indexino/index/<commit>/manifest.json`:

| Field | Description |
|-------|-------------|
| `commit` | Git HEAD at index time |
| `indexerVersion` | indexino version string |
| `topology` | `bazel-query` or `build-parse` |
| `scope` | Bazel target label |
| `includeDeps` | Whether dependency closure was included |
| `sourceFileCount` | Number of Kotlin, Java, and XML files indexed |
| `sourcesContentHash` | Combined SHA-256 of indexed sources |
| `builtAt` | ISO-8601 timestamp |
| `applications` | Application producer ids run (e.g. `selection-context`) |

## `.bazelproject`

When present (Android Studio with Bazel, IntelliJ Bazel plugin), read:

- `directories:` — bound query scope
- `targets:` — default targets when user omits `--bazel-target`

Prefer these over whole-repo scans.

## Source Closure

**Primary path** (requires `bazel` on PATH) follows the requested scope:

```bash
# Target-only source set (`TopologyRequest.includeDeps = false`)
bazel query \
  "kind('source file', deps(labels(srcs, //plugins/foo/ui:ui))) union \
   kind('source file', deps(labels(resource_files, //plugins/foo/ui:ui)))" \
  --output=label

# Dependency source closure (`TopologyRequest.includeDeps = true`)
bazel query "kind('source file', deps(//plugins/foo/ui:ui))" --output=label \
  | rg '\.(kt|java|xml)$' | rg '^//'
```

The inner `labels` calls keep the scope on the target's direct source/resource attributes; the
surrounding `deps` calls expand filegroups and other source aggregators named by those attributes.
This is not the target's build dependency closure.

Flags:

- Embedded `IndexScope.bazel(target)` is target-only;
  `.includingDependencies()` requests the dependency source closure.
- The CLI preserves its historical effective Bazel default: `index` and an explicitly scoped
  `status` include the dependency closure even when `--include-deps` is omitted. The flag remains
  accepted for cross-backend command compatibility.
- `--exclude-test-targets` — skip `testonly` targets (default: exclude)

`TopologyResult.includeDeps` and the generation/compatibility manifests record the closure that
was actually observed, not merely the requested flag. A fallback from a requested dependency
closure to target-only sources therefore records `includeDeps = false`; freshness comparison can
then detect that the published closure does not satisfy a dependency-inclusive scope.

## Test Target Filtering

Exclude targets marked `testonly = True` and paths matching `*test*`, `*testSrc*`, `testData/`
unless `--include-tests`.

## Degraded Mode

When Bazel is unavailable (default CI path uses mock query fixtures instead):

1. Parse `BUILD` / `BUILD.bazel` under the target package directory
2. Select the requested rule by its `name`; fail rather than indexing sibling rules when absent
3. Recursively retain local rules referenced from that rule's `srcs` or `resource_files` (such as
   source `filegroup`s), without admitting unrelated sibling targets
4. Recognize Kotlin/Java files in `srcs` and XML in Android `resource_files`
5. Expand literal entries and `glob([...])` patterns into workspace-relative paths
6. Set manifest `topology` to `build-parse`

When a dependency-closure query fails (for example in a partial checkout), Indexino retries with
the same target-only source/resource query. When that query itself fails, Indexino retries with
BUILD parsing. Progress and BUILD-parse warnings go to stderr. Manifest `includeDeps` is `false`
for either target-only fallback and for `build-parse` degraded mode.

## Test fixtures

CI tests under `src/test/resources/fixtures/bazel/` provide:

- `.bazelproject` — directory/target parsing golden
- `mock-query-output.txt` — simulated `bazel query` label output
- `plugins/foo/ui/BUILD.bazel` — degraded-mode BUILD snippet

No live monorepo or `bazel` binary required in default `./gradlew test`.

## Cache

Store under `.indexino/` (gitignored):

- Key: `(bazel-target, include-deps, hash of BUILD files in closure, per-file content hash)`
- Invalidate when BUILD subtree hash changes or indexed `.kt` content changes

## Gradle Fallback

See [ARCHITECTURE.md](ARCHITECTURE.md). Used when no Bazel workspace markers exist.
