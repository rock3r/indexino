# kotlin-code-index

> **Experimental** — APIs, index layout, and CLI contracts may change without notice.

Standalone Kotlin CLI that builds a **persistent** local code index (Xodus under
`<workspace>/.kotlin-index/index/<commit>/`) for agent audit tools. Detekt-independent,
Bazel-first (Gradle secondary), ships as a fat JAR with no target-repo build coupling.

**selection-context** is the first application plugin: precomputed SelectionContainer /
DisableSelection facts at composable call sites for Compose/Jewel UI audits.

Licensed under the [Universal Ethical License (UEL) v1.0](https://uelicense.eu/) — see
[LICENSE](LICENSE).

## Quick start

Build the fat JAR:

```bash
./gradlew shadowJar
# → build/libs/kotlin-code-index-0.1.0-SNAPSHOT-all.jar
```

Run via Gradle during development, or invoke the JAR directly:

```bash
JAR=build/libs/kotlin-code-index-0.1.0-SNAPSHOT-all.jar

# Build or refresh the index for a Bazel target
java -jar "$JAR" index \
  --project /path/to/monorepo \
  --bazel-target //plugins/foo/ui:ui \
  --applications selection-context

# Query precomputed selection-context facts
java -jar "$JAR" query \
  --project /path/to/monorepo \
  --application selection-context \
  --preset interactive-in-sc \
  --format jsonl
```

Equivalent Gradle invocations:

```bash
./gradlew run --args="index --project /path/to/monorepo --bazel-target //pkg:ui"
./gradlew run --args="query --project /path/to/monorepo --application selection-context --preset interactive-in-sc --format jsonl"
```

Run tests:

```bash
./gradlew check
```

## Docs

| Doc | Topic |
|-----|--------|
| [docs/CLI.md](docs/CLI.md) | Commands, flags, JSONL schema |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layers |
| [docs/INDEX-STORAGE.md](docs/INDEX-STORAGE.md) | `.kotlin-index/` + keys |
| [AGENTS.md](AGENTS.md) | Agent rules |

## Status

Core **C0–C1** and application **A1–A3** milestones implemented on branch
`feat/core-c0-a1`. See [docs/CLI.md](docs/CLI.md) for full command reference.
