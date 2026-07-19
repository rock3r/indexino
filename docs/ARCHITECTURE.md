# Architecture

## Product shape

**kotlin-code-index** is a portable, **persistent** local code index shipped as a standalone fat
CLI JAR and as a thin Maven artifact. It is not a SelectionContainer one-off —
**selection-context** is the first **application plugin** on top of shared storage and topology.

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
                         │  .kotlin-index/index/<commit>/│
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

## Persistence (why it exists)

Large Bazel monorepos may take **minutes** to index. The store under `.kotlin-index/index/<commit>/` amortizes that cost:

- First `index`: build base.xodus
- Subsequent `query`: read keys only
- New commit: new base directory; old bases can be garbage-collected

See [INDEX-STORAGE.md](INDEX-STORAGE.md) and [.plans/kotlin-code-index-core.md](../.plans/kotlin-code-index-core.md).

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
- **Future:** ASM dependency indexing (#817)

## Phased delivery

[.plans/master-plan.md](../.plans/master-plan.md)

## Out of scope

- Target-repo Gradle/Bazel plugins
- Full type resolution across classpath
- A generalized IntelliJ PSI host for arbitrary languages
- IDE daemon / MCP requirement for queries
