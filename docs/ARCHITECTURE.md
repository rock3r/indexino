# Architecture

## Product shape

**indexino** is a portable, **persistent** local code index shipped as a standalone fat
CLI JAR, as a thin Maven artifact, and in self-contained native CLI ZIPs. The build also produces a
separate R8-shrunk CLI JAR as the input to native packaging; it does not replace either public
artifact. It is not a
SelectionContainer one-off — **selection-context** is the first **application plugin** on top of
shared storage and topology.

```
┌──────────────────┐     ┌─────────────────────────────┐
│  Audit skills    │────▶│  CLI: index / query / status │
└──────────────────┘     └──────────────┬──────────────┘
                                        │
                    ┌───────────────────┼───────────────────┐
                    ▼                   ▼                   ▼
            ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
            │ Application  │   │  Producers   │   │  Topology    │
            │ selection-   │   │  (pluggable) │   │  Bazel /     │
            │ context      │   │              │   │  Gradle      │
            └──────┬───────┘   └──────┬───────┘   └──────┬───────┘
                   │                  │                  │
                   └──────────────────┼──────────────────┘
                                      ▼
                         ┌────────────────────────┐
                         │  Xodus store           │
                         │  .indexino/index/<commit>/│
                         └────────────────────────┘
```

## Layers

| Layer | Responsibility |
|-------|----------------|
| `cli/` | Clikt commands, progress, exit codes |
| `application/` | Query plugins (`selection-context`, …) |
| `producer/` | Index build: file hashes, Kotlin PSI, JDK Java trees, secure XML/resource walks |
| `topology/` | Resolve source file set from Bazel/Gradle scope |
| `core/` | Keys, records, Xodus store, manifest, query service |

Dependency direction: `cli` → `application` + `producer` → `topology` + `core`.

## Embedded API boundary

There is no supported embedded API yet. All current layers are implementation details and their
Kotlin declarations are `internal`; the committed ABI baseline is empty. The first supported API
will be introduced deliberately under `dev.sebastiano.indexino.api`. See
[API-STABILITY.md](API-STABILITY.md).

## Persistence (why it exists)

Large Bazel monorepos may take **minutes** to index. The store under `.indexino/index/<commit>/` amortizes that cost:

- First `index`: build base.xodus
- Subsequent `query`: read keys only
- New commit: new base directory; old bases can be garbage-collected

See [INDEX-STORAGE.md](INDEX-STORAGE.md) and
[.plans/kotlin-code-index-core.md](../.plans/kotlin-code-index-core.md).

## selection-context application

Lexical PSI walk at index time → `compose:selection-site:*` records. Queries scan Xodus, not source trees.

Limits: [application-selection-context.md](../.plans/application-selection-context.md).

## Topology

- **Primary:** Bazel — [BAZEL-TOPOLOGY.md](BAZEL-TOPOLOGY.md)
- **Secondary:** Gradle — [GRADLE-TOPOLOGY.md](GRADLE-TOPOLOGY.md)

## Upstream relationship

Storage contracts align with in-app code-index work (epic #812, issues #814–#818). This repo is
the **standalone CLI track**. Kotlin uses embedded PSI, Java uses JDK 21 `JavacTask.parse()`, and
XML uses JDK StAX plus Android resource-path conventions. A Tree-sitter runtime is not required
for these languages. ASM dependency producers remain a later core milestone.

## Technology

- **kotlin-compiler-embeddable** — PSI for Kotlin/Compose (Detekt-independent)
- **JDK compiler trees** — parse-only Java declaration/reference extraction
- **JDK StAX** — namespace-aware XML/resource extraction with DTD and external entities disabled
- **Xodus** — embedded persistent store
- **Clikt** — CLI
- **Shadow + R8** — reproducible standalone JARs; the shrunk variant retains manifest launch,
  embedded PSI/Xodus reflection contracts, and merged service providers
- **Future:** ASM dependency indexing (#817)

## Distribution build outputs

| Output | Purpose | Published to Maven |
|--------|---------|--------------------|
| `*-all.jar` | Unshrunk compatibility/debug CLI for direct `java -jar` use | No |
| `*-shrunk.jar` | Verified native-packaging input | No |
| `native-distributions/application/indexino-cli.jar` | Metadata-normalized native/AOT application JAR | No |
| `indexino-*-linux-x64.zip` | Linux x64 launcher, stripped JBR 25 runtime, application JAR, and licenses | No |
| `indexino-*-macos-arm64.zip` | Flat macOS arm64 CLI with the same installation layout | No |
| `indexino-*-windows-x64.zip` | Windows x64 console launcher with the same installation layout | No |
| ordinary JVM JAR | Thin dependency artifact with transitive runtime dependencies | Yes |

`shadowJar` and `shrunkCliJar` share explicit main output, runtime classpath, manifest, service
merge, duplicate handling, and reproducibility settings. The shrunk task adds only the checked-in
rules under `gradle/r8/`. `normalizedCliJar` atomically copies that R8 output and assigns a
deterministic even-second filesystem mtime. The task is intentionally never up-to-date and its
build cache is disabled because Gradle content snapshots do not detect metadata-only changes that
would invalidate AOT. Construo is configured to consume this exact normalized output.

Each native target uses checked-in JBR and Roast digests. Construo verifies those archives before
extraction, runs `jlink`, `jdeps`, and `javap` from the matching target JBRSDK 25, and emits one
target-specific archive. The shipped jlink image intentionally omits `runtime/bin/java` while
retaining process helpers such as `jspawnhelper`; the application still launches external Git and
topology tools when a command needs them. AOT caches are added by the later D3 packaging layer, not
by the baseline D2 distributions.

## Phased delivery

[.plans/master-plan.md](../.plans/master-plan.md)

## Out of scope

- Target-repo Gradle/Bazel plugins
- Full type resolution across classpath
- A generalized IntelliJ PSI host for arbitrary languages
- IDE daemon / MCP requirement for queries
