# Conventions

## Code Style

- **No wildcard imports**
- **Line length ≤ 120 characters**
- **No magic numbers** — named constants for PSI depth limits, cache TTLs, etc.
- **Exception handling** — catch at CLI boundaries; return typed exit codes. Do not swallow
  exceptions in analysis core without attaching file/line context.
- **Method focus** — extract helpers when branching accumulates; keep walker pure where possible.

## File Placement

Until the multi-module split lands, packages still live under a single source root. **Logical**
placement (and target modules) follows the accepted public API design:

| What | Package / path | Target artifact |
|------|----------------|-----------------|
| Shared public models, IDs, `BasicFactQueries`, `PluginFactValue`, `@IndexinoInternalApi` | `…model` | `indexino-model` |
| Client facade (`Indexino`, refresh, snapshot) | `…api` | `indexino` |
| Plugin SPI (providers, contexts, fact sink) | `…plugin.api` | `indexino-plugin-api` |
| Selection-context plugin | `…plugin.selection` | `indexino-selection-context` |
| Compose decoration plugin | `…plugin.compose.decoration` | `indexino-compose-decoration` |
| Script host + `ScriptFinding` | `…script` | `indexino-script-host` |
| Workspace runtime, refresh registry, pack cache | `…engine` | internal (impl of `indexino`) |
| CLI entry + subcommands (daemon, cache, index, query) | `…cli` | CLI fat/shrunk only |
| Topology (Bazel, Gradle) | `…topology` | internal |
| Producers / core keys / Xodus adapters | `…producer`, `…core` | internal |
| Kotlin PSI bootstrap | `…parse` | internal |
| Detekt rules (equality, no data class) | `:detekt-plugin` | build-only |
| Fixture snippets | `src/test/resources/fixtures/` | tests |
| Fixture-driven tests | `src/test/kotlin/…` | tests |

### Public API rules (when adding public types)

- Prefer **final classes** + private ctor + `@JvmStatic of` + structural `equals`/`hashCode`/`toString`.
- **No** public data classes; **no** public `@JvmInline` value classes.
- Host-only constructors: public + `@IndexinoInternalApi` (in `indexino-model`), never cross-module
  `internal constructor`.
- Property-level equality opt-out: `@ExcludeFromEquality` (in `indexino-model`).
- Shared types go in **`indexino-model`**, not in `api` or `plugin.api`, to avoid dependency cycles.
- Do not put new public types under `core/` “because that’s where records live.”

See [PUBLIC-API-DESIGN.html](PUBLIC-API-DESIGN.html) and [API-STABILITY.md](API-STABILITY.md).

## Git Workflow

- Do not push directly to `main` without confirming with the user.
- Run `./gradlew check` before push or PR.
- Branch names: `feat/bazel-topology`, `fix/walker-disable-selection`.
- Commit messages: imperative subject, no conventional-commit prefixes (`feat:`, `fix:`).
- Reference issues in PR descriptions when applicable.
- Plans live in the **root checkout** `.plans/` (gitignored), not only in a worktree.

## Pre-Push Checks

```bash
./gradlew test
./gradlew check          # tests + detekt + ktfmt (+ Metalava when public API exists)
./gradlew ktfmtFormat    # apply ktfmt to main, test, and Gradle scripts
```

Add format/lint tasks here when introduced; `./gradlew check` runs tests, detekt, and ktfmt.
