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
    generations/<generation>/manifest   # storage format 2: the manifest is all a generation owns
    staging/in-process-writer/              # mutable incremental writer
    staging/<refresh-id>/manifest
    current                            # atomic pointer
    source-links/registry.json           # dependency-to-source link registry snapshot
    source-links/generations/<link-generation>/snapshot.json
    change-journal
    refresh-cache/                     # rebuildable refresh accelerators
      topology/<request-key>.json
      source-hashes
      origin-states/<origin-key>.json
      source-spill/                    # build-scoped captured-source spill; deleted per build
    legacy-store/                      # historical bridge; absent from the current layout
      index/<commit>/                  # mutable incremental writer
      clients/<client-id>/generations/<generation-id>/store/ # per-client immutable snapshot copy
  registry/workspaces.json             # path → fs identity, generations, last-used
  registry/tombstones/
  runtime/<workspace-id>.sock          # AF_UNIX (all platforms)
  runtime/<workspace-id>.lease.json
```

Two pack storage formats share the `chunks/` fanout:

- **Storage format 1 (legacy):** ZIP archives of expanded Xodus environments. Readers unpacked
  them into `generations/<id>/materialized/` and `overlay-delta/` trees plus per-client
  `refs/<client-id>/…` copies. **Old caches are discarded on upgrade**: there is no migration
  and no converter. The current reader rejects format-1 generation manifests, never restores,
  repoints at or forks from them, and forces a full cold rebuild so the next refresh publishes
  the workspace in format 2.
- **Storage format 2 (current):** one raw immutable segment file per pack, mapped directly.
  `generations/<id>/` holds only the manifest; no `materialized/`, `overlay-delta/` or `refs/`
  directory is ever written for a format-2 generation.

### What a chunk is

A **chunk** is an immutable content-addressed **pack** of related analysis facts for one
analysis-key + content-key, stored as a file under the fanout directory—not one Xodus environment
per source file. Format-2 packs are single immutable **segment files**; Xodus remains the mutable
incremental writer that may hold generation-local staging state referencing chunk IDs.
Put-if-absent installs packs; readers open by content key.

Plugin facts are the same mechanism under plugin analysis identity (see plugin SPI in the design
doc). Payload values use the durable `PluginFactValue` model in `indexino-model`.

### Segment files (storage format 2)

A segment file is a self-contained immutable sorted table set:

```
[header 32 B][primary table: data blocks | block index | bloom][derived tables …][directory][trailer 32 B]
```

- Keys are UTF-8 `CodeIndexKey` strings in **unsigned lexicographic byte order** (the same order
  Xodus `StringBinding` persists); values are the unchanged JSON-encoded `CodeIndexRecord` bytes.
- Data entries are prefix-compressed against the previous key of their block; each block index
  entry stores the full first key, so any block decodes without prior context. The primary table
  carries a bloom filter (8 bits per entry, 4 probes) for point lookups.
- Derived tables `symnames`, `symfiles` and `callfiles` mirror the Xodus `SymbolNames`,
  `SymbolFiles` and `CallFiles` indexes as composite keys (`term + 0x00 + primary key`).
- **Tombstones** are entries with a zero-length value and a flag bit; a tombstone in a newer
  segment shadows the same key in older segments. Overlay publication currently emits none
  (deletions are physical in the staging delta), but the format and merged readers honor them.
- The trailer stores a CRC-32 over the file prefix; mapped opens verify it eagerly up to 64 MiB —
  larger packs rely on their content-addressed names, which hash a segment-format marker plus the
  file bytes so ZIP and segment packs can never collide on a key.
- Writers stream one sequential pass over **strictly increasing** keys — the natural fast path for
  pre-sorted bulk input — and finish with one `force`. Publication converts a completed staging
  environment by reopening it read-only for exactly that sorted pass.

Readers map packs with `FileChannel.map` into one shared `Arena` per canonical pack path,
reference-counted by live snapshot pins (`SharedSegmentStore`), mirroring the Xodus
`SharedReadOnlyStore` discipline. Query-time store composition is unchanged from the outside:
base packs plus overlay delta packs merge under `WorktreeOverlayIndexStore` with manifest
tombstone prefixes, preserving `BasicFactQueries`, `PluginFactViewV1` and pagination semantics.

**Windows specifics:** the JDK opens mapped files with `FILE_SHARE_DELETE`, so deleting or
renaming a pack through the cache namespace can succeed while mappings remain live; pinned
readers keep serving until the last release (regression-tested on Windows). Cross-process GC can
therefore unlink packs without waiting for other processes' pins, exactly as it already treats
open Xodus log handles in legacy generations.

### Workspace identity

- `<workspace-id>` is the first 16 hexadecimal characters of SHA-256 over the canonical workspace
  path. The fixed 64-bit identifier keeps the runtime socket below macOS's 102-character budget.
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
| resources | Core | Android/Compose Multiplatform resource identity |
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

Version 6 includes the column in persisted resource-definition keys. Older packs overwrite
same-line declarations, so they cannot be reused as current: refresh rebuilds their facts even
when source bytes are unchanged. The logical resource ID remains package/type/name; distinct
definitions retain their own source locations. The embedded resource API signatures are unchanged.

## Query path (product)

1. Connect to the workspace runtime (or in-process engine in early slices).
2. Pin a published generation (`snapshot(PUBLISHED)` or after refresh / `AWAIT_CURRENT`).
3. Query through `IndexSnapshot` / `BasicFactQueries` — never by opening raw packs from callers.

Named symbol queries do not scan every symbol in newly written Xodus stores. Exact FQN queries
seek the location-qualified primary-key prefix (and the legacy exact key). Short-name, alias and
prefix queries use a derived `SymbolNames` duplicate store mapping each term to its primary key.
Owner resolution uses the same index while retaining origin/file precedence and primary-key tie
ordering. Public result sorting and pagination are unchanged; broad unnamed queries still scan.
Unnamed file-scoped symbol queries use a derived `SymbolFiles` duplicate store keyed by the
length-prefixed origin ID and relative file path. Writes, replacements and deletions update it in
the primary transaction; writable older stores backfill on open. Read-only older packs without
this index stream all symbol records in their base/delta instead of materializing the full overlay,
so their file queries remain slower until rebuilt. Neither path changes the fact schema or public
sorting/pagination. Tombstones and moved replacements stay hidden in both paths.

File-scoped call queries use a derived `CallFiles` duplicate store mapping a length-prefixed
origin ID plus origin-relative file path to primary call keys. Call candidate resolution uses
`SymbolNames` while retaining FQN/alias filtering; a matching short name alone is not a candidate.
Call-file entries participate in replacement, deletion and rollback transactions. Writable legacy
stores backfill once, and read-only legacy stores retain the scanning fallback. Overlay lookups
mask tombstoned and replaced base keys even when a replacement moves to another file or origin.
An origin-qualified file tombstone skips the base call-file lookup entirely; the delta still supplies
replacement calls, while untombstoned files continue to read the base.
For repeated `findCalls(inFile)` pages, a pinned snapshot retains the two most recently requested
files' ordered call records, capped at the host's 10,000-result window plus one sentinel per file.
The first page still scans its file; later pages reuse those records without scanning Xodus again.
The cache is snapshot-local and discarded on close. Other call predicates retain their usual scan.
Unrestricted call queries and opaque enclosing-symbol ID resolution still scan.

The name index is updated in the same transaction as primary records, including replacement,
deletion and rollback. Xodus contextual transactions let nested store operations participate in
the enclosing batch. Java analysis partitions sources into bounded multi-file `JavacTask` batches
parsed on worker threads, with one explicitly closed standard file manager and empty user classpath
per worker. Each compilation unit is walked once into in-memory declarations and unresolved
candidates; cross-file symbols resolve against a run-local symbol table, falling back to stored
symbols for files outside the run. Fact writes leave each producer as bounded key-sorted chunks;
each chunk is one coarse staging transaction that appends through the store's sorted fast path, so
a cold build does not copy B-tree pages per file. Syntax-only parsing
does not need host dependencies or auto-starting javac plugins; `-proc:none` alone does not exclude
them.
Kotlin parses every source once on a bounded worker pool. Each worker gets its own PSI factory from
one shared `KotlinCoreEnvironment`; the walk returns detached declaration and unresolved
call/reference/resource records (~1–2 KB per file), so no project-wide syntax trees stay in heap.
Cross-file candidates resolve against a run-local symbol table with memoized stored-symbol
fallbacks, and fact writes use the same bounded key-sorted chunks.
Build contexts lazily index source identities for metadata lookup, avoiding repeated inventory
scans while keeping origin-specific captured-source reads and unindexed-file fallback unchanged.

XML origin cleanup skips scans when no resource sources are affected. Otherwise it streams each
record family, retaining only matching deletion keys rather than decoded whole-index records, and
deletes those keys in bounded transactions. Origin and relative path both participate in matching.
Java/Kotlin origin cleanup streams and batches deletions the same way; it still scans each relevant
record family, so this reduces materialization and transaction overhead, not scan complexity.

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

A full refresh captures each discovered source once before analysis. A watcher-hinted refresh may
inherit unchanged source hashes and facts from its current published generation when native watches
were armed before that generation's capture and remained covered. It reads and hashes each hinted
source; if an analyzer needs inherited source text, that read must match the inherited hash or the
refresh fails for full reconciliation. Change detection, analyzers, the aggregate source hash, and
origin source fingerprints use this logical immutable snapshot. A read or refresh may fail and
retry, but no generation may publish facts beside a hash from a different read of the file.
Full capture of at least 64 sources without inherited hashes uses up to four readers, assembling
their results in source order; each hash comes from the captured bytes. On failure or stop, it
interrupts sibling readers and waits for them to exit before the refresh retires. A filesystem read
that does not respond to interruption can therefore delay error reporting, as it can in a serial
capture. Watcher-hinted captures retain their sequential inheritance path.
Decoded source text stays in a bounded access-ordered window (64 MiB by default) instead of being
pinned for the whole build. Text that leaves the window is appended to one build-scoped spill file
under `refresh-cache/source-spill/` and served back exactly as captured, so analysis never re-reads
the user's source for bytes it already hashed. The snapshot deletes its spill when the build
releases it; a failed build's file is scrubbed by the next capture. Only entries that were never
captured (inherited hashes and hash-cache hits) are re-read, and a change since capture fails that
refresh for full reconciliation, so an analysis pass can never publish facts beside a hash from
different bytes.

### Refresh accelerators

`workspaces/<workspace-id>/refresh-cache/` holds three rebuildable caches used by manual and CLI
refreshes. They are accelerators, never fact sources: every entry is validated against current
filesystem and Git state before use, a missing or corrupt entry falls back to the full path, and
removing the workspace directory (or `cache forget` / `daemon stop --purge`) discards them.

- **`topology/<request-key>.json`** — the complete `TopologyResult` for one build system, scope and
  `includeDeps` value, including `codeSourceFiles` roles and external included-build mount
  metadata. Validation recomputes the content digest of build-definition files and the digest of
  every file name below the workspace (and included-build roots). Bazel inputs are
  `BUILD`/`BUILD.bazel`, `*.bzl`, `MODULE.bazel` and its lock, every `*.MODULE.bazel` include
  segment, `WORKSPACE`/`WORKSPACE.bazel` and locks, `WORKSPACE.bzlmod`, `REPO.bazel`,
  `.bazelignore`, `.bazelproject` and `.bazelrc*`. Gradle inputs are every `*.gradle`/`*.gradle.kts`
  script, every `*.versions.toml` catalog, `gradle.properties`, wrapper properties, and the
  included-build roots. The key also contains the Bazel client version (`.bazelversion`, the
  launcher identity and `bazel --version`), the JVM version and the Kotlin version. The file-name
  inventory is required because BUILD globs and Gradle source roots change when files are added or
  removed without a build-file edit. Gradle output directories are skipped only outside `src`, so a
  package directory named `build` (or `out`, `target`, `node_modules`) remains covered. A fresh
  result is stored only when both digests are identical before and after discovery, so an edit
  racing a query cannot be recorded as the state that produced the older answer. Included-build
  roots are fingerprinted before and after discovery as well, so a source added to a mount during
  discovery is not paired with an older source list. Injected query executors, `repo` topology, a
  Bazel `build-parse` fallback while the Bazel client is healthy, an unknown `codeSourceFiles`
  classification, and a dependency-inclusive request served by a target-only fallback bypass the
  cache. A workspace containing a symlinked directory also bypasses the cache, because Bazel globs
  follow directory links the inventory cannot certify; root-level `bazel-*` convenience links are
  recorded by target and remain cacheable because they point at build outputs.
- **`source-hashes`** — append-safe text index from `(originId, origin-path)` to
  `(size, mtimeNs, ctimeNs, fileKey) -> sha256`. A hit skips the file read; any metadata change
  re-reads and re-hashes. Entries observed within a two-second timestamp-granularity window are
  not trusted, so a same-tick edit cannot reuse a stale hash. Writes are atomic and best-effort.
- **`origin-states/<origin-key>.json`** — one origin's `OriginGitState` (revision, diff sections,
  tracked status and untracked hashes), reused while `.git/HEAD`, the ref it names,
  `packed-refs`, the index, effective Git configuration/info files and every working-tree file stat
  are unchanged. That proof covers tracked and untracked changes outside the indexed closure
  without spawning `git`. Linked worktrees are supported through their `.git` file and
  `commondir`; a parent with submodules also fingerprints each submodule's HEAD, refs, packed-refs
  and index under `.git/modules`, so a gitlink advance invalidates the parent state. Submodules
  used as their own origin require a caller-provided expected revision. The stamp includes the
  effective global ignore and attributes files: `core.excludesFile`/`core.attributesFile` when
  configured, otherwise `$XDG_CONFIG_HOME/git/ignore` and `git/attributes` (falling back to
  `~/.config/git/...`). Configurations whose output depends on files the stamp cannot observe
  (dotted or section-form `include`/`includeIf`, `core.bare`/`core.worktree`, textconv, ambiguous
  quoted values) and Git environment overrides disable reuse instead of guessing.

Git inputs that can change effective output without touching HEAD, the index or worktree files:

| Input | Handling |
|-------|----------|
| HEAD, resolved ref, packed-refs, index | content/stat in the stamp |
| repo/global/system config, `config.worktree` | content/stat in the stamp; `~/.gitconfig` overrides XDG |
| effective global ignore/attributes | content in the stamp, resolved with Git precedence |
| `info/exclude`, `info/attributes`, `info/grafts`, `info/sparse-checkout`, `shallow` | content in the stamp |
| loose `refs/replace/*` | content in the stamp; packed replacement refs are covered by `packed-refs` |
| `objects/info/alternates` | reuse declined |
| `GIT_DIR`, `GIT_WORK_TREE`, `GIT_COMMON_DIR`, `GIT_OBJECT_DIRECTORY`, `GIT_ALTERNATE_OBJECT_DIRECTORIES`, `GIT_NAMESPACE`, `GIT_SUPER_PREFIX` | reuse declined |
| `GIT_REPLACE_REF_BASE`, `GIT_NO_REPLACE_OBJECTS`, `GIT_SHALLOW_FILE`, `GIT_GRAFT_FILE`, `GIT_ATTR_NOSYSTEM`, `GIT_ATTR_SOURCE`, pathspec env | reuse declined |
| `GIT_CONFIG_GLOBAL`, `GIT_CONFIG_SYSTEM`, `GIT_CONFIG_NOSYSTEM`, `XDG_CONFIG_HOME`, `HOME` | the resolved config/ignore files are stamped |
| worktree files, symlink targets, nested submodule metadata | stamped |
| config includes, `bare`/`worktree` layout, textconv, ambiguous values | reuse declined |

A live daemon watcher still supplies changed paths for hinted refreshes and inherits unchanged
source hashes from the published generation; these caches serve cold starts, explicit refreshes,
and watcher overflow or coverage loss. Source content hashes and publication checks still decide
whether a generation can be reused or published.

Platform note: Unix uses inode change time (`unix:ctime`) to detect a metadata rewrite that
restores the modification time. Java NIO exposes no change time on Windows, so the Windows key is
size, last-write time, creation time and file identity; the timestamp-granularity window plus Git
index/source hashes are the remaining guarantees. A same-size rewrite that also restores the
modification time is not distinguishable on Windows by stat alone; such a rewrite can keep a stale
hash until another metadata field changes.

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
`activeRuntime=true` without deleting packs, expanded stores, or client refs. It removes expanded
`materialized` and `overlay-delta` directories only for generations unreachable from any current
generation, recursive fork base, or provider generation pinned by a current source-link snapshot;
their referenced packs remain available for reconstruction. If a current fork base or source-link
snapshot is unavailable, expanded-store reclamation is skipped for that pass while existing pack
collection continues. Abandoned private client refs are removed only when the exclusive lock
proves that no live client or pinned snapshot can still use them, even if the workspace's current
generation remains published. CLI lookups pin published snapshots through an in-process client;
their shared activity lease protects expanded stores, including after daemon refreshes. The
collector intentionally defers all reclamation while any client is live.
An in-process client acquires its activity lock before resolving the writer staging path: a new
cache beneath a symlinked parent must use the same canonical root before and after creation, or a
second refresh can miss its compatible overlay base.
Unreferenced packs survive until 30 days after their first GC observation. `cache gc` persists
first-observed times in `registry/unreferenced-packs.json` before deleting eligible packs; every
pass recomputes generation reachability under the exclusive lock and clears the age of any pack
that is referenced again. Missing or unreadable ledger state cannot authorize an early unlink.
If the cache's regular files exceed 20 GiB, GC may evict the oldest unreferenced packs after at
least 24 hours of persisted observation, stopping once enough bytes have been selected. This is
a best-effort backstop: live generations, fork bases, and linked provider packs remain referenced
even when they alone exceed the quota. An incomplete fork or source-link reachability scan disables
accelerated quota eviction for that pass. The size calculation counts logical cache-file bytes,
including expanded stores, not allocated disk blocks. A pass never uses filesystem `atime`.
Expanded stores and abandoned client refs retain their separate immediate reclamation rules.
GC may retire a registered workspace's `staging/in-process-writer` after 30 days without a registry
update, but only while the exclusive activity lock excludes all clients and refreshes. It leaves
published manifests and packs untouched; a later refresh rebuilds the writer from those immutable
facts and current sources. A staging tree containing an unfinished `.rollback` checkpoint remains
intact for recovery, regardless of age. Unregistered staging is not collected automatically.
For a registered workspace whose path is definitely absent, GC records its first missing
observation in the registry. After 30 days of persisted absence and at least 30 days since its
last use, it rechecks the path under the exclusive activity lock, then removes that workspace's
local cache tree and registry entry. Reappearance or a registry update resets the observation.
Current recursive fork bases and source-link providers, incomplete dependency scans, and staging
with an unfinished `.rollback` checkpoint prevent deletion. Shared packs retain their separate
age/quota grace; external workspace-loss tombstones remain available for diagnostics. A checkout
that briefly reappears between GC passes without using indexino cannot be observed as present.
Unregistered workspace trees are not collected automatically.
`cache forget` also takes the exclusive activity lock and refuses deletion while another live
workspace's current overlay recursively references the selected base; otherwise a fork could lose
its manifest and shared materialized store. Forget dependent workspaces first. `daemon stop --purge`
uses the same forget operation after stopping the runtime; it removes only this workspace's local
state and leaves shared packs for `cache gc`.
All participating processes must use the updated locking protocol; stop older in-process clients
before running this collector against their cache.

## Current implementation layout

Refresh writes mutable incremental output only beneath
`workspaces/<workspace-id>/staging/in-process-writer/`; rebuildable refresh accelerators live
separately under `refresh-cache/` and never hold published facts. On a completed refresh, indexino installs an
immutable content-addressed pack in `chunks/<ab>/<cd>/<content-key>`, writes a generation manifest
under `workspaces/<workspace-id>/generations/<generation-id>/manifest.json`, then atomically updates
that workspace's `current` pointer. The manifest records the basic-fact schema coordinate, ordered
origin graph (origin ID, actual revision, expected revision when applicable, and origin-local state
fingerprint), workspace revision fingerprint, and pack keys. Older single-origin entries remain
readable through their legacy workspace-origin fields.

The publishing client keeps serving its previous complete generation while that pointer advances
and it installs the new generation with its freshness evidence. A concurrent snapshot cannot restore
the new manifest with `UNKNOWN` evidence in this interval. A newly opened client still restores
published evidence as `UNKNOWN` until a full audit verifies it.

The staging writer is populated from a consistent immutable source snapshot, including prior
captures inherited under continuous native watcher coverage. Pack facts and file-hash records
inside that pack describe the same captured content, including when the checkout changes during
analysis.

When the current workspace generation is compatible, subsequent edits keep its immutable
materialized base and publish a cumulative overlay delta instead of repacking the base. The
published manifest names both the base generation and the delta pack; source tombstones identify
the origin and relative path so a deleted/changed file cannot expose inherited facts from a
different origin. Repeated edits reload the prior delta into the staging writer, remove obsolete
delta facts, and publish a new immutable delta. Readers pin the previous generation while the
new one is built. A byte-for-byte revert to an existing generation repoints to its original
manifest only while all referenced packs remain present, including overlay deltas and recursively
referenced bases. Historical manifests can outlive packs reclaimed by GC; in that case publication
uses the rebuilt representation instead of reviving missing content. Unchanged refreshes do not
reinstall the writer pack. File analyzers process changed
sources; metadata-dependent resource and code files are reprocessed when module metadata or
resources change. An overlay tombstones their inherited facts and rewrites their file hashes even
when their content is unchanged, so old package identities cannot survive a namespace edit. Selected
file analyzers reprocess these sources too, retaining plugin findings hidden by the tombstone.
Post-processors still recompute their declared scope. Physical cleanup scans the delta for
invalidated records rather than streaming the inherited base.

This is an incremental **fact-storage and source-capture** path, not a subsecond whole-workspace
refresh guarantee:
the daemon may reuse its last resolved topology when a fully covered watcher reports only edits to
known, still-existing source paths. Explicit refreshes, changes outside the known source set,
new source paths, deletions that leave a known source absent at launch, watcher overflow, uncovered
watches, and failed automatic refresh retries resolve topology anew. A delete/create pair that
replaces a known file can retain the hint if the path exists when the refresh is claimed. This
hint is in-memory and never persisted across daemon restarts.
The narrow exception for unknown paths is a native-fenced atomic-save cluster of at most five
same-parent paths: all unknown temporary paths must have vanished without changing the bounded
parent inventory, and a known source must have changed. Its published freshness is PROVISIONAL
until an independent full audit; any failed fence or inventory check still resolves topology.
The daemon arms its watches before the full capture; for a subsequent known-source edit it reads
only hinted sources, inherits other hashes from the matching published generation, and avoids a
disk fact scan for change detection. Analyzers verify any inherited source bytes they need against
the saved hash. A watcher overflow, invalid watch, failed auto refresh, changed generation, or
polling-only macOS external mount disables inheritance; explicit refreshes still read all sources.
If known-source edits arrive while an automatic refresh runs, their retained hints can drive a
second incremental refresh rather than discarding them and forcing a full Bazel/source/Git walk.
After a successful full capture, the watcher discards path and topology hints observed before
source capture began, even if the handle was already active. Events after that boundary retain
their latest per-path epoch and drive a successor; a failed refresh does not discard hints.
The first published generation may reflect only an early part of a long write burst; readers must
wait for the successor and verify its facts before treating the whole burst as indexed.
Even on the hinted path, source preview visits the in-memory inventory. Change detection checks
the complete source identity set (including analysis roles) against the published capture, then
compares only re-read sources when they match under continuous recursive watcher coverage. Its
per-file progress total is the re-read count in that case. An inventory mismatch falls back to
full comparison, including deletions. Post-processors still run their declared scope, and
cumulative delta materialization has a cost.
For a changed captured source with unchanged topology and the exact prior origin IDs, origin
provenance may resolve concurrently with staging-store mutation. The full origin result joins
inside the checkpoint before writing the compatibility manifest; failure restores the previous
store and manifest. This also applies to an explicit full refresh only after it captures all
discovered source bytes; it does not overlap Git-state reads with source capture. Unchanged sources,
missing or source-less origins, and incompatible generations retain sequential provenance checks
before any reuse decision.
The Kotlin producer skips PSI environment initialization when no Kotlin source needs analysis, after
performing any required cleanup for deleted Kotlin sources. Its diagnostic `kotlin-cleanup` phase
measures writable-store record removal; `kotlin-parser` encloses parser initialization, the single
parse pass, resolution, fact writes, and disposal. Within it, `kotlin-parser-setup` measures the
shared PSI environment and worker factories, `kotlin-declarations` measures the parallel parse and
single walk per file, and `kotlin-fact-write` measures in-memory candidate resolution and bounded
transactional fact writes. There is no declaration-only parse followed by a fact-write reparse.
These durations are nested, not additive; the remaining `kotlin-parser` time includes project-symbol
assembly and PSI disposal. The CLI no longer materializes a merged project-local store after
publication. Cumulative delta
materialization and the remaining whole-scope costs still need measurement and reduction before
promising subsecond updates for large Bazel workspaces.

Before mutation, the build owner streams the physical writable store into a temporary Xodus
checkpoint beside the compatibility manifest (`manifest.json.rollback/`), while retaining the
writer's existing Xodus lock. The checkpoint also preserves the prior manifest's bytes or absence.
It syncs the Xodus backup before atomically marking it ready, recording whether the destination is
the base store or the writable overlay delta. A process interrupted after that marker can restore
the records and compatibility manifest on the next build for the same commit, before reading the
compatibility manifest or replacing an overlay delta. Recovery keeps the checkpoint until both the
restored writer and manifest are complete; a failed or interrupted restore can be repeated.
An overlay checkpoints only its writable delta, never its inherited read-only base. Producer and
plugin failures restore that checkpoint. Capture and restore copy at most 16 records per transaction;
rollback deletes at most 256 keys per transaction, rather than committing each record individually. Rollback
and close failures are suppressed onto the original failure rather than replacing it. Successful
builds and successful restores make the checkpoint's regular files writable (Xodus leaves
completed logs read-only) and remove them without following symbolic links. They remove the ready
marker first, so interrupted deletion cannot leave a partial backup marked recoverable. Filesystem
deletion errors retain their affected path and cause. A failed restore retains the checkpoint,
without changing its file permissions. A checkpoint without a valid ready marker (including older
or interrupted captures) remains intact and fails closed; it is never interpreted as a complete backup.
The diagnostic `checkpoint` phase measures this record-copy capture separately from change
detection and producers. `checkpoint-release` includes `checkpoint-backup-close` and
`checkpoint-delete`.
`store-open` measures opening the writable store (and the immutable base for overlays) before the
checkpoint; `store-close` measures closing the store after the build. When a prior overlay delta
exists, `overlay-restore` measures unpacking it before `store-open`. These nested phases separate
otherwise hidden costs within `store-build`; they do not change rollback semantics.
This is recovery for mutable staging after a process interruption, not a crash-atomic transaction
or a power-loss guarantee. Preserve incomplete or failed checkpoints for investigation; removing
only the checkpoint can expose partially restored staging as valid. Published generation manifests
and immutable packs are not rewritten by this rollback.

All clients and compatible forks read the same immutable **segment packs** published under the
content-addressed `chunks/` fanout — mapped directly via `FileChannel.map` with no expanded copy.
A damaged or truncated pack fails closed at open (magic, version, bounds and — up to 64 MiB —
CRC checks); the content-addressed name plus the retained staging environment allow
reconstruction. Simultaneous snapshots in one JVM share a reference-counted mapping per canonical
pack path (`SharedSegmentStore`); separate processes map the same immutable file independently.
Closing one snapshot does not unmap another snapshot's view; client close never deletes a shared
pack that another process or fork may read. Current `cache gc` reclaims obsolete packs by
manifest reachability and age exactly as before; its expanded-store reclamation now only ever
applies to legacy format-1 generations, because format-2 generations write no `materialized/`,
`overlay-delta/` or client `refs/` directories. Old reconstructible writer staging is still
retired, but a retained rollback checkpoint is never removed. On Windows the JDK opens mapped
files with `FILE_SHARE_DELETE`, so pack unlinks are not blocked by other processes' live
mappings; pinned readers keep serving a deleted pack until their last release. There is no
runtime `legacy-store` layout. Do **not**
extend `<project>/.indexino/index/<commit>/`; new features must assume user-local composite storage.

## Deprecated / rejected paths

- `<project>/.indexino/` as product cache or runtime root
- `.compose-selection-index/` — early sketch
- `.agent/` — upstream #814 name only; not used by this CLI
