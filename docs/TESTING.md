# Testing

## Expectations

1. **Unit tests for analysis logic** — walker, callee matching, exclusion rules, nested SC
   counting. Use fixture `.kt` files under `src/test/resources/fixtures/`.
2. **Unit tests for topology** — `.bazelproject` parsing, BUILD snippet parsing (no live Bazel
   required in CI).
3. **Edge cases** — nested SC, DisableSelection between site and SC, no SC, import aliases,
   markdown `selectable = true` when configured.
4. Run `./gradlew test` before considering a change complete.

Synthetic fixture commits must use command-local `git -c commit.gpgsign=false commit` so a
developer's signing configuration cannot prompt or hang a headless test. Do not disable signing
globally or through process-wide `GIT_CONFIG_COUNT`: origin-cache tests intentionally reject that
blanket override. Real repository commits retain the developer's signing policy.

Watcher tests must select the coverage they assert: force native startup failure when checking
per-directory fallback keys. A live recursive watcher intentionally needs only the workspace root
for unseen sources; the native-coverage test checks that path separately, including coverage loss.
The 300-origin admission test checks the four-worker limit while fingerprints are blocked, then
uses a separate 120-second completion watchdog on every platform. Completion includes real Git
process launches and is not a 30-second performance contract for non-Windows hosts.

Origin-state cache fixtures use command-local `-c maintenance.auto=false` so a synthetic commit's
detached maintenance cannot race the subsequent timestamp aging of `.git` files. This does not
change repository or user Git configuration, production Git invocations, or explicit `pack-refs`.

`PosixGitProcessTest` runs real Git/fsmonitor hooks on supported Linux hosts with Python 3. A
disposable external subreaper controls orphan reaping without changing the test JVM's process
policy. It covers a successful command with a non-reaping adopter, a normally reaping control,
and cancellation while a live hook forks helpers. Independent assertions distinguish zombie-only
group existence from execution liveness, require Git's leader to be reaped, and verify interruption
preservation. The adopter kills/reaps its own children with a separate bounded cleanup even when
the probe fails. Other hosts or missing Python 3 skip these Linux-only regressions explicitly.
Run just this root-project suite with `./gradlew :test --tests '*PosixGitProcessTest'`.

Distribution and publication contracts use dedicated tagged test tasks and are excluded from the
ordinary unit-test task:

```bash
./gradlew verifyShrunkCli       # manifest launch, full fixture workload, services, size ceiling
./gradlew verifyMavenPublication # thin artifact and distribution-variant non-leakage
./gradlew verifyConstruoContract # released native-packaging API, checksums, modes, normalized JAR
./gradlew verifyAotTrainingContract # immutable staging, hermetic JVM args/env, atomic cache output
./gradlew generateBundledDependencyInventory # release legal/provenance input
./gradlew trainAotMacArm64       # matching-host AOT assembly (LinuxX64/WindowsX64 variants exist)
./gradlew verifyNativeDistributionLinuxX64   # native Linux x64 host only
./gradlew verifyNativeDistributionMacArm64   # native macOS arm64 host only
./gradlew verifyNativeDistributionWindowsX64 # native Windows x64 host only
./gradlew sha256NativeDistributionMacArm64    # checksum final native ZIP (target variants exist)
```

The public API stack is Metalava signature lineage + detekt declaration rules + forward consumer
fixtures. Cross-version linkage against the previously published artifact runs at **release time
only**. See
[API-STABILITY.md](API-STABILITY.md) and [PUBLIC-API-DESIGN.html](PUBLIC-API-DESIGN.html).

The Maven-coordinate forward fixture runs under `verifyMavenPublication`, not the unit-test portion
of `check`, because it publishes to an isolated Maven Local repository and launches an external
Gradle consumer. CI and the pre-merge gate run `check verifyMavenPublication` together.

Never update Metalava dumps merely to make CI green. Move a reviewed dump only through
`metalavaUpdateSignature` (or the corresponding artifact-qualified task).

`verifyShrunkCli` runs only CLI behavior through `shrunkCliJar`; the unshrunk JAR is retained as a
size baseline and diagnostic fallback. The fixture must exercise Kotlin, Java, Android XML, Xodus,
selection-context, status/freshness, deterministic symbol/reference queries, and resource lookup.
Kotlin fixtures in shrunk/native verification and AOT training include KDoc links and tags to
exercise the reflectively constructed `org.jetbrains.kotlin.kdoc.psi` nodes, not only ordinary PSI.
It also pins the validated Shadow release. Shadow 9.6 discovers dependency-supplied R8 rules, but
its rule merger deduplicates repeated lines and therefore corrupts multiline rule blocks that share
closing braces. The shrunk JAR excludes those embedded resources and supplies vetted one-line
runtime rules in `gradle/r8/shrunk-cli.pro`; keep the full workload green when changing either side.

`verifyConstruoContract` runs a Gradle TestKit consumer against the pinned Construo release. It
checks provider-inferred overlays and preparation tasks, per-target archive outputs and target-JDK
tool selection, raw macOS layout, Windows console options, checksum rejection, Unix ZIP modes, and
the deterministic metadata of the non-cacheable normalized application JAR. The Linux checksum
lifecycle is also exercised with a synthetic raw package to catch a non-executable Java overlay
without requiring Linux execution on the test host. A TestKit regression
starts from a restrictive input mode, perturbs only the output's mtime, and proves a second
invocation repairs both `0644` POSIX permissions and the timestamp instead of reporting
`UP-TO-DATE`; warm-cache checksum tests drive extraction tasks and prove rejection happens first.

`verifyAotTrainingContract` uses Gradle TestKit and fake target launchers to prove a full SDK
is rejected, the final runtime and normalized JAR remain immutable, only `java` is restored into a
task-private staging copy, the relative Roast classpath/main/options are exact, hostile ambient JVM
option variables are removed, the normalized JAR metadata survives training, the cache is published
atomically at its separate output, and a second unchanged invocation is locally `UP-TO-DATE`.
Forced retraining also clears read-only Git objects in its task-private staging directory on Windows;
the deletion walker does not follow staging symlinks into external directories.
Matching-host `trainAot<Target>` runs exercise the real JBR 25 one-step `AOTCacheOutput` workflow on
the committed Kotlin/Java/XML selection-context fixture. Git attributes enforce LF checkouts for
those text sources so the Kotlin PSI workload is identical on Windows; build-cache storage and
restoration are disabled for these metadata- and runtime-sensitive outputs.

Each `verifyNativeDistribution<Target>` task packages with the matching verified target JBRSDK 25,
extracts the ZIP with the platform's standard tool, and drives the actual Roast executable from an
arbitrary caller directory. The suite checks the flat layout, normalized JAR timestamp and bytes,
the byte-identical task-owned AOT cache overlay at the platform HotSpot location and cache-free
runtime input, explicit jlink modules,
the complete runtime legal tree byte-for-byte, launcher configuration,
target-JBR packaging tools, POSIX modes,
missing-Git diagnostics, the full Kotlin/Java/XML index/query workload, and relocation. The Windows
task additionally checks PowerShell and `cmd.exe` waiting/redirection/exit propagation and sends a
real console `CTRL_C_EVENT`. These tasks must run on matching native hosts; cross-packaging is not a
substitute for launcher verification.
The handler is enabled only by the packaged Roast VM marker; unit coverage proves direct JVM
launches do not install the halt-based callback and locks the marked-launch ordering/event mapping.

Unix archives must store `runtime/bin/java` as `0755`, and standard extraction must leave it
executable without a permission repair. An explicitly loaded plugin must return findings through
the extracted distribution's worker; hiding the packaged Java launcher must make that check fail.
The verifier clears inherited extension-JVM overrides and JVM option variables so a host JDK cannot
mask a broken install. On macOS it also verifies the restored launcher's code signature and JIT
entitlement.

The same task verifies AOT semantically through copied launcher configurations. Strict mode must
load the target-default cache before and after relocation and must fail for missing, corrupt, or
JAR-metadata-incompatible inputs. Automatic mode must use a valid cache and must still complete the
full workload after rejecting a missing or corrupt cache, with the semantic log fact explicitly
reporting that linked classes are disabled. The relocated strict-mode copy builds a new store rather
than only reading the pre-relocation store. Exact pinned-JBR output is retained as a diagnostic
report, while assertions use parsed cache-path, acceptance/rejection, and linked-class facts so
incidental log wording is not the sole signal.

A differential golden suite compares stdout/JSONL, stderr routing, exit codes, and invalid usage
across the thin Maven runtime classpath, unshrunk fat JAR, R8 JAR, and the target's real Roast
executable. Each entry point independently indexes an equivalent clean fixture and must not create
a project-local compatibility store or manifest; published lookup results remain compared. The
text-progress `durationMillis` values are normalized; phase names and all other output remain
compared. Process output is captured in task-owned files and
decoded with UTF-8 replacement semantics so platform-native diagnostic bytes cannot abort the
verifier. A descendant that inherits stdout or stderr cannot keep a completed launch blocked. Each
command starts only after a helper establishes a new POSIX session/process group or a Windows Job
Object with kill-on-close semantics. Timed-out launchers and their Git/topology descendants are
therefore terminated through a kernel-owned boundary, including children created concurrently with
timeout handling. Boundary readiness has a separate 10-second deadline. The descendant-held-output
test measures command completion and reading both capture files only after readiness, retaining its
2-second POSIX / 8-second Windows bound independently of boundary JVM startup.
The matching-host verifier is deliberately never up-to-date or restored from build cache,
and clears its report directory without following symlinks before every execution so failed runs
cannot upload stale diagnostics. It also writes a report-only benchmark with five
interleaved production-AOT and `AOTMode=off` launches, median wall/user time, and ZIP/runtime/JAR/AOT
cache sizes to `build/reports/native-distributions/<target>/`.

On macOS the public `packageMacArm64` lifecycle includes `finalizedMacArm64Archive`, which round-trips
through native `ditto` and overlays the exact normalized JAR and current AOT cache. The staged cache
is normalized to ordinary-file mode `0644`, including when its source was created under a restrictive
umask, while the restored Java launcher is made executable (`0755`). Verification consumes that
final output, and future checksum/upload tasks must do the same.
Standard extraction must recover the exact even-second JAR mtime; this is deliberately tested after
extraction rather than inferred from Java's interpretation of ZIP extra fields.

The pull-request workflow runs `check`, `verifyMavenPublication`, and `verifyShrunkCli`, then runs the
complete Linux x64 native verifier in a separate matching-host job. That job also launches the built
Roast distribution from an arbitrary directory inside `ubuntu:22.04`, whose glibc 2.35 is the oldest
declared Linux baseline, and performs an index/query workload. The manually dispatched
`native-distributions.yml` workflow repeats full native verification on all Tier 1 runners.
The tag workflow checks Maven and macOS signing/notarization credentials before any publication,
signs nested macOS JAR libraries before AOT training, signs the remaining macOS package payload,
and reruns the matching-host verifier against final signed macOS bytes before Maven upload. The
final signer verifies the JAR libraries without changing the JAR trained against the AOT cache.
Windows remains unsigned Authenticode and passes its matching-host native verifier;
the final checksums detect corruption and the tag-bound Sigstore bundles authenticate the exact
archives attached to the draft release. Unsigned pull-request macOS artifacts do not prove that
the credentialed signing path works; a candidate still needs a matching-host credentialed macOS
run and inspection of its final signature and notarization assessment.

The native archive cache shell contract in the ordinary JVM `test` task requires Git for Windows
Bash on Windows. The test resolves its `bash.exe` beside Git's executable, avoiding the Windows
Subsystem for Linux launcher when no distribution is installed. If Git Bash is unavailable, only
those two shell-contract tests are skipped with an explicit prerequisite message; other Windows
JVM tests still run. A Windows JVM test run is not a substitute for the separate matching-host
`verifyNativeDistributionWindowsX64` launcher and AOT verification.

Native CI caches only the original JBR and Roast archives. The key contains both checked-in digests;
the helper verifies a restored archive before serving it to unchanged Construo download/digest tasks.
Do not add extracted runtimes, `native-distributions/aot`, `classes.jsa`, application JARs, ZIPs, or
reports to a reusable cache. Every run uploads the finalized ZIP and `.sha256` plus size/benchmark
reports, test results, and the plain verification log with seven-day retention.

## TDD Red-Green Cycle

1. Write the test first.
2. Run targeted test; confirm **assertion failure** (not merely compile error).
3. Minimum implementation.
4. Confirm green.
5. Full `./gradlew test`.

```bash
./gradlew test --tests "com.selectionindex.analysis.SelectionWalkerTest.nested selection containers"
```

Do not skip the red step.

## Fixture Layout

```
src/test/resources/fixtures/
  nested-selection-containers.kt
  disable-selection-in-sc.kt
  no-selection-container.kt
  import-alias-selection-container.kt
```

Fixtures are minimal `@Composable` snippets — valid Kotlin syntax, no external deps required
for parse-only tests.

## Testing Anti-Patterns

**Do not** read production source from disk in tests and assert `source.contains("token")`.

**Do not** test Kotlin/PSI behavior by substring-matching generated walker output strings
without structural assertions on `SelectionContext` fields.

| Claim | Right tool |
|-------|------------|
| "call site is inside SC" | Parse fixture → locate call at line → assert `inSelectionContainer` |
| "excluded by DisableSelection" | Fixture with SC + DS + button → assert `excludedByDisableSelection` |
| "two SCs on path" | Nested SC fixture → assert `selectionContainerCount == 2` |
| "Bazel query returns these files" | Mock process output or golden-file parsed BUILD |

## Live Bazel Tests

Optional `@Tag("bazel")` tests that require `bazel` on PATH and a fixture workspace. Excluded from
default `./gradlew test`; run explicitly when developing topology:

```bash
./gradlew test --tests "*BazelTopology*" -PbazelTests
```

Keep CI green without Bazel installed.

## Test Completeness

Before marking work done, verify every scenario in the plan has a test that was red then green.
