# User guide

Indexino indexes a local repository once, then answers symbol, reference, resource, and bundled
selection-context queries from a user-local cache. It does not modify the repository's sources.
For all flags and result schemas, see [CLI.md](CLI.md).

## Install and authenticate a native release

Choose the ZIP matching your host in [DISTRIBUTIONS.md](DISTRIBUTIONS.md). Install Git and the
target repository's Bazel or Gradle tooling separately. Keep the entire extracted `indexino/`
directory together; its launcher needs the adjacent runtime, application JAR, and AOT cache.

For a **tagged release built with the new signing workflow**, download its ZIP, `.sha256`, and
`.zip.sigstore.json` from the same GitHub release. On Linux, verify the exact archive and signer:

```bash
tag=v0.3.0 # replace with an actually published tag
archive="indexino-${tag#v}-linux-x64.zip"
sha256sum -c "$archive.sha256"
cosign verify-blob "$archive" \
  --bundle "$archive.sigstore.json" \
  --certificate-identity "https://github.com/rock3r/indexino/.github/workflows/release.yml@refs/tags/$tag" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
unzip "$archive"
./indexino/indexino --help
```

Use the corresponding macOS/Windows archive filename for those hosts and verify its Sigstore
bundle against the same tag and workflow identity. On macOS, `shasum -a 256 <archive>` provides
the checksum; check the Developer ID launcher signature with
`codesign --verify --strict --verbose=2 indexino/indexino` after extraction. On Windows PowerShell,
compare `(Get-FileHash <archive> -Algorithm SHA256).Hash` with the adjacent `.sha256` digest.
**The 0.3.0 Windows launcher is not Authenticode-signed.** The verified tag-bound Sigstore bundle
authenticates the ZIP's release-workflow origin; the checksum alone does not authenticate its
publisher, and neither is an Authenticode signature on `indexino.exe`. Windows may warn about an
unknown publisher. Native artifacts from ordinary CI are unsigned test outputs, not releases.

## Index a project

Use `/path/to/indexino/indexino` below (or `indexino.exe` on Windows). A Bazel target and its
dependency sources are indexed by the CLI; for Gradle choose a module explicitly:

```bash
indexino index --project /path/to/repo --bazel-target //plugins/example:ui \
  --applications dev.sebastiano.selection-context

indexino index --project /path/to/gradle-repo --build-system gradle \
  --gradle-module :ui --include-deps \
  --applications dev.sebastiano.selection-context
```

`index` publishes a completed generation before queries can read it. The cache is under
`$INDEXINO_CACHE_DIR`, `$XDG_CACHE_HOME/indexino`, `~/Library/Caches/indexino` on macOS, or
`~/.cache/indexino` elsewhere (in that order). It is not in the worktree; `indexino cache status`
reports its state. To rebuild after a corrupt installation, reinstall the **complete** ZIP rather
than replacing the JAR or runtime in place; AOT training binds them together.

## Query the index

```bash
indexino query --project /path/to/repo \
  --application dev.sebastiano.selection-context \
  --preset interactive-in-selection --format jsonl
indexino find-symbol --project /path/to/repo --name Panel
indexino find-references --project /path/to/repo --symbol 'sample.Panel#render'
indexino resolve-resource --project /path/to/repo --type string --name title
```

JSONL output is suitable for scripts; errors and progress are separate from result records. If
the selected scope changed, rerun `index` or use the daemon's auto-refresh, and only treat a
published fresh generation as current. Run `indexino daemon status --project /path/to/repo` to
inspect a running owner, and stop it with `indexino daemon stop --project /path/to/repo` before
moving or deleting the repository.

Native distributions do not host `.indexino.kts` scripts or load arbitrary plugin JARs into the
launcher. Use the full JVM distribution for scripting or the native extension worker protocol for
dynamic compiled checks. See [DISTRIBUTIONS.md](DISTRIBUTIONS.md) for limits and troubleshooting.
