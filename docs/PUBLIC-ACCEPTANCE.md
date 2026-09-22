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
* **IntelliJ Community:** `//:main`, dependencies required, `bazel-query` required. No fallback to
  `//platform/jewel/ui:ui`; that label is only a named diagnostic. The independent Bazel query
  requests labelled source/generated files in the dependency closure. The denominator is local
  `//` `.kt`/`.java`/`.xml` source-file labels; external `@` labels, generated labels and other
  extensions are counted separately as exclusions. Missing/extra discovered paths fail; a count
  floor is never sufficient.

Inventory SHA-256 hashes UTF-8 sorted relative paths, one per line with a final LF. Duplicate
inventory entries fail. The driver uses the existing internal source-inventory callback only to
observe the actual set and reads the existing generation manifest only for source provenance:
actual topology mode, dependency inclusion and scope must match the required lane. These are not
semantic facts; symbol/reference/resource/lifecycle queries use public typed APIs.
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
Instrumented refresh boundaries derive `preDiscoveryCompletedNanos` (combined refresh setup,
topology and capture) and matched start/end durations per producer phase. Each refresh gets a new
reporter so terminal/counter state cannot leak across repeats. Incomplete phase pairs fail rather
than becoming zero-duration samples. Topology-only and total capture/hash fields remain null.
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

Separate `calls-manual` and `calls-watcher` lanes invent a unique top-level `ping` declaration and
caller files. They assert exact reference and call-site multisets after moving a call by editing,
adding a second caller, renaming a caller file, deleting it, and deleting the last caller. Callee
candidate IDs and enclosing caller IDs must match the current snapshot's independently selected
declarations by FQN, file and line. Both query classes receive 100 measurements after ten warmups. All five
mutation stages repeat five times with pinned-snapshot checks. Failed retrieval lanes do not skip
these independent lanes; all four fixture reports are retained and any failure gates corpus work.

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
no privileged persistent self-hosted fallback. This personal repository uses repository-scoped
ephemeral runners, not organization runner groups. Each runner label includes workflow run ID,
attempt and corpus; the operator supplies `INDEXINO_PUBLIC_CGROUP_PARENT` in its service environment.
Checking in the workflow does not provision runners or delegate controllers.
Actions use immutable SHAs; JBR, Gradle, Bazelisk and JetBrains Bazel downloads are checksum-pinned.
Only allowlisted JSON reports up to 16 MiB are uploaded with seven-day retention, never corpus
trees, user homes, caches, or raw process logs. Workflow dispatch is a separate authorized action.

## Smallest supported path to two real corpus results

Read-only inspection on 2026-09-22 found **zero registered repository runners**. Neither the Mac
(no cgroup containment) nor the existing non-delegated Linux service is ready. Local fixture or
fake-command success cannot substitute for provisioning and exercising the following path:

1. An authorized operator provisions two independent disposable Linux x64 VMs, each with 64 GiB
   RAM and at least 200 GiB scratch. Do not place both on an oversubscribed shared host. Use an
   unprivileged runner account without sudo, cloud credentials, private mounts or long-lived secrets.
2. On each **new VM only**, the operator creates a dedicated systemd runner service with delegated
   CPU/memory/pids controllers and an empty parent available for job children. With a supporting
   systemd version, `DelegateSubgroup=runner` can keep the runner's own processes in a leaf; merely
   setting `Delegate=yes` is not proof. The operator enables the dedicated parent's controllers and
   exports its exact path as `INDEXINO_PUBLIC_CGROUP_PARENT`. Never repurpose the shared runner's
   parent, move its members, or change its controllers. See [systemd delegation documentation](https://www.freedesktop.org/software/systemd/man/latest/systemd.resource-control.html#Delegate=).
3. Verify actual creation, limits, timeout, detached-child kill and empty-child cleanup using the
   tiny `test_runner.py` test as the runner account. Then run resource preflight: host available
   memory is capped by every readable finite ancestor cgroup's remaining memory. A writable path
   alone never proves delegation or capacity. Keep this evidence with the eventual corpus reports.
4. Download the Linux x64 Actions runner pinned in `tools.json`; check its SHA-256 **before**
   extraction. Operator registration uses `--ephemeral --disableupdate` and only the label
   `indexino-public-RUN_ID-RUN_ATTEMPT-CORPUS` for the approved manual run, in addition to standard
   self-hosted/Linux/X64 labels. Use separate short-lived registration tokens; remove bootstrap
   credentials before job execution. Re-review/update the pin if GitHub's runner freshness policy
   rejects it; do not enable unverified automatic downloads. [Ephemeral runner guidance](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/autoscale).
5. After the integrated main commit and explicit dispatch authorization, select the capable profile.
   Build the driver once; register the two one-job VMs for that run's corpus labels. Both jobs use
   the exact same artifact checksum, private caches and `fail-fast: false`. Destroy each VM after
   its sole job, including cancellation/failure. A completed job is accepted only with exact
   source/query assertions, resource gates and verified cleanup; missing/skipped lanes stay unready.

Provisioning, runner registration, delegation, and dispatch are operator actions requiring separate
authorization. None has been performed by this harness. The workflow has no provisioner or cloud
credentials, so it cannot silently create infrastructure or incur capacity charges.

For local execution without Actions registration, read-only inspection found Colima 0.10.3 on
the 64 GiB ARM64 Mac. Its existing default profile is stopped and configured with only two CPUs,
2 GiB RAM and 100 GiB disk; it is inadequate and must not be reused or resized by this harness.
A separately authorized, disposable ARM64 Linux profile with approximately 40 GiB guest memory
and 200 GiB scratch is a candidate for **sequential**, independently isolated corpus runs once
host contention subsides. This is not verified infrastructure: guest/toolchain checksums, actual
available capacity and delegated-controller cleanup must pass before acquisition. Use the same
checksummed JVM artifact in both runs; no runner registration is needed for direct `run.py` use.
Never mount private host repositories or credentials into the guest. Creating, starting, configuring
or deleting a VM requires separate authorization; the existing profile remains untouched.

## Local validation and unresolved readiness

The initial fixture run exposed syntactic receiver-resolution and simultaneous-snapshot failures.
After the corresponding production repairs, the integrated Mac run matches all eleven syntactic
cases exactly and measures all thirteen cases 100 times after warmup. The two independently
labelled same-arity semantic cases still report precision 0.5 and recall 1.0; they remain explicit
compiler-resolution gaps, not syntactic failures. No fixture labels were weakened.

Both manual lanes pass all 25 mutation stages, including pinned snapshots. The caller lane also
passes 100 exact reference and 100 caller measurements. The Mac watcher lanes expose a separate
snapshot-materialization move failure (`Directory not empty`); their nonzero exit/checkpoints
remain failures. The caller watcher completed edit/add/rename before failing during deletion.
Do not serialize pins, retry away the failure, or infer success from the independently passing
Linux watcher runs. These contended local timings are correctness evidence, not performance
baselines. Public corpus lanes have not run, and no corpus performance result is claimed.

The real detached-child containment test remains unverified. A Linux runner with a writable
cgroup directory but no delegated child controllers failed before spawning; partial initialization
cleanup removed only its new child. Constructor checks now fail with an actionable delegation
message. Fake-command tests are not a substitute for executing that test on the provisioned runner.

Age/quota/grace-period and daemon-purge cache policies are **not implemented** and have no passing
probes in this harness. The separately developed collector protects live activity conservatively
and follows current/overlay reachability; those safety checks do not establish retention-policy
coverage. Public corpus readiness remains blocked until real infrastructure and containment pass.
