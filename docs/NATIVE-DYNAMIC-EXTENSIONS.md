# Native dynamic extensions

Out-of-process extension protocol for **closed-world** Indexino distributions (R8-shrunk JAR and
native ZIPs). Binding contract:
[PUBLIC-API-DESIGN.html](PUBLIC-API-DESIGN.html) — deferred decision “Dynamic extensions in native
distributions”.

JVM thin/fat distributions keep **in-process** dynamic plugin loading. Native and shrunk
distributions never load arbitrary extension bytecode inside the workspace runtime; dynamic checks
run in a separate JVM worker connected over a versioned local socket protocol.

## Capability matrix

| Distribution | Basic index/query | Bundled compiled plugins | External plugin JAR | `.indexino.kts` |
|---|---|---|---|---|
| JVM thin/fat JAR | Yes | In-process | In-process (explicit path) | Yes (script-host) |
| R8 shrunk JAR | Yes | In-process if bundled pre-shrink | **Out-of-process worker** | No |
| Native ZIP | Yes | In-process if bundled pre-shrink | **Out-of-process worker** | No |

Static bundling of known compiled plugins (selection-context, compose-decoration) is unchanged:
those providers remain on the host classpath and run in-process during refresh and check execution.

## Threat and resource model

### Assets

| Asset | Owner | Failure impact |
|---|---|---|
| Workspace index store (packs, generation pointer, staging) | Daemon / workspace runtime | Corruption, stale reads, unbounded disk growth |
| Snapshot pins and client refs | Daemon | Indefinite retention, memory/disk pressure |
| Daemon process availability | Daemon | CLI/API outage for all clients |
| User workspace paths and cache layout | User (local) | Information disclosure via diagnostics |
| Plugin/check identity and ABI contracts | Host + plugin author | Wrong results, compatibility surprises |

### Adversaries and trust assumptions

- **v1 remains single-user local.** Any process running as the same OS user that can open the
  extension socket path or spawn the worker can participate. There are no capability tokens or
  multi-tenant ACLs beyond owner-only cache permissions.
- Plugin JARs and worker code are **explicitly trusted by path** (CLI `--plugin`, embedder
  configuration). Indexino does **not** provide a security sandbox.
- The out-of-process boundary isolates **daemon memory and index mutation** from extension crashes
  and runaway CPU in extension code. It does **not** prevent a malicious same-user process from
  reading the workspace tree, cache files, or extension socket if file permissions allow it.

### Threats → mitigations

| Threat | Mitigation |
|---|---|
| Untrusted extension bytecode in daemon/native image | Dynamic extensions execute only in a spawned JVM worker; host validates manifest/ABI before spawn and never `ClassLoader.loadClass` dynamic plugin types in closed-world mode |
| Extension crash | Worker process exit; host returns structured `PLUGIN` failure; session and snapshot pin released; index unchanged |
| Extension hang | Deadline + cancellation; process-group kill; host releases snapshot pin; daemon continues |
| Oversized/malformed frames | Length-prefixed frames with hard caps; reject before large allocation (same pattern as runtime protocol) |
| Protocol / ABI / schema mismatch | Versioned handshake rejects before query mediation or check execution |
| Stale snapshot token | Opaque per-session token required on every mediated query; host rejects unknown or expired tokens |
| Invalid findings (wrong plugin/check, blank message, out-of-range locations) | Host validates every finding before returning to clients |
| Resource exhaustion (queries, findings, concurrent workers) | Per-check query budget, finding cap, concurrent worker cap, worker heap cap |
| Workspace loss during extension run | Host closes sessions; worker receives shutdown; no indefinite pins |
| Daemon shutdown | Host cancels active extension sessions; workers terminated; pins released |
| Sensitive path leakage | Diagnostics use plugin/check IDs and remediation text; omit cache roots and workspace absolute paths |

### Operating-system isolation — provided vs not provided

**Provided (best-effort, same-user local model):**

- Separate OS process address space for dynamic extension code
- Process-group / job-object termination on timeout, cancel, or host shutdown
- Owner-only AF_UNIX socket path under the user-local cache root (short path budget)

**Not provided:**

- Seccomp, App Sandbox, Job ACLs, or WASM-style capability dropping
- Prevention of filesystem access by the worker to paths visible to the user
- Network egress blocking (worker JVM inherits user network access)
- Protection against a malicious same-user attacker with cache directory access

Do not describe this feature as a “security sandbox”. Document explicit trusted loading in CLI help
and extension failure messages.

## Protocol v1 (tracer)

Transport: AF_UNIX domain socket at `<cache-root>/runtime/e-<session8>.sock` (short path for the
102-character macOS bind budget). Length-prefixed binary frames (shared size limits with the
workspace runtime codec pattern).

### Handshake (fail before work)

Worker → host first frame after connect:

| Field | Type | Purpose |
|---|---|---|
| `protocolMajor` | int32 | Must match host `EXTENSION_PROTOCOL_MAJOR` |
| `protocolMinor` | int32 | Additive; logged only |
| `sessionToken` | UTF | Opaque host-issued token (proves spawn authorization) |
| `pluginId` | UTF | Must match requested check |
| `checkId` | UTF | Must match requested check |
| `pluginVersion` | UTF | Descriptor version from manifest |
| `pluginFactSchemaVersion` | int32 | Per-plugin fact schema |
| `pluginAbiTarget` | UTF | Manifest `Indexino-Plugin-ABI-Target` |
| `requiredBasicFactSchema` | int32 | Must be ≤ host basic fact schema |

Host → worker response:

| Result | Payload |
|---|---|
| `ACCEPTED` | `basicFactSchema`, `hostPluginAbiRange` |
| `REJECTED` | `code`, `message` (actionable, no internal paths) |

Mismatch on major, session token, identity, ABI interval, or unsupported basic fact schema rejects
with `INVALID_REQUEST` before any query is served.

### Mediated snapshot view

After `ACCEPTED`, the worker sends `RUN_CHECK`. The host serves a **read-only** query surface over
one pinned snapshot:

| Operation | Purpose |
|---|---|
| `PLUGIN_FACT_ENTRIES` | Prefix scan of plugin facts (paginated) |
| `PLUGIN_FACT_GET` | Single fact lookup |
| `PING` | Cooperative cancellation probe |

Each query carries the `sessionToken`. The host enforces a per-check **query budget** and returns
`INVALID_REQUEST` when exhausted. No store handles, file paths, or mutation operations are exposed.

### Completion

| Worker → host | Meaning |
|---|---|
| `COMPLETE_FINDINGS` | Ordered findings (validated) |
| `ERROR` | Structured failure |
| `CANCELLED` | Cooperative stop |

Host → worker: `CANCEL`, `SHUTDOWN`.

### Resource budgets (v1 defaults)

| Limit | Value |
|---|---|
| Max frame bytes | 1 MiB (64 MiB reassembled message cap) |
| Max concurrent extension workers per workspace | 2 |
| Max queries per check | 256 |
| Max findings per check | 10 000 |
| Default check deadline | 120 s (embedder/CLI may lower) |
| Worker JVM heap | 256 MiB (`-Xmx256m`) |

## Worker launch (closed-world)

The host spawns:

```text
<java> -Xmx256m -cp <extension-worker.jar> \
  dev.sebastiano.indexino.engine.extension.ExtensionWorkerMain \
  --socket <path> --session-token <token> --plugin-jar <path> \
  --plugin-id <id> --check-id <id>
```

- `<java>` resolves from `indexino.extension.java` system property, `JAVA_HOME`, or the installation
  JBR when packaged (native ZIP includes `indexino-extension-worker.jar` beside `indexino-cli.jar`).
- The worker JAR is the unshrunk compatibility CLI JAR in development; native packaging adds
  `indexino/indexino-extension-worker.jar` without loading it into the Roast process.

## Parity and proof

`ExtensionParityTest` runs the bundled selection-context
`interactive-in-selection` check in-process (JVM mode) and through the v1 extension worker,
asserting identical finding messages and ranges on the same fixture index.

Distribution contract tests assert capability matrix documentation stays aligned with
[DISTRIBUTIONS.md](DISTRIBUTIONS.md) and that closed-world mode routes dynamic plugin checks through
the extension host.

## Non-goals (v1 tracer)

- Mediating full `BasicFactQueries` (symbols/calls/refs) to extensions
- File analyzers or post-processors out-of-process (checks only)
- Hot reload or extension worker pooling across checks
- Cross-user or remote extension hosts
