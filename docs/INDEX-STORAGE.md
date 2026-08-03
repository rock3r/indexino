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

## Query path (product)

1. Connect to the workspace runtime (or in-process engine in early slices).
2. Pin a published generation (`snapshot(PUBLISHED)` or after refresh / `AWAIT_CURRENT`).
3. Query through `IndexSnapshot` / `BasicFactQueries` — never by opening raw packs from callers.

## Invalidation and reuse

| Event | Action |
|-------|--------|
| Unchanged inputs | Reopen published generation; zero analyzers |
| File edit | Recompute invalidated packs + declared post-process closure |
| Schema / plugin / analyzer bump | Invalidate affected analysis keys |
| New worktree, same machine | Share chunks; new workspace id + current pointer |
| Confirmed workspace loss | Tombstone; abandon worktree staging/refs; keep shared chunks until GC |

### Reclamation

1. **Reference-based** — never drop packs reachable from `current`, pinned snapshots, or staging.
2. **Age** — unreferenced packs / dead workspaces ~30 days.
3. **Quota** — backstop; never below one complete generation per live worktree without force.

CLI-only operators: `indexino cache status|gc|forget` and `daemon stop --purge`. Explicit
last-used in the registry (not filesystem `atime`). GC grace window + re-verify before unlink.

## S2 implementation layout

Refresh writes mutable incremental output only beneath
`workspaces/<workspace-id>/staging/in-process-writer/`. On a completed refresh, indexino installs an
immutable content-addressed pack in `chunks/<ab>/<cd>/<content-key>`, writes a generation manifest
under `workspaces/<workspace-id>/generations/<generation-id>/manifest.json`, then atomically updates
that workspace's `current` pointer. The manifest records the basic-fact schema coordinate, ordered
origin graph (origin ID, actual revision, expected revision when applicable, and origin-local state
fingerprint), workspace revision fingerprint, and pack keys. Older single-origin entries remain
readable through their legacy workspace-origin fields.

Each client materializes a referenced immutable pack atomically into its own
`workspaces/<workspace-id>/refs/<client-id>/<generation-id>/store/` directory before opening a
snapshot. Snapshot pins retain those caller-owned refs until close; shared packs remain immutable and
are reclaimed only by reachability/age/quota GC. There is no runtime `legacy-store` layout. Do **not**
extend `<project>/.indexino/index/<commit>/`; new features must assume user-local composite storage.

## Deprecated / rejected paths

- `<project>/.indexino/` as product cache or runtime root
- `.compose-selection-index/` — early sketch
- `.agent/` — upstream #814 name only; not used by this CLI
