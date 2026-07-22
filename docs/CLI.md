# CLI

Commands for **indexino**. Persistent store lives at `<project>/.indexino/index/<commit>/`.

Examples use `indexino` as the command name. For a native ZIP, that means
`/path/to/indexino/indexino` on Linux/macOS or `C:\path\to\indexino\indexino.exe` on Windows. The
installation may be outside the caller directory; keep its bundled `runtime/`, `indexino-cli.jar`,
and AOT cache beside the launcher. See [DISTRIBUTIONS.md](DISTRIBUTIONS.md).

## Commands

### `index`

Build or refresh the persistent base index for a scope.

```bash
indexino index \
  --project /path/to/monorepo \
  --bazel-target //plugins/foo/ui:ui \
  [--applications selection-context]
```

Gradle-backed repos (no Bazel at project root):

```bash
indexino index \
  --project /path/to/gradle-repo \
  --build-system gradle \
  --gradle-module :plugin:ui \
  [--include-deps] \
  [--applications selection-context]
```

When `--build-system auto` (default), Bazel is chosen if `MODULE.bazel` / `WORKSPACE` exists;
otherwise Gradle when `settings.gradle(.kts)` is present. Pass `--bazel-target` or
`--gradle-module` for the scope.

On first run, resolves `git rev-parse HEAD` in `--project`, discovers Kotlin, Java, and Android XML sources via Bazel
query (with `labels(srcs, …)` fallback when `deps()` fails on partial checkouts), BUILD-file
parse when `bazel` is unavailable, opens `<project>/.indexino/index/<commit>/base.xodus`,
runs core `FileHashProducer` plus any requested application producers, and writes `manifest.json`.
Core producers always build Kotlin/Java symbols, cross-language references, and XML resources;
`--applications` selects additional application facts such as `selection-context`.

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

```bash
indexino status --project /path/to/monorepo [--bazel-target //pkg:ui]
indexino status --project /path/to/gradle-repo --gradle-module :ui
```

When scope flags are omitted, freshness is checked against the scope stored in the manifest
(Bazel target or Gradle module path).

### Session overlay

Query with `--session-id <id>` reads base index plus session delta at
`.indexino/sessions/<id>/delta.xodus` (delta overrides base keys).

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

Read precomputed facts from the store (fast).

```bash
# Application: selection-context
indexino query \
  --project /path/to/monorepo \
  --application selection-context \
  --preset interactive-in-sc \
  --format jsonl

# Point query (single site)
indexino query \
  --project /path/to/monorepo \
  --application selection-context \
  --file plugins/foo/ui/src/.../Panel.kt \
  --line 142 \
  [--column 8]
```

Point queries read `compose:selection-site:{file}:{line}:{column}` from the store for the
current git commit. When multiple call sites share a line, pass `--column` to disambiguate.

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
| `--application` | Query plugin, e.g. `selection-context` |
| `--preset` | Application preset, e.g. `interactive-in-sc` |
| `--file` | Point query: relative source path |
| `--line` | Point query: 1-based line number |
| `--column` | Point query: optional 1-based column disambiguator |
| `--session-id` | Optional session delta overlay for query |

## JSONL Row Schema

One JSON object per line:

```json
{
  "target": "//plugins/foo/ui:ui",
  "file": "plugins/foo/ui/src/.../Panel.kt",
  "line": 142,
  "column": 8,
  "callee": "ActionButton",
  "inSelectionContainer": true,
  "selectionContainerCount": 1,
  "excludedByDisableSelection": false,
  "selectionContainers": [
    {"file": "plugins/foo/ui/src/.../Panel.kt", "line": 138, "function": "ToolWindowPanel"}
  ],
  "disableSelection": null,
  "confidence": "lexical",
  "topology": "bazel-query"
}
```

Gradle-scoped indexes add `"module": ":plugin:ui"` when `topology` starts with `gradle`.

Field notes:

- `selectionContainers` — innermost first
- `excludedByDisableSelection` — `true` when `DisableSelection` sits between the call site and
  the nearest ancestor SC
- `confidence` — `lexical` | `caller-chain` (future)
- `topology` — `bazel-query` | `build-parse` | `gradle-parse` | `idea`

## Presets (initial)

| Preset | Intent |
|--------|--------|
| `interactive-in-sc` | Interactive composables inside SC without `DisableSelection` |
| `nested-selection-container` | `selectionContainerCount > 1` |
| `all-call-sites` | Every `@Composable` call site with context (no policy filter) |

Preset callee lists live in `config/presets/` (e.g. `interactive-in-sc.json`); the CLI applies
structural filters only unless `--preset-config` points at a JSON file.

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Analysis / query error |
| 2 | Invalid arguments |
| 3 | Topology discovery failed |
