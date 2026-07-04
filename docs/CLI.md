# CLI

Commands for **kotlin-code-index**. Persistent store lives at `<project>/.kotlin-index/index/<commit>/`.

## Commands

### `index`

Build or refresh the persistent base index for a scope.

```bash
kotlin-code-index index \
  --project /path/to/monorepo \
  --bazel-target //plugins/foo/ui:ui \
  [--applications selection-context]
```

On first run, resolves `git rev-parse HEAD` in `--project`, discovers Kotlin sources via Bazel
query (with `labels(srcs, …)` fallback when `deps()` fails on partial checkouts), BUILD-file
parse when `bazel` is unavailable, opens `<project>/.kotlin-index/index/<commit>/base.xodus`,
runs core `FileHashProducer` plus any requested application producers, and writes `manifest.json`.

Progress lines (producer names) go to stderr.

### PSI bootstrap (fat JAR)

Kotlin PSI (`SelectionContextProducer` and future producers) requires IntelliJ Platform home
paths. The shadow JAR bundles a minimal `idea-home/` under `src/main/resources/` and sets
`-Didea.home.path`, `-Didea.config.path`, `-Didea.system.path`, and `-Didea.plugins.path`
automatically on first run (extracted to `~/.kotlin-index/idea-home/`). Override by passing JVM
flags before `-jar`:

```bash
java -Didea.home.path=/path/to/idea/home -jar kotlin-code-index-all.jar index ...
```

First run on a large repo may take minutes; subsequent queries read Xodus.

### `status`

```bash
kotlin-code-index status --project /path/to/monorepo [--bazel-target //pkg:ui]
```

Reports: commit hash, manifest age, file count, stale?, indexer version.

### `query`

Read precomputed facts from the store (fast).

```bash
# Application: selection-context
kotlin-code-index query \
  --project /path/to/monorepo \
  --application selection-context \
  --preset interactive-in-sc \
  --format jsonl

# Point query (single site)
kotlin-code-index query \
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
| `--format` | `jsonl`, `json`, `text` |
| `--application` | Query plugin, e.g. `selection-context` |
| `--preset` | Application preset, e.g. `interactive-in-sc` |
| `--file` | Point query: relative source path |
| `--line` | Point query: 1-based line number |
| `--column` | Point query: optional 1-based column disambiguator |

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

Field notes:

- `selectionContainers` — innermost first
- `excludedByDisableSelection` — `true` when `DisableSelection` sits between the call site and
  the nearest ancestor SC
- `confidence` — `lexical` | `caller-chain` (future)
- `topology` — `bazel-query` | `build-parse` | `gradle` | `idea`

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
