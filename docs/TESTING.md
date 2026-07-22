# Testing

## Expectations

1. **Unit tests for analysis logic** — walker, callee matching, exclusion rules, nested SC
   counting. Use fixture `.kt` files under `src/test/resources/fixtures/`.
2. **Unit tests for topology** — `.bazelproject` parsing, BUILD snippet parsing (no live Bazel
   required in CI).
3. **Edge cases** — nested SC, DisableSelection between site and SC, no SC, import aliases,
   markdown `selectable = true` when configured.
4. Run `./gradlew test` before considering a change complete.

Distribution and publication contracts use dedicated tagged test tasks and are excluded from the
ordinary unit-test task:

```bash
./gradlew verifyShrunkCli       # manifest launch, full fixture workload, services, size ceiling
./gradlew verifyMavenPublication # thin artifact and distribution-variant non-leakage
./gradlew verifyConstruoContract # released native-packaging API, checksums, modes, normalized JAR
./gradlew verifyAotTrainingContract # immutable staging, hermetic JVM args/env, atomic cache output
./gradlew trainAotMacArm64       # matching-host AOT assembly (LinuxX64/WindowsX64 variants exist)
./gradlew verifyNativeDistributionLinuxX64   # native Linux x64 host only
./gradlew verifyNativeDistributionMacArm64   # native macOS arm64 host only
./gradlew verifyNativeDistributionWindowsX64 # native Windows x64 host only
```

Kotlin ABI validation is part of `./gradlew check`. Until the first embedded API is defined,
`api/indexino.api` is intentionally empty and `checkKotlinAbi` fails on any accidental public
declaration:

```bash
./gradlew checkKotlinAbi
```

Run `updateKotlinAbi` only when intentionally accepting a reviewed public API change; never use it
as an automatic CI repair.

`verifyShrunkCli` runs only CLI behavior through `shrunkCliJar`; the unshrunk JAR is retained as a
size baseline and diagnostic fallback. The fixture must exercise Kotlin, Java, Android XML, Xodus,
selection-context, status/freshness, deterministic symbol/reference queries, and resource lookup.

`verifyConstruoContract` runs a Gradle TestKit consumer against the pinned Construo release. It
checks provider-inferred overlays and preparation tasks, per-target archive outputs and target-JDK
tool selection, raw macOS layout, Windows console options, checksum rejection, Unix ZIP modes, and
the deterministic metadata of the non-cacheable normalized application JAR. A TestKit regression
starts from a restrictive input mode, perturbs only the output's mtime, and proves a second
invocation repairs both `0644` POSIX permissions and the timestamp instead of reporting
`UP-TO-DATE`; warm-cache checksum tests drive extraction tasks and prove rejection happens first.

`verifyAotTrainingContract` uses Gradle TestKit and a fake POSIX target launcher to prove a full SDK
is rejected, the final runtime and normalized JAR remain immutable, only `java` is restored into a
task-private staging copy, the relative Roast classpath/main/options are exact, hostile ambient JVM
option variables are removed, the normalized JAR metadata survives training, the cache is published
atomically at its separate output, and a second unchanged invocation is locally `UP-TO-DATE`.
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

The same task verifies AOT semantically through copied launcher configurations. Strict mode must
load the target-default cache before and after relocation and must fail for missing, corrupt, or
JAR-metadata-incompatible inputs. Automatic mode must use a valid cache and must still complete the
full workload after rejecting a missing or corrupt cache, with the semantic log fact explicitly
reporting that linked classes are disabled. The relocated strict-mode copy builds a new store rather
than only reading the pre-relocation store. Exact pinned-JBR output is retained as a diagnostic
report, while assertions use parsed cache-path, acceptance/rejection, and linked-class facts so
incidental log wording is not the sole signal.

A differential golden suite compares stdout/JSONL, stderr routing, exit codes, invalid usage, and
the manifest schema/version across the thin Maven runtime classpath, unshrunk fat JAR, R8 JAR, and
the target's real Roast executable. Each entry point independently indexes an equivalent clean
fixture so store creation, schema/version, and representative records are compared; only the
documented volatile `builtAt` value is normalized. Process output is captured in task-owned files and
decoded with UTF-8 replacement semantics so platform-native diagnostic bytes cannot abort the
verifier. A descendant that inherits stdout or stderr cannot keep a completed launch blocked. Each
command starts only after a helper establishes a new POSIX session/process group or a Windows Job
Object with kill-on-close semantics. Timed-out launchers and their Git/topology descendants are
therefore terminated through a kernel-owned boundary, including children created concurrently with
timeout handling. The matching-host verifier is deliberately never up-to-date or restored from build cache,
and clears its report directory without following symlinks before every execution so failed runs
cannot upload stale diagnostics. It also writes a report-only benchmark with five
interleaved production-AOT and `AOTMode=off` launches, median wall/user time, and ZIP/runtime/JAR/AOT
cache sizes to `build/reports/native-distributions/<target>/`.

On macOS the public `packageMacArm64` lifecycle includes `finalizedMacArm64Archive`, which round-trips
through native `ditto` and overlays the exact normalized JAR and current AOT cache. The staged cache
is normalized to ordinary-file mode `0644`, including when its source was created under a restrictive
umask. Verification consumes that final output, and future checksum/upload tasks must do the same.
Standard extraction must recover the exact even-second JAR mtime; this is deliberately tested after
extraction rather than inferred from Java's interpretation of ZIP extra fields.

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
