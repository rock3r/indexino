# Indexino 0.3.0

Approved next version after the published 0.2.6 series. Release only after the matching-host
Tier 1 matrix, publication checks, macOS signing/notarization, and separate release approval.
The Windows launcher is **unsigned Authenticode** by decision; Windows may show an unknown-publisher
warning. A verified tag-bound Sigstore bundle authenticates the ZIP's release-workflow origin, not
the executable's Windows publisher identity. A `.sha256` sidecar alone does not provide trust.

## Since 0.2.6

- Published workspace snapshots now support guarded provisional refresh and shared immutable
  bases, with source-origin provenance retained across nested repositories and watcher updates.
- Indexing no longer creates the project-local CLI compatibility projection; the product cache
  remains user-local. The CLI resolves resource references from published snapshots and supports
  `repo` manifest scopes through the runtime.
- Cache collection now retires unused expanded stores, abandoned client references, and eligible
  aged packs while protecting active generations and dependent workspaces.
- Native packages use a pinned Roast 1.8.0 launcher, JBR 25, and a matching AOT cache. The release
  workflow signs and notarizes macOS payloads, verifies all final archives, and attaches tag-bound
  Sigstore bundles. The Windows executable remains unsigned Authenticode. Availability depends on
  the release workflow actually completing; ordinary CI artifacts are not signed release binaries.

For installation and signature verification, see [the user guide](../docs/USER-GUIDE.md). For
supported platforms and native limitations, see [native distributions](../docs/DISTRIBUTIONS.md).
