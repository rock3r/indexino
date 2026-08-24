# Architecture

## Product shape

**indexino** is a portable, **persistent** local code index shipped as:

- a standalone fat CLI JAR (and R8-shrunk native-packaging input),
- self-contained native CLI ZIPs, and
- thin Maven artifacts for embedding (`indexino-model`, `indexino`, `indexino-plugin-api`,
  optional `indexino-script-host`, `indexino-compose-decoration`).

It is not a SelectionContainer one-off — **selection-context** is the first **compiled plugin** on
shared storage and topology; **compose-decoration** (S11) derives ordered modifier chains from
generic call/argument facts. Binding product design:
[PUBLIC-API-DESIGN.html](PUBLIC-API-DESIGN.html) (Accepted 2026-07-25).

```
┌──────────────────┐     ┌──────────────────────────────────────┐
│  Audit skills    │────▶│  CLI adapter / embedded Indexino API │
└──────────────────┘     └──────────────────┬───────────────────┘
                                            │
                         local AF_UNIX protocol (or in-process)
                                            │
                                            ▼
                              ┌─────────────────────────┐
                              │  Workspace runtime      │
                              │  refresh registry       │
                              │  watcher / budgets      │
                              └───────────┬─────────────┘
                    ┌─────────────────────┼─────────────────────┐
                    ▼                     ▼                     ▼
            ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
            │ Plugins      │     │ Producers    │     │ Topology     │
            │ (SPI JAR)    │     │ core facts   │     │ Bazel/Gradle │
            └──────┬───────┘     └──────┬───────┘     └──────┬───────┘
                   │                    │                    │
                   └────────────────────┼────────────────────┘
                                        ▼
                         ┌──────────────────────────────┐
                         │  User-local content-addressed │
                         │  packs + generation manifests │
                         │  (not under the worktree)     │
                         └──────────────────────────────┘
```

## Layers and packages

| Layer / artifact | Responsibility |
|------------------|----------------|
| `indexino-model` | Shared public types: IDs, locations, `QueryPage`, `Finding`, `BasicFactQueries`, `PluginFactValue`, `IndexinoInternalApi` |
| `indexino` (`…api`) | Client facade: connect, refresh, snapshot, status, diagnostics |
| `indexino-plugin-api` | Versioned SPI: analyzers, post-processors, checks, fact sink/view |
| `indexino-selection-context` | SelectionContainer / DisableSelection compiled plugin |
| `indexino-compose-decoration` | Compose modifier-chain decoration compiled plugin (S11) |
| `indexino-script-host` | Optional Alpha `.indexino.kts` (non-suspend DSL) |
| `engine` (internal) | Workspace runtime, refresh registry, coordinator, pack cache, GC |
| `cli` (internal) | Clikt, daemon/cache commands, JSONL, exit codes |
| `producer` / `topology` / `parse` / `core` (internal) | Analysis, Bazel/Gradle, PSI/Javac/StAX, keys |
| `detekt-plugin` (build-only) | Enforces equality and no-data-class rules on public packages; targets JDK 17 so Gradle can load it while product artifacts target JDK 25 |

Dependency direction (public):

```text
indexino ──────────► indexino-model ◄────────── indexino-plugin-api
     │                      ▲
     │                      │
     └──── engine (impl) ───┘
script-host ──► indexino + model
```

**Never** `indexino-plugin-api` → `indexino` or `indexino` → `api(indexino-plugin-api)` for shared
types. Host constructs SPI contexts with public constructors annotated `@IndexinoInternalApi`.

### S1 transitional boundaries

The S1 in-process facade delegates refresh orchestration to internal `cli/IndexBuildRunner`. This is
a tracer shortcut, not the target dependency direction. S3 extracts that orchestration into an
internal `IndexingCoordinator` owned below both facade and CLI when the refresh registry lands; the
CLI then becomes an adapter over the coordinator. The coordinator must return the exact manifest,
changes, and outcome used by the completed run; callers must not re-resolve `HEAD` or re-derive a
result from mutable storage.

S2 publishes refresh output as immutable content-addressed packs with generation manifests and an
atomic workspace `current` pointer. Mutable incremental writer state is confined to workspace
`staging/in-process-writer`; each client atomically materializes a referenced pack into its own
on-disk `refs/<client>/<generation>/store` snapshot directory. Snapshot pins reclaim only those
client-owned refs after close, while shared packs are retained by manifest reachability and cache GC.

The thin `indexino` POM deliberately omits CLI-only Clikt, JNA, and `slf4j-nop`; Gradle Module
Metadata is disabled for that artifact so Gradle consumers resolve the filtered POM rather than an
unfiltered `.module` graph. The fat CLI distribution still contains its runtime dependencies.

S5 publishes a complete signed Central release train: `indexino-bom`, `indexino-model`, `indexino`,
`indexino-plugin-api`, `indexino-selection-context`, and `indexino-compose-decoration`. S8 adds the optional Alpha
`indexino-script-host` artifact to that aligned train. The BOM aligns those coordinates for
Gradle and Maven consumers, and the tag workflow uploads every artifact together. Maven Local
remains available for dogfooding, but is no longer the only publication path.

## Embedded API boundary

Supported packages are only those listed in [API-STABILITY.md](API-STABILITY.md). Implementation
remains `internal` until deliberately published. Target tooling: Metalava + detekt + consumer
fixtures.

JDK floor for library artifacts: **25**.

## Persistence (why it exists)

Large Bazel monorepos may take **minutes** to index. User-local packs + generation manifests
amortize that cost:

- First refresh: analyze and publish a generation
- Subsequent open with no changes: **zero analyzers**
- Incremental refresh: work proportional to invalidation closure
- Sibling worktrees share content-addressed packs on the same machine

See [INDEX-STORAGE.md](INDEX-STORAGE.md). Commit is provenance, not the primary cache key.

## Workspace runtime

A long-lived **local** workspace runtime (normally a daemon) owns refresh work, watchers, plugin
classloaders, and budgets. CLI and embedded clients attach over AF_UNIX. Early tracer slices may
run in-process with the same public types. This is **not** an IDE/MCP requirement — it is a local
process for durable indexing.

## selection-context

Lexical PSI walk at index time → plugin-namespaced facts. Queries use checks / typed wrappers, not
source tree walks. Extraction to `indexino-selection-context` is slice S4.

## Topology

- **Primary:** Bazel — [BAZEL-TOPOLOGY.md](BAZEL-TOPOLOGY.md)
- **Secondary:** Gradle — [GRADLE-TOPOLOGY.md](GRADLE-TOPOLOGY.md)
- Public scopes: `IndexScope.bazel` / `gradle` (+ `includingDependencies`); non-Git single root from S2

## Technology

- **kotlin-compiler-embeddable** — PSI for Kotlin/Compose (Detekt-independent)
- **JDK compiler trees** — parse-only Java extraction
- **JDK StAX** — XML/resource extraction for public Android/CMP resource definitions and usages
- **Xodus** — generation-local indexes referencing content-addressed packs
- **Clikt** — CLI only (not on library POM)
- **Shadow + R8** — standalone / native-packaging inputs
- **kotlinx-coroutines** — `api` of the client (suspend / Flow)
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
| thin JARs | `indexino-model`, `indexino`, `indexino-plugin-api`, optional `indexino-script-host` | Yes |

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

Tracer-bullet slices **S0–S11** in [PUBLIC-API-DESIGN.html](PUBLIC-API-DESIGN.html). Historical
C*/A* labels in older plan notes are superseded for product delivery planning.

## Out of scope

- Target-repo Gradle/Bazel plugins
- Full type resolution across classpath
- A generalized IntelliJ PSI host for arbitrary languages
- **IDE / MCP daemon as a requirement** for queries (local workspace runtime is in scope)
- Sandboxed untrusted plugins (v1 is trusted local code)
- Migrating pre-contract in-worktree store layouts
