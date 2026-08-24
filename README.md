![Indexino animated banner](docs/assets/indexino-banner.webp)

[![check](https://github.com/rock3r/indexino/actions/workflows/check.yml/badge.svg)](https://github.com/rock3r/indexino/actions/workflows/check.yml)

> **Pre-release** — the embedded API is published as an experimental 0.x release train. Its
> public artifacts are compatibility-gated, while APIs explicitly marked experimental may change in
> a minor release. CLI and storage contracts remain pre-release.

> **Personal experiment only** — Indexino is a personal experimentation project, not a product or
> supported tool. It is not production-ready and carries no commitment to stability, maintenance,
> security fixes, compatibility, releases, or fitness for any use. Do not depend on it for
> important work; it may change radically or be abandoned without notice.

Standalone Kotlin CLI and embeddable library that builds a **persistent** local code index in a
user-local content-addressed cache (never under the target worktree) for agent audit tools.
Detekt-independent, Bazel-first (Gradle secondary), and ships as a fat compatibility JAR with no
target-repo build coupling. A separate R8-shrunk JAR is the internal native-distribution input.

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
# → build/libs/indexino-0.3.0-SNAPSHOT-all.jar
```

Build and verify the internal R8 native-packaging input:

```bash
./gradlew shrunkCliJar verifyShrunkCli
# → build/libs/indexino-0.3.0-SNAPSHOT-shrunk.jar
```

Run via Gradle during development, or invoke the JAR directly:

```bash
JAR=build/libs/indexino-0.3.0-SNAPSHOT-all.jar

# Build or refresh the index for a Bazel target
java -jar "$JAR" index \
  --project /path/to/monorepo \
  --bazel-target //plugins/foo/ui:ui \
  --applications dev.sebastiano.selection-context

# Query precomputed selection-context facts
java -jar "$JAR" query \
  --project /path/to/monorepo \
  --application dev.sebastiano.selection-context \
  --preset interactive-in-selection \
  --format jsonl

# Language-neutral symbol and reference lookups
java -jar "$JAR" find-symbol --project /path/to/repo --name Panel
java -jar "$JAR" find-references --project /path/to/repo --symbol 'sample.Panel#render'

# Android XML resource lookup
java -jar "$JAR" resolve-resource --project /path/to/repo --type string --name title
```

Equivalent Gradle invocations:

```bash
./gradlew run --args="index --project /path/to/monorepo --bazel-target //pkg:ui --applications dev.sebastiano.selection-context"
./gradlew run --args="query --project /path/to/monorepo --application dev.sebastiano.selection-context --preset interactive-in-selection --format jsonl"
```

Run tests:

```bash
./gradlew check
```

Build and verify a native distribution on its matching host:

```bash
./gradlew verifyNativeDistributionLinuxX64 sha256NativeDistributionLinuxX64
# macOS arm64 and Windows x64 use the corresponding MacArm64 / WindowsX64 suffix.
```

The resulting ZIP and `.sha256` file are under `build/distributions/`. Native packages bundle JBR
25 and the application, but still expect repository tools such as Git and the selected Bazel or
Gradle tooling on `PATH`. See [docs/DISTRIBUTIONS.md](docs/DISTRIBUTIONS.md) for installation,
supported baselines, AOT fallback, and current pre-release restrictions.

## Maven publication

The embedded API is published as a compatibility-gated 0.x release train. Use the BOM to keep the
facade, model, plugin SPI, and selection-context plugin aligned:

```kotlin
dependencies {
    implementation(platform("dev.sebastiano.indexino:indexino-bom:<version>"))
    implementation("dev.sebastiano.indexino:indexino")
    implementation("dev.sebastiano.indexino:indexino-selection-context")
}
```

`indexino-model`, `indexino`, `indexino-plugin-api`, and `indexino-selection-context` are thin
artifacts. See [docs/API-STABILITY.md](docs/API-STABILITY.md) for the supported packages,
experimental-status policy, and compatibility gates.

The Shadow `*-all.jar` remains the standalone distribution for direct `java -jar` use. The
`*-shrunk.jar` is not a Maven artifact. See [docs/PUBLISHING.md](docs/PUBLISHING.md) for local
publication verification and the release flow.

## Docs

| Doc | Topic |
|-----|--------|
| [docs/CLI.md](docs/CLI.md) | Commands, flags, JSONL schema |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layers |
| [docs/INDEX-STORAGE.md](docs/INDEX-STORAGE.md) | User-local cache + keys |
| [docs/API-STABILITY.md](docs/API-STABILITY.md) | Public API boundary and compatibility gates |
| [docs/PLUGIN-AUTHORING.md](docs/PLUGIN-AUTHORING.md) | Compiled plugin ABI and manifest contract |
| [docs/PUBLISHING.md](docs/PUBLISHING.md) | Maven coordinates and release flow |
| [docs/DISTRIBUTIONS.md](docs/DISTRIBUTIONS.md) | Native installation, support, and release gates |
| [AGENTS.md](AGENTS.md) | Agent rules |

## Contributing

After the initial GitHub import, all changes go through **pull request → CI babysit →
squash merge** cycles. See [CONTRIBUTING.md](CONTRIBUTING.md) for the full workflow,
local checks, and agent conventions.

## Status

Core **C0–C1** and application **A1–A3** milestones implemented. See
[docs/CLI.md](docs/CLI.md) for full command reference.
