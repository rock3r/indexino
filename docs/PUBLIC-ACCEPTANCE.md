# Public acceptance and benchmark harness

This opt-in harness uses only pinned public repositories and invented fixtures. It is not a
release, publication, or compiler-resolution claim. Expensive corpora are outside `test` and
`check`; the small driver contract tests remain ordinary tests. No test API is published.

## Build and cheap checks

    python3 -m unittest discover -s scripts/public-acceptance -v
    ./gradlew :test --tests '*PublicAcceptanceDriverTest'
    ./gradlew publicAcceptanceDriverChecksum

The last task builds `build/public-acceptance/public-acceptance-driver.zip` and its SHA-256 file.
The ZIP contains the exact test/main outputs and runtime dependencies, not a CLI release. Build
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
* **IntelliJ Community:** `//:main`, dependencies required, `bazel-query` required. No fallback to
  `//platform/jewel/ui:ui`; that label is only a named diagnostic. The independent Bazel query
  requests labelled source/generated files in the dependency closure. The denominator is local
  `//` `.kt`/`.java`/`.xml` source-file labels; external `@` labels, generated labels and other
  extensions are counted separately as exclusions. Missing/extra discovered paths fail; a count
  floor is never sufficient.

Inventory SHA-256 hashes UTF-8 sorted relative paths, one per line with a final LF. Duplicate
inventory entries fail. The driver uses the existing internal source-inventory callback only to
observe the actual set; symbol/reference/resource/lifecycle queries use public typed APIs.
Public declaration IDs are generation-qualified opaque IDs: reference queries first select the
independently specified FQN, file and declaration line, then use the returned ID.

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
Unavailable metrics remain null or explicitly labelled unavailable, never zero. Instrumented
timings are not mixed with public API latency. Query timings include typed pagination and assertion
overhead, but exclude CLI/JVM launch; command wall timings include launch and the complete driver.

The invented `retrieval-v1` ground truth is owned by `docs/RETRIEVAL-FIXTURES.md`. It separates
syntactic correctness gates from semantic precision/recall gaps and marks held-out labels.
The fixture driver compares exact row multisets after explicit language/file filtering. It never
weakens semantic labels to match candidate resolution. Manual and watcher lanes use separate
disposable copies, apply all edit/add/rename/delete stages five times, verify exact locations and
generation changes, and verify previously pinned snapshots remain unchanged. Watcher mutations
must converge without a manual refresh for that mutation; restoration between repeats is explicit.

Reports retain raw samples and nearest-rank p50/p95 (`ceil(p*n)-1` in zero-based sorted samples),
artifact digest, public pins, scope, cache assumptions, machine and Java information. Linux cgroup
CPU deltas include all job-owned descendants; `memory.peak` is accounted memory **not RSS**.
Unsupported per-phase RSS and disk-peak measurements are null with reasons. Performance is
initially report-only. Correctness, exact source coverage, resource limits and cleanup gate success.

## Containment is a prerequisite, including detached servers

macOS and Windows full corpus lanes are **not ready** in this implementation. A process group or
PID-tree walk does not prove cleanup of a detached Bazel server. Do not run both corpora on a shared
Mac merely because physical RAM exceeds the public Bazel rc's 12 GiB heap request.

The supported runner profile requires a single-use, isolated Linux VM with cgroup v2, at least
32 GiB **available** memory and 150 GiB free disposable disk (workflow profile: 64 GiB physical,
200 GiB scratch), JDK 25 and Python 3.11+. An operator must pre-delegate memory/pids controllers.
The runner creates only a unique `indexino-<uuid>` child beneath the supplied parent; it never
moves existing processes or writes the parent's `cgroup.kill`. No sudo, service restart, controller
enablement, or permission repair is performed. Memory is capped at 28 GiB, swap at zero, processes
at 1024; individual command timeouts are bounded. Low free disk and oversized command output abort.

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
the capable ephemeral runner profile. Its two corpus jobs use `fail-fast: false`, unique artifacts,
isolated caches and a single checksummed driver build. There is no untrusted PR execution path and
no privileged persistent self-hosted fallback. Configure the dedicated ephemeral runner group and
`INDEXINO_PUBLIC_CGROUP_PARENT` separately; checking in the workflow does not provision them.
Actions use immutable SHAs; JBR, Gradle, Bazelisk and JetBrains Bazel downloads are checksum-pinned.
Only allowlisted JSON reports up to 16 MiB are uploaded with seven-day retention, never corpus
trees, user homes, caches, or raw process logs. Workflow dispatch is a separate authorized action.

## Initial validation and unresolved readiness

The initial Mac run used the invented fixture in-process only. All 13 query cases were measured
100 times after warmup. Nine of eleven syntactic cases matched exactly; `kotlin-alias-zero-arity`
returned `Use.kt:14` in addition to `Use.kt:7`, while `shadowed-receiver` omitted `Use.kt:14`.
Both independently labelled same-arity semantic cases reported precision 0.5 and recall 1.0.
These semantic gaps are separate from the two syntactic failures; labels remain unchanged.

Lifecycle execution then failed while opening a second same-generation snapshot with the first
still pinned (Xodus environment lock timeout). The driver preserves a query checkpoint marked
`incomplete` and exits nonzero. It does not serialize or discard the required pins to hide this
failure. Production retrieval and lifecycle repairs are separate workstreams. Watcher and public
corpus lanes have not passed, and no corpus performance result is claimed.

The real detached-child containment test remains unverified. A Linux runner with a writable
cgroup directory but no delegated child controllers failed before spawning; partial initialization
cleanup removed only its new child. Constructor checks now fail with an actionable delegation
message. Fake-command tests are not a substitute for executing that test on the provisioned runner.
