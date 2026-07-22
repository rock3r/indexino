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
| `native-distributions/aot/*/classes.jsa` | Task-owned, target-specific AOT cache overlay | No |
| `indexino-*-linux-x64.zip` | Linux x64 launcher, stripped JBR 25 runtime, application JAR, AOT cache, and licenses | No |
| `indexino-*-macos-arm64.zip` | Flat macOS arm64 CLI with the same installation layout | No |
| `indexino-*-windows-x64.zip` | Windows x64 console launcher with the same installation layout | No |
| `indexino-*-<target>.zip.sha256` | Portable checksum for the finalized native ZIP | No |
| ordinary JVM JAR | Thin dependency artifact with transitive runtime dependencies | Yes |

`shadowJar` and `shrunkCliJar` share explicit main output, runtime classpath, manifest, service
merge, duplicate handling, and reproducibility settings. The shrunk task adds only the checked-in
rules under `gradle/r8/`. `normalizedCliJar` atomically copies that R8 output and assigns a
deterministic even-second filesystem mtime plus ordinary-file `0644` permissions on POSIX hosts.
The task is intentionally never up-to-date and its
build cache is disabled because Gradle content snapshots do not detect metadata-only changes that
would invalidate AOT. Construo is configured to consume this exact normalized output.

Each native target uses checked-in JBR and Roast digests. Construo verifies those archives before
extraction, runs `jlink`, `jdeps`, and `javap` from the matching target JBRSDK 25, and emits one
target-specific archive. The shipped jlink image intentionally omits `runtime/bin/java` while
retaining process helpers such as `jspawnhelper`; the application still launches external Git and
topology tools when a command needs them.

Roast embeds HotSpot in the launcher process. The packaged launcher sets an Indexino-specific VM
property; only that marked Windows entry point installs a Win32 `SetConsoleCtrlHandler` callback
through JNA, first clearing the process-level ignore-Ctrl-C flag. The callback halts with exit code
130 so a console `CTRL_C_EVENT` or `CTRL_BREAK_EVENT` terminates a running command. Thin/fat/R8 JVM
launches retain the JVM's normal interrupt and shutdown-hook behavior.

The macOS archive has one Indexino-owned downstream finalization step. It extracts Construo's raw
ZIP with `ditto`, replaces the staged application JAR and AOT cache with the exact task inputs,
normalizes the cache to ordinary-file mode `0644`, and re-archives with `ditto --norsrc`. This
preserves the normalized JAR filesystem mtime when users extract with macOS `ditto`, prevents a
restrictive builder umask from leaking into the archive, and prevents AppleDouble entries. The
finalizer does not mutate Construo tasks or their inputs and is intentionally neither cacheable nor
up-to-date because the JAR mtime and current task-owned AOT cache are part of the output contract.
Its expanded staging tree is removed after both successful and failed finalization. The public
`packageMacArm64` lifecycle
finalizes the raw Construo output before it completes; downstream checksum and upload tasks must
consume only the finalized archive.

Each `trainAot<Target>` task treats the final jlink image and normalized JAR as immutable inputs. It
copies them into a task-private flat Roast staging root, restores only the matching target JDK
`java` launcher into that private runtime, initializes the committed deterministic fixture, and
runs the production classpath/main/VM options with a bounded heap and hermetic environment. The
cache is assembled at a temporary path and atomically published as a separate task output. Construo
infers the producer dependency from the target-specific `packageFiles` provider and overlays only
that cache at HotSpot's platform location: `runtime/lib/server/classes.jsa` on Linux/macOS and
`runtime/bin/server/classes.jsa` on Windows. The archive still uses the original stripped runtime
and exact normalized JAR. AOT task build caching is disabled until reproducibility and cross-runner
compatibility are proven, while unchanged local inputs may reuse an up-to-date output.

Native verification augments only copied launcher JSON files with strict or diagnostic AOT flags;
the production archive remains in automatic mode without logging flags. The verifier also compares
the thin runtime classpath, unshrunk fat JAR, R8 JAR, and actual Roast launcher by independently
indexing equivalent clean fixtures, and writes per-target AOT diagnostics plus non-gating launch-time
and artifact-size reports under `build/reports/native-distributions/`. Matching-host verification is
never up-to-date or restored from the build cache because host tools, console behavior, and OS runtime
compatibility cannot be represented safely as reusable Gradle state.
CI keeps only the original JBR and Roast download archives in a dedicated cache whose exact key
contains both checked-in SHA-256 digests. A cache helper verifies every restored or downloaded byte,
then exposes those archives through a loopback HTTP server so Construo's ordinary download and digest
tasks remain unchanged. Extracted JDKs, runtime images, normalized JARs, trained AOT caches, packages,
and reports are never restored from that cache.
Report cleanup uses a non-following delete task so a symlink at the predictable report path cannot
escape the build directory. Process output is captured in task-owned files and decoded with UTF-8
replacement semantics for platform-native diagnostic bytes. Before a verified command starts, a
test helper places it in a new POSIX session/process group or a Windows Job Object configured to kill
all members when its owner closes. Timeout cleanup terminates that kernel-owned boundary rather than
depending on a racy user-space process-tree snapshot. Inherited streams and concurrent late child
creation therefore cannot hang or escape a verification run.
The thin runtime dependency collection remains a declared verifier input but is converted to a
classpath string only in the selected verifier's execution action, so unrelated Gradle tasks do not
resolve native-verification dependencies during configuration.

Pull requests run the JVM/publication/R8 gates and the Linux x64 native verifier. The latter repeats
an actual Roast index/query smoke inside Ubuntu 22.04 (glibc 2.35). A manual workflow runs the full
matching-host verifier on Ubuntu 24.04 x64, macOS 15 arm64, and Windows Server 2022 x64 and retains
the finalized ZIP, checksum, reports, test results, and console log for seven days.

## Phased delivery

[.plans/master-plan.md](../.plans/master-plan.md)

## Out of scope

- Target-repo Gradle/Bazel plugins
- Full type resolution across classpath
- A generalized IntelliJ PSI host for arbitrary languages
- IDE daemon / MCP requirement for queries
