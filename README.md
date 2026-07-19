# kotlin-code-index

[![check](https://github.com/rock3r/kotlin-index/actions/workflows/check.yml/badge.svg)](https://github.com/rock3r/kotlin-index/actions/workflows/check.yml)

> **Experimental** — APIs, index layout, and CLI contracts may change without notice.

Standalone Kotlin CLI that builds a **persistent** local code index (Xodus under
`<workspace>/.kotlin-index/index/<commit>/`) for agent audit tools. Detekt-independent,
Bazel-first (Gradle secondary), ships as a fat JAR with no target-repo build coupling.
The same code is published as a thin Maven artifact for dependency-based consumers.

**selection-context** is the first application plugin: precomputed SelectionContainer /
DisableSelection facts at composable call sites for Compose/Jewel UI audits.

The core index also extracts Kotlin and Java declarations/references plus Android XML resources.
Java uses the JDK compiler tree API, Kotlin uses embedded PSI, and XML uses secure JDK StAX
parsing; no language-server or parser daemon is required.

Licensed under the [Unenshittifiable License (UEL) v1.0](https://uelicense.eu/) — see
[LICENSE](LICENSE).

## Quick start

Build the fat JAR:

```bash
./gradlew shadowJar
# → build/libs/kotlin-code-index-0.2.0-SNAPSHOT-all.jar
```

Run via Gradle during development, or invoke the JAR directly:

```bash
JAR=build/libs/kotlin-code-index-0.2.0-SNAPSHOT-all.jar

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

# Language-neutral symbol and reference lookups
java -jar "$JAR" find-symbol --project /path/to/repo --name Panel
java -jar "$JAR" find-references --project /path/to/repo --symbol 'sample.Panel#render'

# Android XML resource lookup
java -jar "$JAR" resolve-resource --project /path/to/repo --type string --name title
```

Equivalent Gradle invocations:

```bash
./gradlew run --args="index --project /path/to/monorepo --bazel-target //pkg:ui --applications selection-context"
./gradlew run --args="query --project /path/to/monorepo --application selection-context --preset interactive-in-sc --format jsonl"
```

Run tests:

```bash
./gradlew check
```

## Maven dependency

Consumers need no custom Gradle plugin. Use the normal Maven Central coordinates; the thin JAR's
runtime dependencies are declared transitively in its POM:

```kotlin
dependencies {
    implementation("dev.sebastiano.kotlinindex:kotlin-code-index:<version>")
}
```

The Shadow `*-all.jar` remains the standalone distribution for direct `java -jar` use. See
[docs/PUBLISHING.md](docs/PUBLISHING.md) for local publication verification and the release flow.

## Docs

| Doc | Topic |
|-----|--------|
| [docs/CLI.md](docs/CLI.md) | Commands, flags, JSONL schema |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layers |
| [docs/INDEX-STORAGE.md](docs/INDEX-STORAGE.md) | `.kotlin-index/` + keys |
| [docs/PUBLISHING.md](docs/PUBLISHING.md) | Maven coordinates and release flow |
| [AGENTS.md](AGENTS.md) | Agent rules |

## Contributing

After the initial GitHub import, all changes go through **pull request → CI babysit →
squash merge** cycles. See [CONTRIBUTING.md](CONTRIBUTING.md) for the full workflow,
local checks, and agent conventions.

## Status

Core **C0–C1** and application **A1–A3** milestones implemented. See
[docs/CLI.md](docs/CLI.md) for full command reference.
