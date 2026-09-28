# Public acceptance and benchmark harness

This opt-in harness uses only pinned public repositories and invented fixtures. It is not a
release, publication, or compiler-resolution claim. Expensive corpora are outside `test` and
`check`; the small driver contract tests remain ordinary tests. No test API is published.

## Build and cheap checks

    python3 -m unittest discover -s scripts/public-acceptance -v
    ./gradlew :test --tests '*PublicAcceptanceDriverTest'
    ./gradlew publicAcceptanceDriverChecksum

The last task builds `build/public-acceptance/public-acceptance-driver.zip` and its SHA-256 file.
The ZIP contains the exact test/main outputs and production runtime dependencies, not a CLI release.
JUnit/TestKit dependencies are excluded so Gradle's logging backend cannot leak into driver daemons. Build
it once and use the same bytes in separate corpus jobs. Never publish it as a library artifact.

    python3 scripts/public-acceptance/run.py --corpus intellij \
      --report build/reports/public-acceptance/preflight.json

Preflight does not clone or execute a corpus. Exit 2 and `not-ready` are intentional when a
runner cannot satisfy the containment/resource contract; they are not a skipped success.

## Corpus and inventory contracts

`scripts/public-acceptance/corpora.json` owns full Git pins, scopes and independently inspected
declaration locations. Each disposable clone verifies `rev-parse HEAD` after checking out the
exact commit. Neither Spectre's Gradle wrapper nor its build scripts are executed.

* **Spectre:** `:core` target-only **and** dependency-inclusive runs. The latter is the shipped
  syntactic dependency whitelist, not a resolved production classpath: `core`,
  `input-coordinator`, and `input-coordinator-server`; `testImplementation` participates,
  `testRuntimeOnly` does not. Test source sets are excluded. The independent filesystem
  enumerator uses those reviewed roots, not Indexino topology results. An incomplete accessor
  closure fails exact set comparison, even if source counts are large.
  The captured-input closure also includes existing module build scripts and Android package
  manifests. The enumerator checks those metadata paths independently in each selected module;
  they are not excluded from equality or inferred from observed results. The manifest's Git-tree
  evidence counts only language/resource source-root files, before this metadata augmentation.
* **IntelliJ Community:** `//:main`, dependencies required, `bazel-query` required. No fallback to
  `//platform/jewel/ui:ui`; that label is only a named diagnostic. The independent Bazel query
  requests labelled source/generated files in the dependency closure. The denominator is local
  `//` `.kt`/`.java`/`.xml` source-file labels plus files under resource directory components
  (`res`, `resources`, `composeResources`, or names ending in `_res`, `-res`, `_resources`,
  `-resources`). External `@` labels, generated labels, directory entries, and unsupported paths
  are counted separately as query exclusions. Directory labels are not implicitly expanded.
  The captured-input closure also includes existing build scripts and Android manifests adjacent
  to queried code/resource module roots, independently enumerated from the filesystem. Unrelated
  modules and plain configuration XML do not expand that metadata closure, and escaping metadata
  is rejected. Missing required files remain in the denominator. Missing/extra discovered paths
  fail; a count floor is never sufficient.
  Upstream's `getPlugins.sh` requires a separate `android/` checkout but supplies no paired commit.
  The harness does not execute that unpinned script: it acquires the public `JetBrains/android`
  mirror at the explicit companion pin in `corpora.json`, selected by committer time preceding the
  IntelliJ pin. This defines an auditable composite corpus, not an upstream-certified revision pair.
  The GitHub mirror omits some large binaries relative to JetBrains' canonical Git service; full
  source-closure query and exact inventory gates remain mandatory. No missing package is ignored.
  Nested origins retain their workspace-relative prefixes in the observed inventory; sources
  outside the disposable workspace remain forbidden.

Inventory SHA-256 hashes UTF-8 sorted relative paths, one per line with a final LF. Duplicate
inventory entries fail. The driver uses the existing internal source-inventory callback only to
observe the actual captured-input set (including resource metadata) and reads the existing generation
manifest only for source provenance:
actual topology mode, dependency inclusion and scope must match the required lane. These are not
semantic facts; symbol/reference/resource/lifecycle queries use public typed APIs.
Public declaration IDs are generation-qualified opaque IDs: reference queries first select the
independently specified FQN, file and declaration line, then use the returned ID.
Declaration lines follow the syntactic-start contract, including annotations but excluding comments:
at the pinned Spectre revision, `ComposeAutomator` starts with `@Suppress` on line 40, not the
`class` keyword on line 41.

## Measurements and correctness

Bootstrap/tool acquisition and independent inventory enumeration are separate from indexing.
For each scope the orchestrator launches **three Indexino-cold** driver JVMs with new external
caches. The **OS page cache is uncontrolled**. Each does five unchanged/manual refreshes, opens
pinned snapshots, performs ten query warmups then 100 measured queries per class, and closes and
reopens the published generation. Generation and exact result assertions run throughout.
Pagination is bounded and rejects duplicates, omissions and non-advancing pages.

A separate instrumented cold diagnostic uses the existing internal refresh overload. Cold
producer events are a positive control; unchanged refreshes must emit no core producer phases.
Its generation must match the public lane. This is producer-phase evidence, not a claim about
every internal function call. Semantic acceptance never depends on raw storage reads.

Discovery completion occurs **after source capture**. The existing reporter cannot separately
measure topology enumeration, capture, and hash work within refresh. The report retains timestamped
raw phase events; `source-hash-preview` is only aggregate hashing, not total capture/hash cost.
Instrumented refresh boundaries derive `preDiscoveryCompletedNanos` (combined refresh setup,
topology and capture) and matched start/end durations per producer phase. Each refresh gets a new
reporter so terminal/counter state cannot leak across repeats. Incomplete phase pairs fail rather
than becoming zero-duration samples. Topology-only and total capture/hash fields remain null.
Unavailable metrics remain null or explicitly labelled unavailable, never zero. Instrumented
timings are not mixed with public API latency. Query timings include typed pagination and assertion
overhead, but exclude CLI/JVM launch; command wall timings include launch and the complete driver.
Refresh timings include the test-only inventory callback's validation overhead; they are not
instrumentation-free production latency measurements. Compare the same harness build and scope.

The invented `retrieval-v1` ground truth is owned by `docs/RETRIEVAL-FIXTURES.md`. It separates
syntactic correctness gates from semantic precision/recall gaps and marks held-out labels.
Producer regressions also cover callable values shadowing imported constructor names: local
initializer inference stays unknown rather than attributing members to the shadowed type.
The fixture driver compares exact row multisets after explicit language/file filtering. It never
weakens semantic labels to match candidate resolution. Manual and watcher lanes use separate
disposable copies, apply all edit/add/rename/delete stages five times, verify exact locations and
generation changes, and verify previously pinned snapshots remain unchanged. Watcher mutations
must converge without a manual refresh for that mutation; restoration between repeats is explicit.

Separate `calls-manual` and `calls-watcher` lanes invent a unique top-level `ping` declaration and
caller files. They assert exact reference and call-site multisets after moving a call by editing,
adding a second caller, renaming a caller file, deleting it, and deleting the last caller. Callee
candidate IDs and enclosing caller IDs must match the current snapshot's independently selected
declarations by FQN, file and line. Both query classes receive 100 measurements after ten warmups. All five
mutation stages repeat five times with pinned-snapshot checks. Failed retrieval lanes do not skip
these independent lanes when the runner has confirmed process cleanup and the resource budget still
permits execution. Timeout and bounded-output failures retain any checkpoint but cannot be reported
as passed. Nonzero root exit also requires cgroup cleanup, since detached children may remain.
Cleanup evidence resets for every command. Unconfirmed quiescence, interruption, or a resource-budget
failure stops further lanes. All safely run
fixture reports are retained after disposable cleanup, and any failure gates corpus work.

Reports retain raw samples and nearest-rank p50/p95 (`ceil(p*n)-1` in zero-based sorted samples),
artifact digest, public pins, scope, cache assumptions, machine and Java information. Linux cgroup
CPU deltas include all job-owned descendants; `memory.peak` is accounted memory **not RSS**.
Unsupported per-phase RSS and disk-peak measurements are null with reasons. Performance is
initially report-only. Correctness, exact source coverage, resource limits and cleanup gate success.

Scope and repeat checkpoints are registered before execution and survive disposable-directory
cleanup even when a command fails. Existing driver JSON is retained on nonzero exit, with unique
per-repeat filenames so an earlier success cannot stand in for a missing checkpoint. Stderr evidence
is limited to fixed allowlisted exception classes and diagnostic signals from the first 64 KiB;
raw messages, command arguments, absolute host paths and possible secrets are not exported.
After exact set comparison succeeds, each repeat retains the inventory count and SHA-256 rather
than duplicating the entire large source list. Failed checkpoints retain available source rows.
This compaction does not replace set equality with a count or digest-only correctness check.

## Containment is a prerequisite, including detached servers

macOS and Windows full corpus lanes are **not ready** in this implementation. A process group or
PID-tree walk does not prove cleanup of a detached Bazel server. Do not run both corpora on a shared
Mac merely because physical RAM exceeds the public Bazel rc's 12 GiB heap request.

The supported serial profile fits a Linux x64 machine with 32 GiB physical RAM, cgroup v2,
at least 18 GiB **available** memory and 150 GiB free disposable disk (200 GiB scratch recommended),
JDK 25 and Python 3.11+. An operator must pre-delegate memory/pids controllers.
The runner creates only a unique `indexino-<uuid>` child beneath the supplied parent; it never
moves existing processes or writes the parent's `cgroup.kill`. No sudo, service restart, controller
enablement, or permission repair is performed. Memory is capped at 16 GiB, swap at zero, processes
at 1024. The driver and private Bazel server each use a 6 GiB maximum heap; the job-private Bazel
startup override supersedes the public rc's 12 GiB request without changing corpus files or scope.
Run only one corpus job at a time. Individual command timeouts are bounded. Low free disk and
oversized command output abort. OOM and timeout remain failures, never reasons to shrink the corpus.
Reports record these budgets. Shared-host timings are contended correctness evidence, not an isolated
performance baseline; cgroup containment is process/resource containment, not a filesystem sandbox.

Before acquiring a corpus, prove detached-child timeout cleanup on that exact runner:

    INDEXINO_TEST_CGROUP_PARENT=/operator/delegated/cgroup \
      python3 -m unittest discover -s scripts/public-acceptance -p test_runner.py -v

The test forks a child that starts a detached session, times out the parent, kills only the new
job cgroup, and verifies `populated 0`. On unsupported hosts it is explicitly skipped, not a proof.
Every real job uses private HOME/config/cache and Bazel `output_user_root`; it explicitly shuts
down **only** that private Bazel server, then kills/verifies the job cgroup even after failure.
The corpus, Indexino caches, private config, tool downloads and command logs are disposable.

    python3 scripts/public-acceptance/run.py --execute --corpus spectre \
      --artifact build/public-acceptance/public-acceptance-driver.zip \
      --sha256 EXACT_BUILD_DIGEST --cgroup-parent /operator/delegated/cgroup \
      --scratch /ephemeral/scratch --report build/reports/public-acceptance/spectre.json

The manual workflow is disabled by default, main-branch-only, and requires explicit selection of
the capable ephemeral runner profile. Its two corpus jobs use `max-parallel: 1`, `fail-fast: false`,
unique artifacts, isolated caches and a single checksummed driver build. There is no untrusted PR execution path and
no privileged persistent self-hosted fallback. This personal repository uses repository-scoped
ephemeral runners, not organization runner groups. Each runner label includes workflow run ID,
attempt and corpus; the operator supplies `INDEXINO_PUBLIC_CGROUP_PARENT` in its service environment.
Checking in the workflow does not provision runners or delegate controllers.
Actions use immutable SHAs; JBR, Gradle, Bazelisk and JetBrains Bazel downloads are checksum-pinned.
Only allowlisted JSON reports up to 16 MiB are uploaded with seven-day retention, never corpus
trees, user homes, caches, or raw process logs. Workflow dispatch is a separate authorized action.

## Run both corpora serially on one authorized Linux host

No 64 GiB machine or Actions registration is required for direct `run.py` execution. The authorized
32 GiB Linux host's delegated cgroup has passed both real detached-child timeout and nonzero-parent
cleanup tests, including empty-child verification, without changing parent controllers or existing
processes. Actual available memory, capped by every finite ancestor's remaining allowance, must
still pass preflight immediately before each corpus. A writable directory alone is not proof.

1. Run the containment tests above on the exact host and delegated parent.
2. Build the driver once and record its SHA-256. Run the command above for Spectre.
3. Wait for exit and inspect its report, including `cleanupVerified`. Only after confirmed cleanup,
   run the same command with `--corpus intellij` and a different report path, using identical driver
   bytes. Recheck resource readiness; do not run other corpus jobs concurrently.
4. Preserve both reports, including failed checkpoints. Exact inventory/query assertions, resource
   gates and verified cleanup determine success. Local fixture success cannot replace corpus results.

For optional Actions execution, the disabled-by-default `ephemeral-linux-x64-32g-serial` profile
uses one-job repository-scoped runners, unique per-corpus labels and a serial matrix. Provisioning,
runner registration, delegation, and workflow dispatch remain separately authorized operator actions;
none is performed by the harness. The local path does not install a persistent runner, provision a
VM, change shared controllers, or stop shared servers. Only pinned public corpora are acquired.

## Local validation and retained failure history

The v9 full-corpus correctness gates pass for both pinned corpora on the authorized 32 GiB Linux
host, run serially with 6 GiB driver/Bazel heaps, a 16 GiB job limit and no swap. IntelliJ captures
65,149 inputs; Spectre captures 47 target-only and 68 dependency-inclusive inputs. Each scope
passes three public repeats and a separate instrumented repeat, including exact independent
inventory equality, cold refresh, five unchanged refreshes, exact queries and reopen. Both jobs
verify cleanup. These are bounded correctness results for the pinned scopes, not general semantic,
performance or cache-policy readiness. The earlier failures below remain recorded.

The initial fixture run exposed syntactic receiver-resolution and simultaneous-snapshot failures.
After the corresponding production repairs, the integrated Mac run matches all eleven syntactic
cases exactly and measures all thirteen cases 100 times after warmup. The two independently
labelled same-arity semantic cases still report precision 0.5 and recall 1.0; they remain explicit
compiler-resolution gaps, not syntactic failures. No fixture labels were weakened.

Both manual lanes pass all 25 mutation stages, including pinned snapshots. The caller lane also
passes 100 exact reference and 100 caller measurements. The initial Mac watcher lanes exposed a
snapshot-materialization move failure (`Directory not empty`); those red roots and checkpoints
remain preserved. After the separately regression-tested atomic-rename repair, both rebuilt Mac
watcher lanes pass all 25 stages in new disposable roots, with unchanged assertions and labels.
The retrieval watcher has no syntactic failures; the caller watcher passes 100 exact reference and
100 caller measurements. These contended local timings are correctness evidence, not performance
baselines.

The serial 32 GiB Linux profile has completed Spectre's two full scopes with the same packaged
driver: 47 target-only captured inputs (46 sources plus build metadata), and 68 dependency-inclusive
inputs (65 sources plus three build files). All three cold repeats, five warm refreshes per repeat,
exact symbol queries, reopen checks and separate instrumented repeats pass. Job peak accounted memory
was 825,507,840 bytes and cleanup was verified. The initial wrong inventory and declaration-line
expectations failed before their independently checked harness corrections; those reports remain
retained.

The same v4 driver also ran IntelliJ's full query and first cold refresh: the independent
inventory contains 65,129 inputs, with 2,285 external labels, one generated label, 5,221 unsupported
paths and 1,015 directories excluded. Resource-directory capture was regression-fixed without
silently dropping missing files. The refresh then failed on a configuration-text preview stored
as `java/java-impl/resources/codeVisionProviders/java.configuration/preview.java`, because
Java extraction rejects compiler parse errors. This exposed the code/resource classification gap
addressed below, not a capacity or containment failure: peak accounted memory was 13,302,009,856
bytes within the 16 GiB job limit, and cleanup was verified. That run did not complete an IntelliJ
index or establish corpus readiness.
Both runs used driver SHA-256 `d5263c3920e119b1669f22b80e4901e2ace2f4f37562d296d88db32e65e97d10`.
All four invented fixture lanes passed in each run. The reports are
`build/reports/public-acceptance/{spectre,intellij}-serial-32g-v4.json`.

The approved correction distinguishes captured resources from compilation sources using build-role
evidence. Bazel follows compilation `srcs` and code-reachable filegroups/aliases; Gradle uses its
supported code source roots. Resource-only `.java` and `.kt` files remain captured and hashed but
do not run core language analysis. Dual-role files remain code. Unknown role evidence preserves
legacy strict analysis, rather than silently excluding files. Role changes invalidate cached facts,
including when bytes are unchanged; schema version 5 prevents reuse of older extension-only facts.
This does not relax parser errors in actual code or introduce production/test classification.

The v5 rebuilt driver passed all four fixture lanes and Spectre's two full scopes, including all
cold/warm/query/reopen and instrumented checks. IntelliJ again enumerated 65,129 inputs, but its
first cold driver reached the unchanged 7,200-second bound during Kotlin analysis. That run did not
complete an IntelliJ index. Job peak accounted memory was 15,759,323,136 bytes; sampled memory-limit
and OOM counters remained zero. Cleanup was verified, including removal of the owned processes,
disposable root and cgroup. Both reports remain at
`build/reports/public-acceptance/{spectre,intellij}-serial-32g-v5.json`, using driver SHA-256
`9adae641f3e7d44d50f0782cf9eb574c5fad506d20351d33dd8f104ca5b479df`.
Thread samples during this contended run repeatedly found project-wide symbol scans in Kotlin
call and parameter resolution. Lookup now groups declarations once by name and FQN, retaining
order and duplicates; characterization tests preserve overload ambiguity, origin preference,
nested constructors and import aliases.

The v6 driver with that lookup optimization passed Spectre, but IntelliJ's first driver exited 1
after 1,262.2 seconds with Java heap exhaustion in the build's rollback scan. That secondary error
masked the original failure; it does not prove that all Kotlin analysis finished. Cleanup passed
for both corpora. The v6 reports retain driver SHA-256
`cbcf6cc2a685f5a179f649efa83841a4d6328e67fb45b08c1d1654cbdb342002` and the failure evidence.
A separate 64 MiB JVM reproduced the eager checkpoint failure with 128 MiB of decoded records.
Checkpoint and rollback now stream through a temporary disk store and preserve the original error;
the same bounded-heap regression passes.

The v7 driver preserved IntelliJ's original failure: Java heap exhaustion while Kotlin reference
indexing wrote records, after 1,820.9 seconds. Spectre passed again, and both jobs verified cleanup.
The reports retain driver SHA-256
`97c6dd8143ea28bdad54cc745295db12a4f1db82499b40b6dcc525d694a667ff`.
Kotlin analysis retained the whole project's syntax trees. It now retains declaration metadata
only, reparsing one file at a time from the immutable refresh snapshot for reference/call indexing.
A 256 MiB subprocess with 384 invented callers reproduces the old heap failure and passes after
the change, checking exact declarations, forward cross-file targets, parameter names and locations.

The v8 driver reached the captured-inventory assertion after 1,505.3 seconds without the prior
heap failure. It captured 65,149 inputs against 65,129 expected: 20 additional Gradle build scripts
used as resource metadata. The Bazel enumerator omitted that documented augmentation, unlike the
Gradle enumerator. Two assertion-red/green tests now cover independent metadata enumeration,
unrelated-module exclusion, deduplication and escaping metadata rejection; exact equality remains
mandatory. Spectre passed and both jobs verified cleanup. Driver SHA-256 is
`46fc91edf85edf8fe43912f0b0858b09d498ab4aa35025a3db1837c19cf47357`.

The v9 run uses that unchanged driver with the corrected independent enumerator. All IntelliJ
repeats capture exactly 65,149 inputs and publish the same generation. The diagnostic repeat
confirms cold core-producer phases and zero core-producer phases during unchanged refreshes;
all phase boundaries pair correctly. Spectre's two scopes and all four invented fixture lanes
in each corpus job also pass. Final reports are
`build/reports/public-acceptance/{intellij,spectre}-serial-32g-v9.json`.
Failed v4–v8 reports are not replaced or counted as passing results.

IntelliJ's three public cold refreshes take 1,375.032, 1,430.600 and 1,338.669 seconds. Exact
`ApplicationManager` query p95 values are 5.877, 2.778 and 6.071 seconds respectively, over 100
measurements per repeat; unchanged refreshes still take approximately 18–19 seconds. Exact-FQN
queries in that v9 artifact scan the symbol namespace. These are material latency limitations, not a
performance pass: the host is contended and timings remain report-only. The job's accounted-memory
spot check reached its enforced 16 GiB ceiling, including page cache, with memory-limit pressure
but zero observed OOM events or kills. Passing on this host does not establish spare memory headroom.

The v10 performance build passes both corpus jobs, including three public repeats and a separate
instrumented repeat per scope, all four fixture lanes per job, exact inventories and cleanup.
Reports are `build/reports/public-acceptance/{intellij,spectre}-serial-32g-v10.json`; driver SHA-256 is
`bba92794a51a39485939f74474d8176bdbe27e629d131011f9afacbf81b3bf88`.
IntelliJ still captures exactly 65,149 inputs with matching generations across all repeats.
Cold public refreshes take 709.146, 850.350 and 973.044 seconds: median time falls 38% from v9.
Exact `ApplicationManager` query p95 is 0.095–0.115ms, measured inside an acquired snapshot over
100 samples per repeat. Unchanged refreshes remain 17.9–20.0s and fresh in-process client reopen
remains 13.95–14.10s. Spectre's six public repeats have query p95 between 0.079 and 0.220ms.
These are same-host observations with uncontrolled OS cache, not an IntelliJ IDEA comparison or
a worst-case latency guarantee.

The v10 diagnostic confirms zero core-producer phases on unchanged refreshes. Its Java phase
takes 348.364s versus v9's 1,066.248s, while Kotlin takes 359.334s versus 260.109s; the latter is
a slower observation, not an improvement claim. Both producer phases remain substantial.
Total disposable-tree logical bytes increase from 77.19GB to 87.26GB; this includes the checkout,
tools and four independent caches, not just index payloads. The auxiliary name index adds storage
work, but these aggregate measurements do not isolate its entire cost. Peak accounted job memory
is 15.85 GiB including page cache, close to the 16 GiB limit. Passing still does not establish spare
memory headroom, and no RSS or per-phase peak measurement is inferred from cgroup accounting.

The v11 build adds per-file Kotlin write transactions and indexed source-identity lookup for
resource metadata. Both changes have assertion-red/green regressions: unbatched Kotlin writes
are rejected while forward-call parameter names remain correct, and repeated metadata queries
must not rescan the inventory while preserving distinct origin namespaces. The ordinary suite
contains 709 passing tests, including the existing bounded-heap and source/resource contracts.

Both v11 corpus jobs pass their unchanged inventories, public/instrumented repeats, four fixture
lanes and cleanup. Driver SHA-256 is
`d9e42ae5a2478aeb18190832a36d006690c84f7e7b399aa7a1182765d9926f5d`; reports are
`build/reports/public-acceptance/{intellij,spectre}-serial-32g-v11.json`.
All three IntelliJ public cold refreshes are below the requested nine minutes: 335.278, 477.982
and 450.176 seconds (median 7m30s). All 300 exact `ApplicationManager` query samples are below
one second: the largest is 0.274ms, with per-repeat p95 of 0.119–0.160ms. These are warm queries
against an acquired snapshot, not process launch or cold-client latency. All 65,149 inputs and
the published generation remain identical to v10; no correctness assertion or scope was reduced.
Unchanged refreshes still take 16.6–19.0s; fresh in-process client reopen takes 11.47–12.50s.

The separate v11 diagnostic also passes, with no core-producer work on unchanged refreshes.
It additionally had 120 seconds of JFR profiling, excluded from the three public measurements;
its Java/Kotlin phases take 227.886s/97.633s. The raw recording remains local because it can
contain host metadata. Total disposable-tree logical bytes fall to 62.69GB, but peak accounted
memory again reaches the 16 GiB cap including page cache. These observations meet the requested
targets on this host; they do not establish an isolated-host SLA or spare memory headroom.

Real Linux detached-child containment tests now pass on the authorized host. An earlier attempt
with a writable parent but no delegated child controllers failed before spawning; partial
initialization cleanup removed only its new child. Constructor checks retain that actionable
delegation failure. Fake-command tests are not a substitute for these actual process tests.

Age/quota/grace-period and daemon-purge cache policies are **not implemented** and have no passing
probes in this harness. The separately developed collector protects live activity conservatively
and follows current/overlay reachability; those safety checks do not establish retention-policy
coverage. Corpus readiness depends on the actual full-scope reports, not those policy claims.

### Opt-in sibling-worktree lifecycle soak

`LifecycleSoakAcceptanceDriver` uses only a freshly invented repository, two detached sibling Git
worktrees, and a private cache under a new disposable root. It disables Git signing per invocation,
ignores global/system Git configuration for fixture creation, and uses empty hooks/templates.
It neither reads a corpus nor modifies the caller's Git configuration. The existing test-output
archive includes the driver without additional build or workflow wiring.

With an extracted checksummed driver and an existing report parent directory, run:

    java -Xmx2g --enable-native-access=ALL-UNNAMED \
      -cp "$DRIVER/test:$DRIVER/main:$DRIVER/lib/*" \
      dev.sebastiano.indexino.acceptance.LifecycleSoakAcceptanceDriver \
      "$SCRATCH/new-soak-root" 50 "$REPORT"

The root must not exist and the report must be outside it. The cycle count is bounded to 2–200;
the focused smoke uses two cycles, while an acceptance soak uses at least 50. Each cycle edits and
refreshes **both** worktrees through fresh in-process clients: 50 cycles means 100 mutations.
Public symbol queries use page size one and assert the exact symbol, relative file and line.
Short-lived pins must still return their previous generation after refresh; two seed pins survive
their original clients and remain readable across the entire run. Existing maintenance GC must
leave packs unchanged while those pins are active. After every client/pin closes, materialized
reference files must be absent, GC must reclaim obsolete packs, and reopened public snapshots must
still return both current symbols. The owned repository, worktrees and cache are then deleted.

Each completed cycle records reference-file/materialization counts, logical cache bytes, JMX live
platform threads, JVM heap/non-heap usage, open FDs where supported, and observed descendant process
counts. Baseline, post-close and post-GC snapshots support resource-trend comparison. These are
boundary samples, not peaks or leak thresholds. RSS and allocated disk bytes remain null with
explicit reasons; logical file sizes are not allocated blocks. Descendant observations **do not
prove containment**, and this in-process soak does not exercise daemon teardown. Age, quota, grace
and daemon-purge policy statuses remain explicitly `not-implemented`.

The driver has a ten-minute cooperative coroutine deadline and thirty-second Git command bounds.
Failures remain nonzero, retain completed-cycle JSON with only the exception class, restore the
cache property, and attempt cleanup of only the newly created root. No resource trend substitutes
for the separate isolated-runner/containment gates, and no corpus readiness is implied.

The same v9 packaged driver passed a fresh Linux 50-cycle/100-mutation soak after both corpus jobs.
All old/current rows and 50 protected-GC checks passed. References stayed at four files/two
materializations during cycles, then zero after close; GC reclaimed 99 obsolete packs and both
reopened current snapshots still queried correctly. Root cleanup passed. The report is
`build/reports/public-acceptance/lifecycle-soak-linux-v9.json`; resource observations remain
report-only and the unimplemented policies above are unchanged.

The exact v10 performance artifact also passes the Linux 50-cycle/100-mutation soak after both
corpus jobs. All old/current rows, seed pins and protected-GC checks pass, references drop to zero,
99 obsolete packs are reclaimed, reopened queries pass and the disposable root is removed.
Its separate report is `build/reports/public-acceptance/lifecycle-soak-linux-v10.json`.

The v11 artifact repeats the same successful 50-cycle/100-mutation Linux soak after its corpus
and distribution checks. All 100 old/new row pairs, seed pins and protected-GC checks pass;
references drop to zero, 99 obsolete packs are reclaimed, reopened queries and root cleanup pass.
The report is `build/reports/public-acceptance/lifecycle-soak-linux-v11.json`.

### Opt-in performance experiments

The acceptance archive also contains three test-only drivers. Use its extracted
`test:main:lib/*` classpath and an existing report parent. Run comparisons serially with the same
JVM/heap; none is an IntelliJ IDEA comparison or an isolated-host performance guarantee.

* `JavaIndexingPerformanceDriver <fresh-root> <report.json> 500 20` generates 500 Java files with
  forward cross-file calls, indexes into real Xodus, and checks exact declarations and resolved
  parameter names. It reports wall time and delegated storage-operation counters. The initial
  same-host comparison reduced 62,000 writes to 31,000 for the same 31,000 facts and wall time from
  5.221s to 3.234s after per-file transactions, disjoint declaration/use passes and the symbol-name
  index. Transaction commit time is outside the per-put timer; use wall time for comparison.
* `SymbolQueryPerformanceDriver <fresh-root> <report.json> 100000` seeds and reopens a persisted
  index with 100,001 symbols, then checks exact returned names, declaration lines and owner IDs.
  It records 100 public snapshot queries after ten warmups for FQN, short name, alias, a ten-row
  prefix and a miss. It excludes parser work, snapshot acquisition and process launch. Against
  the retained v9 production classes, median hits were 499–502ms; indexed lookup measured
  0.085–0.211ms, with hit p95 at 0.124–0.398ms. Broad prefixes and unnamed scans are not covered by
  that latency result. Fresh disposable roots are removed; reports remain outside them.
* `SymbolCodecPerformanceDriver <report.json> 50000 [--verify-serializer-reuse]` alternates JSON,
  reused-serializer JSON and a test-only explicit binary codec. It checks byte equality between
  JSON paths and round-trips all symbol fields, including Unicode, aliases and nulls. On this
  JVM, reusing the JSON serializer reduced temporary decode allocation from 424,955,112 to
  143,755,112 bytes and median decode time from 112.5ms to 50.7ms. These are allocated bytes, not
  live heap or RSS. The optional allocation regression requires enabled HotSpot thread-allocation
  counters and allows production at most 10% above the same-process reused-serializer control;
  the actual control failed before serializer reuse and passed afterwards.

Query samples run against an already acquired snapshot. Fresh `IN_PROCESS` client reopen is a
separate measurement in the same JVM, including generation restoration and store opening; neither
measurement includes process launch. Daemon connections route snapshot requests through one shared
in-process owner, so fresh-client reopen time must not be presented as warm-daemon query latency.
The corpus query samples also do not measure daemon protocol overhead.

The binary prototype encoded those 50,000 records in 11,873,210 bytes versus JSON's 19,142,593
(38% smaller), and decoded in approximately 20ms. It is deliberately not a production format:
it covers only symbols, not all fact families, format migration, malformed-input policy or full
publication/reopen costs. Production retains byte-compatible JSON with a reused serializer.
The query and transaction changes address unnecessary work independently of payload encoding.
Raw experiment reports live under `build/performance/`; do not publish raw JFR recordings, which
can contain host metadata.

Repeating the Java and query experiments from the exact final v10 archive gives 3.220s for the
same 31,000 Java facts/writes and hit query p95 of 0.174–0.388ms. The reports are
`build/performance/java-v10.json` and `build/performance/query-v10.json`; these do not replace the
earlier baseline or intermediate measurements.

### Controlled incremental and worktree readiness

The test-only archive also includes `IncrementalAcceptanceDriver` and
`WorktreeAcceptanceDriver`. Both require an explicitly disposable root containing a
`.indexino-benchmark-owned` marker and `workspace/`; reports must be outside that root.
Never point them at a developer checkout. The worktree driver creates local commits, restores
the original heads and source bytes, and removes its own sibling worktrees. Declare nested
repositories in the plan's `nestedRepositories` list so sibling checkouts preserve their origins.

After changing default store-interface methods, rebuild nonincrementally before packaging a
measurement artifact. An observed incremental Kotlin build retained an older delegated snapshot
wrapper and silently used the scanning defaults. The shared-snapshot indexed-lookup regression
detects this; verify the packaged wrapper forwards both symbol-name and call-file lookups too.

`scripts/public-acceptance/incremental_plan.py` consumes an independently enumerated JSON list
of Bazel source paths plus `--bazel-rules-xml` from
`bazel query 'kind("rule", deps(//:main))' --output=xml`. It follows compilation `srcs` through
filegroups and aliases, not resource edges, and intersects the result with the source inventory.
This excludes Java/Kotlin-shaped build resources without consulting Indexino's output.
It selects one source, ten sources in one Bazel package, and one source
from every eligible Bazel package plus those ten. A package is the closest `BUILD`/`BUILD.bazel`
ancestor, not necessarily an IntelliJ IDE module. The plan records exact selected source hashes,
inventory hash, coverage and exclusions. Check those hashes against the pinned checkout before
launching the driver. Non-code and package/module descriptor files are not mutation targets.

    java -Xmx6g --enable-native-access=ALL-UNNAMED \
      -cp "$DRIVER/test:$DRIVER/main:$DRIVER/lib/*" \
      dev.sebastiano.indexino.acceptance.IncrementalAcceptanceDriver \
      "$OWNED_ROOT" "$PLAN" manual 3 "$REPORT"

Use `watcher` instead of `manual` for the separate daemon/watcher lane; Windows classpaths use
`;` instead of `:`. Pin the child JDK explicitly and use private home, temporary, Indexino and
Bazel directories. The driver reserves `$OWNED_ROOT/cache` for Indexino. Preserve completed
caches separately and start each lane with an empty Indexino cache. Dependency acquisition,
Bazel setup, process launch and the seed refresh are separate from the mutation samples.
The existing Linux cgroup supervisor can bound the job; the drivers themselves do not establish
cross-platform process containment or isolated-host readiness.

Each selected real source receives a controlled additional class. A sample renames that class,
changes a method from one parameter to two, and updates its caller. This is **not** a genuine
upstream cross-file semantic refactoring. Public queries verify exact source origin/file,
declarations, method arity, generation-local caller/callee IDs and argument positions. Old
snapshots stay pinned and unchanged sources are checked after publication. Original bytes are
restored when the driver returns. A missing caller is an assertion-tested negative control.
The incremental caller-in-file check reads bounded pages of 1,000 calls while retaining exact
duplicate/omission and caller assertions; other acceptance pagination checks still use one-item
pages. This avoids repeatedly rescanning the same large file once per call during verification.

`writeNanos` measures all writes; `readyNanos` starts after the last write and includes manual
refresh or watcher publication, snapshot materialization and the changed-source query checks.
`totalNanos` includes both. Watcher polling has a 50ms interval. Seed, edits (including writes,
refresh and query verification) and baseline resets each have a 10-minute cooperative deadline,
and the entire driver run has an independent 10-minute cooperative deadline. These are recorded as
`stageWaitMillis`, `seedWaitMillis`, `runWaitMillis` and, for compatibility, `watcherWaitMillis`
in the report. Two optional trailing arguments, `<seed-wait-minutes> <run-wait-minutes>`, set a
separate seed bound (at most 120) and a longer run bound (at most 240, above the seed bound) for
a deliberately bounded seed of a large corpus; edits and resets keep their 10-minute deadline.
`seedReadyNanos` (split into `seedRefreshNanos` and `seedVerifyNanos`) is a seed measurement, never
edit latency. Before each watcher edit the driver waits until no refresh is active and the scope is
not dirty in two consecutive polls; that wait is `preEditSettleNanos` and is outside `readyNanos`.
Reports carry filtered refresh journals (`seedRefreshes`, per-sample `settleRefreshes` and
`refreshes`, `resets`, and `failureRefreshes`) with summed `phaseMillis`; lines naming the owned
root are dropped. A daemon refresh is attributed only when it was observed active, so a very short
automatic refresh may be absent from the evidence. Watcher samples add `watcherTiming`: polled
refresh windows, when a new generation first became visible, and the final snapshot acquisition
and query durations. Edited bytes are deterministic, so against a preseeded cache an edit can
recreate an earlier run's generation and repoint to it, including its full materialized store.
`-Dindexino.acceptance.editNonce=<id>` appends a comment naming that id to edited (V1) bytes only,
keeping baseline bytes and seed freshness unchanged; the report records `editNonce`. A
timed-out run fails even when stored facts look correct; use a separate process-tree supervisor
deadline with cleanup grace because coroutine timeouts do not interrupt blocking work or contain
child processes. Earlier 20-, 60- and 90-minute waits yielded correctness observations, not
acceptable incremental performance; they must not be treated as passes under the 10-minute budget.
Three repetitions are interleaved by workload size, with verified baseline restoration between
samples. No sample is silently discarded as warmup.
If an explicit baseline refresh joins an in-flight watcher publication from the prior edit, the
driver waits for a subsequent refresh and verifies the full V0 baseline before proceeding; a
returned refresh handle alone does not prove that restored source bytes were indexed. This reset
is outside the next sample's timing and retains the same bounded wait and query assertions.
On failure the driver records the active seed/edit/reset stage, and for watcher polling the last
observed generation, elapsed wait, and first unmet public predicate/plan ordinal. These private
diagnostics do not replace a passing public-query assertion.
Large-workload query verification costs more than the one-file verification, so these are
end-to-end checked readiness times, not parser-only timings. `watcherTiming.firstNewGenerationNanos`
records the first observed publication separately from `readyNanos`; changing the verification
page size changes the latter without necessarily changing publication latency. Compare timings
only for the same harness build and page size.

The incremental driver writes a JSON heartbeat to stderr every 30 seconds, and immediately at
seed/edit/reset transitions, with stage elapsed time, last public-query predicate and ordinal, and
for watcher runs the active daemon refresh's latest phase/counters when available. The heartbeat
polls the internal progress journal for one active refresh; it is private diagnostic output, not a
new public API or a substitute for public-query assertions. Build diagnostics mark topology,
source capture, source preview, checkpoint copying, change detection, each producer, store build
and publication start/completion with elapsed milliseconds, so an incomplete run can identify its
expensive phase. Origin-resolution diagnostics separately report each origin's source fingerprint,
Git-state read and manifest fingerprint durations; origins can run concurrently, so their times
must not be summed as wall time. Repeated overlays also time prior-delta restore; checkpoint release
and store close are timed separately so post-producer cleanup is not mistaken for analysis. Cleanup still
runs if a cancelled refresh rejects diagnostic progress callbacks.
Daemon progress is polled rather than forwarded through the in-process callback; initial startup
and gaps between phases may have no phase detail. Failed JSON reports retain the structured Indexino
failure code and category as well as the exception class, without raw failure messages or checkout
paths. No new full-corpus lane should start before its supervisor deadline and live log capture have
been verified on a fixture.

`WorktreeAcceptanceDriver <owned-root> <plan.json> <report.json>` reports a seed, same-commit
sibling/shared-cache readiness, fresh-cache control, diverged sibling, commit switch and
switch-back. Controlled branch transitions use detached Git checkouts; original named branches
are not moved. Git setup/checkout, connection, refresh and verification are measured separately.
The original V0 snapshot stays pinned. JSONL phase events record actual producer execution;
their presence does not itself prove zero-analysis reuse. This lane is instrumented, unlike
the manual/watcher public mutation timings. Completed samples are checkpointed on failure;
reports retain exception classes rather than raw exception messages. OS page cache and unrelated
host activity remain uncontrolled, and different host JDK vendors are recorded rather than
attributing every difference to OS/architecture.
