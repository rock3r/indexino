# Gradle Topology (Phase G)

Secondary project-discovery backend for Gradle-based Compose/Jewel repos (plugin projects,
smaller open-source trees). **Bazel remains primary** for IntelliJ Platform and Android Studio
monorepos.

## Detection

Auto-select Gradle when no `MODULE.bazel` / `WORKSPACE` at `--project` root and
`settings.gradle(.kts)` exists. Override with `--build-system gradle`.

## Module discovery

1. Parse `settings.gradle.kts` / `settings.gradle` for `include(":foo", …)` and
   `include(project(":bar"))` forms
2. Map `--gradle-module :foo:ui` to indexable files under `src/`:
   - Kotlin source sets (`*/kotlin/**/*.kt`)
   - Java source sets (`*/java/**/*.java`)
   - Android resource trees (`*/res/**/*.xml`)

`.idea/modules.xml` / `*.iml` parsing is deferred; filesystem walk covers conventional layouts.

## Dependency closure

- Parse `build.gradle.kts` / `build.gradle` for `project(":…")` dependencies (in-repo only)
- `--include-deps` walks transitive project deps; Maven coordinates are ignored
- Root module `:` indexes its own conventional `src/main/kotlin`, `src/main/java`, and
  `src/main/res` sources, plus all settings-included modules

## CLI

```bash
kotlin-code-index index \
  --project . \
  --build-system gradle \
  --gradle-module :plugin:ui \
  [--include-deps] \
  [--applications selection-context]

kotlin-code-index status \
  --project . \
  --gradle-module :plugin:ui
```

## Cache

Same `.kotlin-index/` layout as Bazel; manifest `scope` holds the Gradle module path (e.g.
`:ui`); `topology` is `gradle-parse`.

## Limits

- No Gradle invocation required (parse settings/build files only)
- Groovy DSL supported for `include` and `project()` deps; no full Gradle model
- Composite builds, Android product flavors, and version-catalog-only source sets are out of
  scope for v1

Same cache format and CLI JSONL output as Bazel path; query rows include `"module"` when
indexed via Gradle.
