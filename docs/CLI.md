# CLI

Commands for **indexino**. The CLI is an adapter over the workspace runtime and public API
described in [PUBLIC-API-DESIGN.html](PUBLIC-API-DESIGN.html) (Accepted 2026-07-25).

## Storage (product vs transitional)

| | Product contract | Transitional (current shipping CLI) |
|--|------------------|-------------------------------------|
| Location | User-local cache root (`$INDEXINO_CACHE_DIR` / XDG / `~/Library/Caches/indexino`) | May still open `<project>/.indexino/index/<commit>/` until S2 |
| Migration | Non-goal — wipe and rebuild | Do not extend the in-worktree layout |
| Details | [INDEX-STORAGE.md](INDEX-STORAGE.md) | Implementation debt only |

Do **not** document new features as depending on project-local `.indexino/` stores.

## Lifecycle commands (product surface)

These map to the embedded API; land with slices S6–S7 (daemon) and cache operators.

### `daemon`

```bash
indexino daemon start  --project /path/to/repo [--no-auto-refresh]
indexino daemon status --project /path/to/repo
indexino daemon stop   --project /path/to/repo [--purge]
indexino daemon run    --project /path/to/repo [--no-auto-refresh] # foreground owner; used by auto-spawn
```

| Command | Maps to | Notes |
|---------|---------|--------|
| `start` / `run` | Runtime attach / owner process | One AF_UNIX runtime per workspace identity |
| `status` | Lease + liveness | Reports tombstones if the workspace was lost |
| `stop` | `shutdownRuntime()` | Explicit shared shutdown — not client disconnect. `--purge` drops reclaimable cache for this workspace |

Before deleting or moving a daemon-owned worktree, run `daemon stop`. Deleting the bound workspace
while the daemon is alive yields terminal **`WORKSPACE_LOST`** (loud diagnostic + external tombstone).

### `cache` (operators only)

```bash
indexino cache status [--project /path/to/repo]
indexino cache gc
indexino cache forget --project /path/to/repo
```

Not an agent-query API. Reclamation policy: [INDEX-STORAGE.md](INDEX-STORAGE.md).

### Shared flags (product)

| Flag | Meaning |
|------|---------|
| `--no-auto-refresh` | Launches the workspace runtime with `AutoRefreshMode.DISABLED`; explicit `index` still refreshes. A live runtime keeps its existing mode. |
| `--plugin /path/to.jar` | Explicit trusted plugin JAR (repeatable); maps to `withPlugin` |
| `--project` | Workspace root (identity binding) |

Examples use `indexino` as the command name. For a native ZIP, that means
`/path/to/indexino/indexino` on Linux/macOS or `C:\path\to\indexino\indexino.exe` on Windows. The
installation may be outside the caller directory; keep its bundled `runtime/`, `indexino-cli.jar`,
and AOT cache beside the launcher. See [DISTRIBUTIONS.md](DISTRIBUTIONS.md).

## Commands

### `index`

Build or refresh the persistent base index for a scope. Product mapping:
`refresh(RefreshRequest)` + await on the workspace runtime (joins an existing daemon when present).

```bash
indexino index \
  --project /path/to/monorepo \
  --bazel-target //plugins/foo/ui:ui \
  [--applications dev.sebastiano.selection-context] \
  [--plugin /path/to/extra.jar] \
  [--no-auto-refresh]
```

Gradle-backed repos (no Bazel at project root):

```bash
indexino index \
  --project /path/to/gradle-repo \
  --build-system gradle \
  --gradle-module :plugin:ui \
  [--include-deps] \
  [--applications dev.sebastiano.selection-context]
```

When `--build-system auto` (default), Bazel is chosen if `MODULE.bazel` / `WORKSPACE` exists;
otherwise Gradle when `settings.gradle(.kts)` is present. Pass `--bazel-target` or
`--gradle-module` for the scope. Embedded API scopes stay explicit (`IndexScope.bazel` /
`gradle`); CLI may keep auto-detect.

**Current shipping behaviour (transitional):** resolves `git rev-parse HEAD`, discovers sources via
Bazel/Gradle, may open a commit-addressed store under the project, runs core producers plus
`--applications`. **Product behaviour:** publishes a generation under the user-local cache;
commit is provenance only. Core producers build Kotlin/Java symbols, references, and XML facts;
plugins (e.g. selection-context) are loaded explicitly or bundled in the fat JAR.

Progress lines (producer names and `[N/M] file` per source file) go to stderr.

#### Machine progress JSONL

`index` keeps that human-readable stderr output by default. Pass
`--progress-format jsonl` to additionally emit a versioned machine stream on stdout, one JSON
object per line. Stdout is otherwise unused by `index`, so a parent process can consume this
stream without parsing human stderr. `--progress-format text` is the default and emits no machine
stream. This flag is independent of the query `--format` flag.

Every JSONL event has `version: 1` and `event`. Fields are emitted in the documented order, events
are emitted in phase order, and no timestamps or global percentages are included. `currentFile` is
always a normalized, workspace-relative path using `/` separators. File-update events are emitted
for the first file, every 25th file, and the final file of each phase; their `phaseCompleted` value
may therefore advance by more than one.

| Event | Required fields | Meaning |
|-------|-----------------|---------|
| `discovery_started` | `phase: "discovery"`, `phaseTotal: null` | Source discovery has begun; its total is not known yet. |
| `discovery_completed` | `phase`, `phaseCompleted`, `phaseTotal` | Discovery resolved the source set. |
| `phase_started` | `phase`, `phaseCompleted: 0`, `phaseTotal` | A named phase has begun. `phaseTotal` is `null` only if that phase cannot determine a total. |
| `progress` | `phase`, `phaseCompleted`, `phaseTotal`, `currentFile` | Work has reached a file in that phase. Totals are phase-local, not global. |
| `phase_completed` | `phase`, `phaseCompleted`, `phaseTotal` | A phase completed, including empty phases (`0` of `0`); both counts are `null` when the phase total is unknowable. |
| `changes_detected` | `phase: "source-change-detection"`, change counters | File-change classification is available. |
| `completed` | `outcome` | Terminal success; `outcome` is `indexed` or `fresh`. |
| `failed` | `exitCode`, `message` | Terminal failure before the index command exits or rethrows its error. |

Event keys always appear in this order when present:
`version`, `event`, `phase`, `phaseCompleted`, `phaseTotal`, `currentFile`, `changedFiles`,
`unchangedFiles`, `removedFiles`, `outcome`, `exitCode`, `message`. The three counters first appear
on `changes_detected` and are repeated unchanged on subsequent events. They are non-negative and
monotonic for a build:

- `changedFiles` is the number of currently discovered source files selected for reprocessing in
  this build. A forced full rebuild counts every current source file here, even when its content is
  unchanged.
- `unchangedFiles` is the number of currently discovered source files not selected by the core
  change-driven producers. Application producers can still inspect such a file; this counter never
  claims that its work was reused or skipped.
- `removedFiles` is the number of stored file-hash records whose workspace-relative paths are absent
  from the newly discovered source set and are scheduled for cleanup.

The current phases are `source-hash-preview`, `source-change-detection`, then one phase per producer
(for example `java-source`, `kotlin-psi-symbols`, `xml-resources`, `selection-context`, and
`file-hash`). A producer's total is its own input subset, so a consumer should display it as a
phase-local fraction such as `Kotlin symbols: 109 of 182`, not as a synthetic global percentage.

Example:

```json
{"version":1,"event":"discovery_started","phase":"discovery","phaseTotal":null}
{"version":1,"event":"phase_started","phase":"java-source","phaseCompleted":0,"phaseTotal":2,"changedFiles":2,"unchangedFiles":1,"removedFiles":0}
{"version":1,"event":"progress","phase":"java-source","phaseCompleted":1,"phaseTotal":2,"currentFile":"app/src/main/java/sample/Panel.java","changedFiles":2,"unchangedFiles":1,"removedFiles":0}
{"version":1,"event":"completed","changedFiles":2,"unchangedFiles":1,"removedFiles":0,"outcome":"indexed"}
```

When this option is absent, stdout stays empty and the existing human stderr progress, exit codes,
manifest behavior, and query output formats are unchanged.

When the manifest matches the current commit, scope, indexer version, source hash, and
applications list, the command prints `index fresh … — skip rebuild` and exits without
re-running producers.

### PSI bootstrap (fat JAR)

Kotlin PSI (`SelectionContextProducer` and future producers) requires IntelliJ Platform home
paths. The shadow JAR bundles a minimal `idea-home/` under `src/main/resources/` and sets
`-Didea.home.path`, `-Didea.config.path`, `-Didea.system.path`, and `-Didea.plugins.path`
automatically on first run (extracted to `~/.indexino/idea-home/`). Override by passing JVM
flags before `-jar`:

```bash
java -Didea.home.path=/path/to/idea/home -jar indexino-all.jar index ...
```

First run on a large repo may take minutes; subsequent queries read Xodus.

### `status`

Cheap product mapping: manifest + last known freshness (not a full rehash by default).

```bash
indexino status --project /path/to/monorepo [--bazel-target //pkg:ui]
indexino status --project /path/to/gradle-repo --gradle-module :ui
```

When scope flags are omitted, freshness is checked against the scope and `includeDeps` stored in the
generation manifest (whether the index is still current for its own configuration). May include
reclaimable-cache hints for operators. **Transitional shipping CLI** may still re-resolve topology
and rehash — that is a bug relative to the product contract, not a feature to preserve.

### `script` (Alpha; requires script-host on the distribution)

```bash
indexino script --project /path/to/repo path/to/query.indexino.kts
```

Maps to `IndexinoScriptHost.run`. Non-suspend DSL. See design doc script contract.

### Session overlay

Query with `--session-id <id>` is a **transitional** CLI feature reading a delta store. Product
storage does not use project-local session paths; do not extend this layout.

### `find-symbol`

Find definitions by exact short name, language-neutral ID, or alias. Results are deterministic
JSONL rows and retain language, owner, signature, arity, and source location.

```bash
indexino find-symbol --project /path/to/repo --name Panel
indexino find-symbol --project /path/to/repo --name 'sample.Panel#render' --language java
```

Optional filters: `--kind`, `--language`, `--session-id`, and `--format jsonl`.

### `find-references`

Find references whose resolved or candidate target matches a language-neutral symbol ID.

```bash
indexino find-references \
  --project /path/to/repo \
  --symbol 'sample.Panel#render'
```

Rows include source qualifier, referenced name, arity, language, and candidate target IDs so a
client can reconstruct cross-language edges.

#### Lookup machine progress

`find-symbol`, `find-references`, and `resolve-resource` also accept
`--progress-format jsonl`. Their final result rows remain the existing deterministic JSONL contract
on stdout. When this option is present, version 1 lookup events are written as JSONL to stderr so a
parent can consume progress independently; without it, lookup stdout and stderr behavior is
unchanged.

| Event | Required fields | Meaning |
|-------|-----------------|---------|
| `lookup_started` | `command`, `query` | A lookup has started. `query` has the command-specific fields (`name`, optional `kind` and `language`; `symbol`; or `type` and `name`). |
| `lookup_match` | `command`, `emittedMatchCount`, `record` | One complete declaration, reference, or resource record. `emittedMatchCount` starts at 1 and increases once per emitted event. |
| `lookup_completed` | `command`, `totalMatchCount`, `durationMillis` | Terminal success after all matches were emitted. |
| `lookup_failed` | `command`, `failureReason`, `message`, `durationMillis` | Terminal failure. `failureReason` is `invalid_format`, `index_not_found`, or `lookup_error`. |

Event keys appear in this order when present: `version`, `event`, `command`, `query`,
`emittedMatchCount`, `record`, `totalMatchCount`, `durationMillis`, `failureReason`, `message`.
The protocol version is the same `version: 1` stream used by `index` progress.

Lookups collect and sort every match before emitting any `lookup_match` event. This is deliberate:
it guarantees that event records and final stdout rows have exactly the same stable order, rather
than exposing unordered store-scan hits as live results. This preserves the command-specific final
ordering: `find-symbol` sorts by FQN, then workspace-relative file and line; `find-references` and
`resolve-resource` sort by workspace-relative file and line, with reference column as the next key.
Consumers receive `lookup_started` while the scan is running, then sorted match events after the
collection completes. A zero-match lookup emits `lookup_started` followed by `lookup_completed`
with `totalMatchCount: 0` and no `lookup_match` events.

Example stderr stream for `find-references --symbol sample.Panel#render --progress-format jsonl`:

```json
{"version":1,"event":"lookup_started","command":"find-references","query":{"symbol":"sample.Panel#render"}}
{"version":1,"event":"lookup_match","command":"find-references","emittedMatchCount":1,"record":{"type":"reference","symbolFqn":"sample.Panel#render","relativeFile":"src/App.kt","line":8,"column":5}}
{"version":1,"event":"lookup_completed","command":"find-references","totalMatchCount":1,"durationMillis":12}
```

### `resolve-resource`

Resolve Android resources by disambiguated type and name. Multiple configuration-specific
definitions (for example `values/` and `values-night/`) are returned as separate rows.

```bash
indexino resolve-resource \
  --project /path/to/repo \
  --type string \
  --name title
```

### `query`

Run a loaded plugin check against the published snapshot. The CLI passes the plugin ID and check ID
to `IndexSnapshot.runCheck`; plugin facts remain private, namespaced storage details.

```bash
# selection-context's interactive-in-selection check
indexino query \
  --project /path/to/monorepo \
  --application dev.sebastiano.selection-context \
  --preset interactive-in-selection \
  --format jsonl
```

The `--application` value is a plugin ID and `--preset` is its plugin-defined check ID. Point
queries and direct plugin-fact reads are intentionally not a core CLI contract.

## Common Flags

| Flag | Description |
|------|-------------|
| `--project` | Monorepo root (required) |
| `--build-system` | `auto`, `bazel`, `gradle` |
| `--bazel-target` | Bazel label, e.g. `//pkg:ui` |
| `--gradle-module` | Gradle path, e.g. `:foo:ui` (bonus backend) |
| `--include-deps` | Include dependency target/module sources |
| `--progress-format` | `index` / lookup commands: `text` (default) or JSONL machine progress; `index` uses stdout and lookups use stderr |
| `--format` | `jsonl`, `json`, `text` |
| `--application` | Plugin ID, e.g. `dev.sebastiano.selection-context` |
| `--preset` | Plugin-defined check ID, e.g. `interactive-in-selection` |
| `--session-id` | Optional session delta overlay for query |

## JSONL Row Schema

One JSON object per line:

```json
{
  "plugin": "dev.sebastiano.selection-context",
  "checkId": "interactive-in-selection",
  "message": "ActionButton is interactive inside SelectionContainer",
  "range": {"file": "plugins/foo/ui/src/.../Panel.kt", "line": 142, "column": 8},
  "properties": {"callee": "ActionButton", "factKey": "selection-site:142:8"}
}
```

Finding fields are stable model values. Plugin-specific fields belong in `properties`; callers must
not depend on the plugin's durable fact layout.

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Analysis / query error |
| 2 | Invalid arguments |
| 3 | Topology discovery failed |
