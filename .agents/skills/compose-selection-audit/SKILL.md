---
name: compose-selection-audit
description: >
  Use when auditing Compose/Jewel UI for SelectionContainer context: interactive composables
  inside a SelectionContainer without DisableSelection, nested selection containers, or missing
  exclusion wrappers. Uses kotlin-code-index (selection-context application) for precomputed
  facts from a persistent `.kotlin-index/` index — not manual file reading. Bazel monorepos primary;
  Gradle secondary.
---

# Compose Selection Audit

Uses **kotlin-code-index** with `--application selection-context`. The skill applies **policy**;
the index supplies **facts** from `.kotlin-index/index/<commit>/` (persistent — build once, query many).

## Prerequisites

- Built fat JAR: `./gradlew shadowJar` → `build/libs/kotlin-code-index-*-all.jar`
- Target repo path (Bazel monorepo root or Gradle project root)
- Scope: `--bazel-target` (Bazel) or `--gradle-module` (Gradle)

## Quick workflow (Bazel)

1. **Scope** — leaf UI Bazel target (e.g. `//plugins/foo/ui:ui`).

2. **Status** — check whether index exists and is fresh:

   ```bash
   java -jar /path/to/kotlin-code-index-*-all.jar status \
     --project /path/to/target-repo \
     --bazel-target //plugins/foo/ui:ui
   ```

   JSON output includes `"fresh": true|false`. Run `index` when missing or stale.

3. **Index** (once per commit/scope — skipped automatically when manifest is fresh):

   ```bash
   java -jar /path/to/kotlin-code-index-*-all.jar index \
     --project /path/to/target-repo \
     --bazel-target //plugins/foo/ui:ui \
     --applications selection-context
   ```

4. **Query** (fast — reads Xodus, no re-parse):

   ```bash
   java -jar /path/to/kotlin-code-index-*-all.jar query \
     --project /path/to/target-repo \
     --application selection-context \
     --preset interactive-in-sc \
     --format jsonl
   ```

5. **Point query** for a diff line:

   ```bash
   java -jar /path/to/kotlin-code-index-*-all.jar query \
     --project /path/to/target-repo \
     --application selection-context \
     --file relative/path/Panel.kt \
     --line 142 \
     --format jsonl
   ```

6. **Session overlay** (optional — reads base + delta when agent edited files in-session):

   ```bash
   java -jar /path/to/kotlin-code-index-*-all.jar query \
     --project /path/to/target-repo \
     --application selection-context \
     --preset all-call-sites \
     --session-id my-session \
     --format jsonl
   ```

## Quick workflow (Gradle)

For Gradle-only plugin repos (no Bazel at root):

1. **Scope** — Gradle module path (e.g. `:plugin:ui`).

2. **Index**:

   ```bash
   java -jar /path/to/kotlin-code-index-*-all.jar index \
     --project /path/to/gradle-repo \
     --build-system gradle \
     --gradle-module :plugin:ui \
     --include-deps \
     --applications selection-context
   ```

3. **Query** — same `query` commands as Bazel; JSONL rows include `"module"` when topology is
   `gradle-parse`.

## Policy table

| Callee kind | `inSelectionContainer` | `excludedByDisableSelection` | Verdict |
|-------------|------------------------|------------------------------|---------|
| ActionButton, IconButton, clickable Row/Box | true | false | **Violation** |
| Text, Markdown (selectable) | true | — | OK (informational) |
| Interactive control | true | true | OK |
| Any | true, `selectionContainerCount > 1` | — | **Warning** |

## Output shape

```markdown
## Selection container findings
1. [high] ActionButton in SC without DisableSelection — `Panel.kt:142`

## Index
- store: `.kotlin-index/index/<commit>/`
- application: selection-context
- confidence: lexical | caller-chain (known wrappers / lambda-origin)
```

## Rules

- **Always** run `status` or `index` before batch `query` — never walk source for SC facts when index rows exist
- Second `index` with unchanged sources is a no-op (skip-if-fresh)
- Add `.kotlin-index/` to target repo `.gitignore` if not present
- Preset callee lists: bundled `config/presets/interactive-in-sc.json`
- Known wrappers (e.g. `Markdown(selectable=true)`): `config/presets/known-wrappers.json`

## Integration

Run before broader `compose-ui-audit` for token efficiency.

Repository: https://github.com/rock3r/kotlin-index (experimental, UEL)
