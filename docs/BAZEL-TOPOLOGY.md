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
bazel query "kind('alias rule', //plugins/foo/ui:ui)" --output=label
bazel query \
  "kind('source file', labels(srcs, //plugins/foo/ui:ui)) union \
   kind('source file', labels(resources, //plugins/foo/ui:ui)) union \
   kind('source file', labels(resource_files, //plugins/foo/ui:ui)) union \
   kind('source file', labels(data, //plugins/foo/ui:ui))" \
  --output=label

# Discover direct source aggregators and aliases; repeat both queries for returned labels.
bazel query \
  "kind('filegroup rule', labels(srcs, //plugins/foo/ui:ui)) union \
   kind('alias rule', labels(srcs, //plugins/foo/ui:ui)) union \
   kind('filegroup rule', labels(resource_files, //plugins/foo/ui:ui)) union \
   kind('alias rule', labels(resource_files, //plugins/foo/ui:ui))" \
  --output=label

# Dependency source closure (`TopologyRequest.includeDeps = true`)
bazel query "kind('source file', deps(//plugins/foo/ui:ui))" --output=label \
  | rg '\.(kt|java|xml)$' | rg '^//'
```

Each node is classified first. Normal rules follow only `srcs`, `resources`, `resource_files`, and
`data`; alias rules
collect a direct source from `actual` or enqueue the `actual` rule for normal processing. The
traversal never applies `deps()`, so tools and other dependencies of generating rules cannot
broaden the index.

Topology also returns optional exact code membership in `codeSourceFiles`, relative to the same
root as `sourceFiles`. A non-null set is derived only from `srcs` BUILD roles; resource/data roles
never become code because of an extension or directory name, while a file present in both roles
remains code. Target-only traversal follows `srcs` filegroups and aliases independently for this
classification. Dependency classification seeds `srcs` from compilation rules in the closure,
excluding standalone filegroup and alias roots, then follows only `srcs`/`actual` aggregator edges
in batched waves. An explicitly scoped filegroup or alias is still a valid code root. This prevents
the `srcs` inside a resource-only filegroup from becoming code while retaining the complete
`deps(target)` inventory. If any role query fails, capture remains available but membership is
`null` (unknown), never an empty set inferred from failure. Legacy injected query executors likewise
return unknown because they provide no role evidence.

Source capture excludes directory labels, including resource-directory entries that Bazel calls
`source file`. It does not recursively expand those labels; only explicitly discovered file paths
enter the capture. Kotlin, Java and XML files and files below recognized resource directories are
retained. Missing or unreadable required files still fail capture rather than silently publishing
a truncated generation.

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
3. Recursively retain local rules referenced from that rule's `srcs`, `resources`, `resource_files`,
   or `data` (such as source `filegroup`s), preserving the incoming role through filegroups and
   alias chains without admitting unrelated sibling targets
4. Recognize captured indexable files in those attributes and classify only `srcs` members as code
5. Expand literal entries and `glob([...])` patterns into workspace-relative paths
6. Set manifest `topology` to `build-parse`

When a dependency-closure query fails (for example in a partial checkout), Indexino retries with
the same target-only source/resource query. When that query itself fails, Indexino retries with
BUILD parsing. Progress and BUILD-parse warnings go to stderr. Manifest `includeDeps` is `false`
for either target-only fallback and for `build-parse` degraded mode.

## Explicit refresh stop and process ownership

The availability probe runs `bazel version` in the requested workspace with a ten-second wait
deadline. A missing executable, nonzero exit, or expired probe permits degraded discovery.
Interruption does not: it propagates and prevents another query or BUILD-parse fallback.
Queries have no new wall-clock limit; explicit refresh stop interrupts their wait.

Query and probe output is captured in a temporary file rather than drained synchronously from a
pipe. On interruption or timeout, Indexino terminates only the directly launched client, waits up
to two seconds, then forcibly terminates it and waits up to two more seconds. Interrupt status is
preserved. Failure to confirm exit throws the internal `BazelClientCleanupException`, retaining
the original command failure as its cause when present. It is neither unavailability nor ordinary
cancellation. Capture files are removed after cleanup, or scheduled for deletion at JVM exit if a
remaining open handle prevents immediate removal; deletion must not mask the cleanup failure.

The refresh coordinator retains the active refresh while its worker unwinds. Equal requests join
that same stopping refresh; `RefreshStopped` and result cancellation occur only after the worker's
cleanup returns. Cancelling an `await()` observer or event collector does not request stop.
The internal `commitIfActive` boundary serializes explicit stop with generation publication: a stop
that wins prevents publication; successful publication records commitment before releasing the lock,
making later stop calls no-ops. Result completion follows cleanup using `publishIfActive`, so a
committed generation cannot later be reported as cancelled. Neither boundary is a separate check
followed by an unguarded write.
For a client cleanup failure, the facade instead maps the typed cause and calls
`failAfterCleanup(IndexinoException)`. The coordinator stages this failure under its state lock and
prefers it over stopped when completing both result and terminal event after the worker unwinds.
An unsuccessful termination attempt is never evidence of process quiescence.

**This is direct-client cleanup, not complete process-tree cancellation.** Normal Bazel servers are
shared and intentionally remain running. Indexino does not traverse descendants, kill a global
Bazel server, or silently switch to `--batch`. In particular, Windows Bazelisk launches a separate
Bazel client which can survive termination of its wrapper. Wrapper-specific containment and the
public acceptance supervisor's independent process-containment gate remain required; these unit
tests do not establish either. The synthetic JVM fixtures launch no Bazel server and verify direct
client exit before the stopped terminal, preservation of an unrelated process, deadline cleanup,
interruption propagation, and stop/publication ordering.

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
