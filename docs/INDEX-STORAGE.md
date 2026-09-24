# Index storage

Persistent on-disk layout for **indexino**.

> **Binding contract:** This document matches
> [PUBLIC-API-DESIGN.html](PUBLIC-API-DESIGN.html) (Accepted 2026-07-25).
> Pre-contract layouts under `<project>/.indexino/index/<commit>/` are a **non-goal to migrate** —
> wipe and rebuild. Implementation still shipping that layout must treat it as transitional and
> must not extend it.

## Cache is out of the worktree

Nothing Indexino writes belongs inside the checkout. Cache, generation manifests, staging, runtime
sockets, leases, journals, and tombstones live under a **user-local root**:

| Resolution order | Path |
|------------------|------|
| 1. Explicit | `$INDEXINO_CACHE_DIR` |
| 2. XDG | `$XDG_CACHE_HOME/indexino` |
| 3. macOS default | `~/Library/Caches/indexino` |
| 4. Other | `~/.cache/indexino` |

Indices are **per-machine**. Do not gitignore a project-local store for the product layout — there
is none. `git clean -xdf` on a worktree must not delete the user-local cache.

### Why not `.indexino/` in the project?

| Concern | Project-local `.indexino/` | User-local cache |
|---------|---------------------------|------------------|
| `git status` dirt | Requires gitignore / exclude | Never |
| AF_UNIX path length | Worktree paths often exceed 102 chars | Short fixed root |
| Sibling worktrees | Separate copies | Shared content-addressed chunks |
| `git clean` | Destroys index | Manifest rebuild only |

Upstream in-app work (#814) used `.agent/`; this CLI deliberately does **not** write into the
worktree for product storage.

## Layout

```
<cache-root>/                          # see resolution order above
  chunks/<ab>/<cd>/<content-key>       # immutable packs (two-level fanout)
  origins/<origin-cache-id>/inventory
  workspaces/<workspace-id>/
    generations/<generation>/manifest
    staging/<refresh-id>/manifest
    current                            # atomic pointer
    refs/<runtime-or-snapshot-id>
    source-links/registry.json           # dependency-to-source link registry snapshot
    source-links/generations/<link-generation>/snapshot.json
    change-journal
    legacy-store/                      # S1 bridge; removed as one unit in S2
      index/<commit>/                  # mutable incremental writer
      clients/<client-id>/generations/<generation-id>/store/ # per-client immutable snapshot copy
  registry/workspaces.json             # path → fs identity, generations, last-used
  registry/tombstones/
  runtime/<workspace-id>.sock          # AF_UNIX (all platforms)
  runtime/<workspace-id>.lease.json
```

### What a chunk is

A **chunk** is an immutable content-addressed **pack** of related analysis facts for one
analysis-key + content-key, stored as a file under the fanout directory—not one Xodus environment
per source file. Xodus (or equivalent) may hold generation-local indexes that **reference** chunk
IDs. Put-if-absent installs packs; readers open by content key.

Plugin facts are the same mechanism under plugin analysis identity (see plugin SPI in the design
doc). Payload values use the durable `PluginFactValue` model in `indexino-model`.

### Workspace identity

- `<workspace-id>` is the first 16 hexadecimal characters of SHA-256 over the canonical workspace
  path. The fixed 64-bit identifier keeps the S6 runtime socket below macOS's 102-character budget.
- **Not** “one Git commit = one store directory”.
- A workspace generation is a **composite** manifest pinning topology + origin shards + link
  generation (public types are composite even when the first engine is one-shard).
- Git commit is **provenance** for a Git origin and a delta anchor, not the primary cache key.
- Non-Git origins use a durable filesystem-origin identity.
- Android `repo` projects use `repo:<manifest-project-name>` identity, never their local mount
  path; the resolved manifest revision is recorded as expected origin provenance. If a resolved
  manifest contains duplicate project names, each conflicting mount is disambiguated as
  `repo:<manifest-project-name>:<mount-path>`.

### Runtime (not storage API)

AF_UNIX socket path budget is **102 characters** on macOS (bind fails at 103). Paths must stay
under the short user-local root. Peer identity comes from the lease file.

## Logical fact namespaces

Logical key prefixes remain useful for mental models and generation-local indexes:

| Prefix / family | Owner | Purpose |
|-----------------|-------|---------|
| symbols / calls / refs | Core | Kotlin/Java definitions, call graph, references |
| resources | Core (S10) | Android/CMP resource identity (deferred public API) |
| file hashes | Core | Content-key inputs for packs |
| plugin namespaces | Plugins | Namespaced facts under plugin ID + `PluginFactSchemaVersion` |
| meta | Core | Indexer / schema versions, generation metadata |

Definitions remain location-qualified so overloads and duplicate configurations do not collide.
`BasicFactSchemaVersion` is the core schema coordinate; plugin schemas are per-plugin integers
(`PluginFactSchemaVersion`).

Basic fact schema version 3 adds the mandatory persisted declaration column to symbol facts.
Declaration and reference lines and columns are 1-based. Kotlin and Java symbols point to the
declaration's syntactic start; XML value resources point to the opening `<`, ID resources to the
`@+id/…` token, and path-derived resources to line 1, column 1.

Version 4 is a compatibility reset for incorrectly attributed Kotlin shadowed-receiver facts; the
record shape is unchanged. The current engine has no separate core-analyzer revision coordinate:
the indexer version invalidates incremental writers, but reopening a published generation checks
only the basic-fact schema coordinate. This reset therefore rejects old published packs and forces
unchanged sources through the writer again. A warm-cache regression seeds schema-3 wrong reference
and call targets, checks that they cannot be reopened as current, and verifies corrected public
queries after refresh. Local and remote snapshots report this same core schema coordinate, not a
separate facade version. This is not a general requirement to bump fact schemas for bug fixes.

Version 5 persists the code/non-code analysis role in file-hash records. Aggregate source hashes
and origin fingerprints include that role as well as captured content hashes. A build-role change
therefore invalidates analysis even when bytes are unchanged: code-to-resource removes stale
language facts, and resource-to-code runs language analysis. Both roles retain captured bytes,
file hashes and provenance. Existing published packs must rebuild because their extension-only
language facts did not respect build roles. Production/test classification is unchanged.

## Query path (product)

1. Connect to the workspace runtime (or in-process engine in early slices).
2. Pin a published generation (`snapshot(PUBLISHED)` or after refresh / `AWAIT_CURRENT`).
3. Query through `IndexSnapshot` / `BasicFactQueries` — never by opening raw packs from callers.

Named symbol queries do not scan every symbol in newly written Xodus stores. Exact FQN queries
seek the location-qualified primary-key prefix (and the legacy exact key). Short-name, alias and
prefix queries use a derived `SymbolNames` duplicate store mapping each term to its primary key.
Owner resolution uses the same index while retaining origin/file precedence and primary-key tie
ordering. Public result sorting and pagination are unchanged; broad unnamed queries still scan.

File-scoped call queries use a derived `CallFiles` duplicate store mapping a length-prefixed
origin ID plus origin-relative file path to primary call keys. Call candidate resolution uses
`SymbolNames` while retaining FQN/alias filtering; a matching short name alone is not a candidate.
Call-file entries participate in replacement, deletion and rollback transactions. Writable legacy
stores backfill once, and read-only legacy stores retain the scanning fallback. Overlay lookups
mask tombstoned and replaced base keys even when a replacement moves to another file or origin.
Unrestricted call queries and opaque enclosing-symbol ID resolution still scan.

The name index is updated in the same transaction as primary records, including replacement,
deletion and rollback. Xodus contextual transactions let nested store operations participate in
the enclosing batch. Java analysis batches writes per file/pass and emits declarations only in
the first pass, then references/calls/resource usages in the second pass. Both Java passes share
an explicitly closed standard file manager with an empty user classpath. Syntax-only parsing does
not need host dependencies or auto-starting javac plugins; `-proc:none` alone does not exclude them.
Kotlin also batches fact
writes per file; it still retains declaration metadata rather than project-wide syntax trees.
Build contexts lazily index source identities for metadata lookup, avoiding repeated inventory
scans while keeping origin-specific captured-source reads and unindexed-file fallback unchanged.

XML origin cleanup skips scans when no resource sources are affected. Otherwise it streams each
record family, retaining only matching deletion keys rather than decoded whole-index records, and
deletes those keys in bounded transactions. Origin and relative path both participate in matching.

This is derived lookup data, not a basic-fact schema change. A writable legacy environment builds
the index once; failed initialization releases its environment lock. Read-only legacy snapshots
remain readable through a scanning fallback and are not modified. Overlay queries suppress base
records hidden by tombstones or replaced in the delta, even when the replacement no longer matches
the queried name. Record payloads remain byte-compatible JSON with one reused serializer, rather
than rebuilding polymorphic metadata per record. Binary payloads are a measured experiment, not
the production format.

## Invalidation and reuse

| Event | Action |
|-------|--------|
| Unchanged inputs | Reopen published generation; zero analyzers |
| File edit | Recompute invalidated packs + declared post-process closure |
| Schema / plugin / analyzer bump | Invalidate affected analysis keys |
| New worktree, same machine | Share chunks; new workspace id + current pointer |
| Confirmed workspace loss | Tombstone; abandon worktree staging/refs; keep shared chunks until GC |

A refresh captures each discovered source once before analysis. Change detection, analyzers,
per-file content hashes, the aggregate source hash, and origin source fingerprints all consume that
immutable refresh snapshot. If a file changes after capture, the published generation remains
internally consistent with the captured bytes and the next refresh detects the newer content. A
read or refresh may instead fail and retry, but no generation may publish facts beside a hash from a
different read of the file.

### Reclamation

1. **Reference-based** — never drop packs reachable from `current`, pinned snapshots, or staging.
2. **Age** — unreferenced packs / dead workspaces ~30 days.
3. **Quota** — backstop; never below one complete generation per live worktree without force.

CLI-only operators: `indexino cache status|gc|forget` and `daemon stop --purge`. Explicit
last-used in the registry (not filesystem `atime`). GC grace window + re-verify before unlink.

Current `cache gc` implements conservative activity exclusion and current-generation/overlay
reachability. In-process clients hold a shared OS lock on `activity.lock`; snapshots that outlive
client close retain that lease, and refreshes retain independent leases until their work ends.
GC takes the exclusive lock for its entire reachability scan and deletion, or reports
`activeRuntime=true` without deleting packs. This prevents publication and pinned-generation races
across cooperating processes. It intentionally defers all reclamation while any client is live.
Age/quota policy, a persisted grace window, and `daemon stop --purge` remain follow-up work rather
than guarantees of the current collector. All participating processes must use the updated locking
protocol; stop older in-process clients before running this collector against their cache.

## S2 implementation layout

Refresh writes mutable incremental output only beneath
`workspaces/<workspace-id>/staging/in-process-writer/`. On a completed refresh, indexino installs an
immutable content-addressed pack in `chunks/<ab>/<cd>/<content-key>`, writes a generation manifest
under `workspaces/<workspace-id>/generations/<generation-id>/manifest.json`, then atomically updates
that workspace's `current` pointer. The manifest records the basic-fact schema coordinate, ordered
origin graph (origin ID, actual revision, expected revision when applicable, and origin-local state
fingerprint), workspace revision fingerprint, and pack keys. Older single-origin entries remain
readable through their legacy workspace-origin fields.

The staging writer is populated from one immutable per-refresh source snapshot. Pack facts and the
file-hash records inside that pack therefore always describe the same captured content, including
when the checkout changes while analysis is running.

Before mutation, the build owner streams the physical writable store into a temporary Xodus
checkpoint beside the compatibility manifest (`manifest.json.rollback/`), while retaining the
writer's existing Xodus lock. The checkpoint also preserves the prior manifest's bytes or absence.
An overlay checkpoints only its writable delta, never its inherited read-only base. Producer and
plugin failures restore that checkpoint using bounded key batches and streamed records. Capture
and restore copy at most 16 records per transaction; rollback deletes at most 256 keys per
transaction, rather than committing each record individually. Rollback
and close failures are suppressed onto the original failure rather than replacing it. Successful
builds and successful restores close the checkpoint store, make its regular files writable (Xodus
leaves completed logs read-only), and remove it without following symbolic links. Filesystem deletion
errors retain their affected path and cause. A failed restore retains the checkpoint, without changing
its file permissions. Subsequent builds reject the active/unfinished checkpoint before accepting even
a fresh compatibility index.
The diagnostic `checkpoint` phase measures this record-copy capture separately from change
detection and producers; it does not change the checkpoint or rollback semantics.
This is recovery for mutable staging, not a crash-atomic transaction or an automatic crash-recovery
service. Preserve retained checkpoints for investigation; removing only the checkpoint can expose
partially restored staging as valid. Published generation manifests and immutable packs are not
rewritten by this rollback.

Each client materializes a referenced immutable pack atomically into its own
`workspaces/<workspace-id>/refs/<client-id>/<generation-id>/store/` directory before opening a
snapshot. Simultaneous snapshots in one client share a reference-counted read-only Xodus environment;
different clients open their own copies because Xodus locks even read-only environments exclusively.
Publication and snapshot restoration may compete to materialize the same immutable destination.
If another complete directory wins the rename, its copy is reused and the losing staging directory
is removed. A move failure without a directory at the destination still propagates.
Closing one snapshot does not close another snapshot's environment. Snapshot pins retain those
caller-owned refs until close. Client-owned overlay base copies are tracked alongside the snapshot
pins and reclaimed after the last pin closes; shared packs remain immutable and
are reclaimed only by reachability/age/quota GC. There is no runtime `legacy-store` layout. Do **not**
extend `<project>/.indexino/index/<commit>/`; new features must assume user-local composite storage.

## Deprecated / rejected paths

- `<project>/.indexino/` as product cache or runtime root
- `.compose-selection-index/` — early sketch
- `.agent/` — upstream #814 name only; not used by this CLI
