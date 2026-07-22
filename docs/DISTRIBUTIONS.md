# Native distributions

Indexino native distributions are self-contained command-line installations built around the same
JVM 21 bytecode as the thin Maven and fat-JAR variants. Each target ZIP contains a Roast launcher,
a stripped JBR 25 runtime, the R8 application JAR, a target-trained AOT cache, and the applicable
license files. Native ZIPs are CI artifacts while Indexino remains pre-release; no native release
has been published.

## Targets and tested baselines

| Target | Archive | Tested minimum |
|---|---|---|
| Linux x64 | `indexino-<version>-linux-x64.zip` | Ubuntu 22.04 x64 / glibc 2.35 |
| macOS arm64 | `indexino-<version>-macos-arm64.zip` | macOS 15 on Apple silicon |
| Windows x64 | `indexino-<version>-windows-x64.zip` | Windows Server 2022 x64 |

The Linux package is built and fully verified on Ubuntu 24.04, then its actual Roast executable runs
an index/query workload inside the declared Ubuntu 22.04 baseline container. The manually
dispatched native matrix performs the complete matching-host verifier on macOS 15 arm64 and Windows
Server 2022 as well. Environments older than these baselines are unsupported until they are added to
the permanent smoke matrix.

## Installation

Download the ZIP and adjacent `.sha256` file from the same trusted CI run. Verify the checksum before
extracting it. The checksum format is compatible with `sha256sum -c` on Linux and with equivalent
SHA-256 tools on macOS and Windows.

Extract the complete `indexino/` directory without rearranging its contents. The executable is:

- `indexino/indexino` on Linux and macOS;
- `indexino/indexino.exe` on Windows.

The installation is relocatable as a unit. It can be invoked from any caller directory; it does not
need to be the current working directory.

## Using the installed executable

    /path/to/indexino/indexino index \
      --project /path/to/repository \
      --bazel-target //plugins/example:ui \
      --applications selection-context

    /path/to/indexino/indexino query \
      --project /path/to/repository \
      --application selection-context \
      --preset interactive-in-sc \
      --format jsonl

Use `indexino.exe` in PowerShell or Command Prompt. All paths accepted by the JAR CLI are accepted by
the installed executable.

## External tools

The runtime and Java dependencies are bundled. Tools used to discover the target repository are not:

- Git must be available on `PATH`; Indexino uses it to identify commits and inspect repositories.
- Bazel must be available when indexing through Bazel topology.
- Gradle or a project Gradle wrapper must be available when a requested Gradle topology operation
  requires it.
- Any project-specific topology tool invoked by the selected build path must also be available.

## AOT behavior

The cache at `runtime/lib/server/classes.jsa` on Linux/macOS or
`runtime/bin/server/classes.jsa` on Windows is trained with the exact normalized application JAR and
JBR image in the ZIP. Automatic mode uses the cache when it is compatible and falls back to normal
startup when it is missing or corrupt. Strict verification rejects a missing, corrupt, or
JAR-mismatched cache. Do not replace the application JAR, runtime, or cache independently and expect
AOT loading to remain valid.

Removing the AOT cache is a supported diagnostic step; the CLI remains functional with slower
startup. Reinstall the complete ZIP to restore the matching cache.

## Troubleshooting

- `Permission denied` on Unix: re-extract with a tool that preserves ZIP Unix modes. The launcher,
  directories, `jexec`, and `jspawnhelper` must remain executable.
- Git or topology command failures: confirm the required external tool is installed and visible on
  `PATH` from the same shell that launches Indexino.
- AOT rejection: reinstall the whole archive. Automatic mode should still run; strict diagnostic
  runs intentionally fail when the cache does not match.
- macOS quarantine or Gatekeeper failures: ordinary CI artifacts are unsigned and unnotarized test
  outputs. A future approved release ZIP is signed and notarized as immutable bytes. ZIPs cannot
  carry a stapled notarization ticket, so a first launch on a quarantined machine needs network
  access for Gatekeeper to retrieve the ticket. After successful online assessment macOS may cache
  the ticket; an offline first launch can be rejected and should be retried online.
- Windows security warnings: the decided first-release Authenticode policy is `UNSIGNED`. Verify the
  published SHA-256 file and signed aggregate provenance; SmartScreen reputation warnings are
  expected. Enabling Authenticode later requires signing the final executable before archiving and
  rerunning the complete verifier on those final bytes.

For an approved release, first fetch `release/native-redistribution-manifest.json` and
`release/indexino-release-signing-key.asc` independently from the immutable source tag in the
official `rock3r/indexino` repository; do not use only the copies co-distributed with a release or
mirror. Compare the full fingerprint printed by `gpg --show-keys --fingerprint` with
`releaseSigningKeyFingerprint` in that independently fetched manifest. Only after that identity
check, verify the aggregate signature and per-archive checksum from the directory containing the
downloaded release assets:

```bash
gpg --show-keys --fingerprint release/indexino-release-signing-key.asc
gpg --import release/indexino-release-signing-key.asc
gpg --verify indexino-release-provenance.txt.asc indexino-release-provenance.txt
sha256sum -c indexino-<version>-windows-x64.zip.sha256
```

## Release blockers

No native release may be created until the manual Tier 1 matrix is green for the intended source
commit and all release-safety work is complete. The tag workflow enforces a checked-in
`release/native-redistribution-manifest.json` approval plus the repository
`NATIVE_RELEASE_APPROVED=true` gate. The checked-in manifest intentionally remains
`PENDING_COUNSEL_APPROVAL`, so native drafting is disabled. Approval must cover the exact JBR and
Roast inputs, retained JBR GPLv2 with Classpath Exception and module notices, a counsel-approved
corresponding-source mechanism, a bundled dependency inventory, immutable-payload macOS codesigning
and notarization, and the documented Gatekeeper and Windows Authenticode policies. Final
signed/notarized bytes must repeat the full Roast, AOT, relocation, mode, and differential verifier
before release checksums are generated.
