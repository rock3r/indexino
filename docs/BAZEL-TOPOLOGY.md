# Bazel Topology

Primary project-discovery backend for IntelliJ Platform and Android Studio monorepos.

## Detection

Auto-select Bazel when `MODULE.bazel` or `WORKSPACE` exists at `--project` root. Override with
`--build-system bazel|gradle|auto`.

## Manifest fields

Written to `<project>/.kotlin-index/index/<commit>/manifest.json`:

| Field | Description |
|-------|-------------|
| `commit` | Git HEAD at index time |
| `indexerVersion` | kotlin-code-index version string |
| `topology` | `bazel-query` or `build-parse` |
| `scope` | Bazel target label |
| `includeDeps` | Whether dependency closure was included |
| `sourceFileCount` | Number of `.kt` files indexed |
| `sourcesContentHash` | Combined SHA-256 of indexed sources |
| `builtAt` | ISO-8601 timestamp |
| `applications` | Application producer ids run (e.g. `selection-context`) |

## `.bazelproject`

When present (Android Studio with Bazel, IntelliJ Bazel plugin), read:

- `directories:` — bound query scope
- `targets:` — default targets when user omits `--bazel-target`

Prefer these over whole-repo scans.

## Source Closure

**Primary path** (requires `bazel` on PATH):

```bash
# Kotlin library targets in dependency closure
bazel query "filter('kt_.*library', deps(//plugins/foo/ui:ui))" --output=label

# In-repo Kotlin sources only
bazel query "kind('source file', deps(//plugins/foo/ui:ui))" --output=label \
  | rg '\.kt$' | rg '^//'
```

Flags:

- `--include-deps` — index dependency targets' sources (shared UI libs)
- `--exclude-test-targets` — skip `testonly` targets (default: exclude)

## Test Target Filtering

Exclude targets marked `testonly = True` and paths matching `*test*`, `*testSrc*`, `testData/`
unless `--include-tests`.

## Degraded Mode

When Bazel is unavailable (default CI path uses mock query fixtures instead):

1. Parse `BUILD` / `BUILD.bazel` under the target package directory
2. Recognize `kt_jvm_library`, `kt_android_library`, `android_library` with `.kt` in `srcs`
3. Expand literal `srcs` entries and `glob([...])` patterns into workspace-relative paths
4. Set manifest `topology` to `build-parse`

When `bazel` is available but the dependency closure is incomplete (partial checkout), the CLI
retries with `labels(srcs, $target)` after `kind('source file', deps($target))` fails. Progress
and BUILD-parse warnings go to stderr. Manifest `includeDeps` is `false` for that fallback and
for `build-parse` degraded mode.

## Test fixtures

CI tests under `src/test/resources/fixtures/bazel/` provide:

- `.bazelproject` — directory/target parsing golden
- `mock-query-output.txt` — simulated `bazel query` label output
- `plugins/foo/ui/BUILD.bazel` — degraded-mode BUILD snippet

No live monorepo or `bazel` binary required in default `./gradlew test`.

## Cache

Store under `.kotlin-index/` (gitignored):

- Key: `(bazel-target, include-deps, hash of BUILD files in closure, per-file content hash)`
- Invalidate when BUILD subtree hash changes or indexed `.kt` content changes

## Gradle Fallback

See [ARCHITECTURE.md](ARCHITECTURE.md). Used when no Bazel workspace markers exist.
