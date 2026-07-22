# AGENTS.md

## Project Overview

**indexino** is a standalone Kotlin CLI that builds a **persistent** local code index
(Xodus under `<workspace>/.indexino/index/<commit>/`) for agent audit tools. It is
Detekt-independent, Bazel-first (Gradle secondary), and ships as a fat compatibility JAR with no
target-repo build coupling. A separate R8 JAR is the internal native-distribution input.

**selection-context** is the **first application plugin**: precomputed SelectionContainer /
DisableSelection facts at composable call sites for Compose/Jewel audits — replacing token-heavy
manual file reading.

## Source of Truth Docs

| Document | Use it for |
|----------|------------|
| [.plans/HANDOFF.md](.plans/HANDOFF.md) | **New session start** — read first |
| [.plans/kotlin-code-index-core.md](.plans/kotlin-code-index-core.md) | Persistent index platform, Xodus, producers |
| [.plans/application-selection-context.md](.plans/application-selection-context.md) | First application (SelectionContainer) |
| [.plans/master-plan.md](.plans/master-plan.md) | Phased delivery (Core C* / App A*) |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layer map, dependency direction |
| [docs/INDEX-STORAGE.md](docs/INDEX-STORAGE.md) | `.indexino/` layout, key namespaces |
| [docs/BAZEL-TOPOLOGY.md](docs/BAZEL-TOPOLOGY.md) | Bazel target closure, `.bazelproject` |
| [docs/GRADLE-TOPOLOGY.md](docs/GRADLE-TOPOLOGY.md) | Gradle backend (phase G) |
| [docs/CLI.md](docs/CLI.md) | Commands, flags, JSONL output schema |
| [docs/PUBLISHING.md](docs/PUBLISHING.md) | Maven Central coordinates and release flow |
| [docs/API-STABILITY.md](docs/API-STABILITY.md) | Public API boundary, ABI baseline, SemVer policy |
| [docs/TESTING.md](docs/TESTING.md) | TDD flow, fixture layout |
| [docs/CONVENTIONS.md](docs/CONVENTIONS.md) | File placement, style, git workflow |

## Non-Negotiables

### Mandatory Pre-Implementation Doc Read

Before writing or modifying production code, read these docs in full for every session:

1. [.plans/HANDOFF.md](.plans/HANDOFF.md) (if new to the repo)
2. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
3. [docs/INDEX-STORAGE.md](docs/INDEX-STORAGE.md)
4. [docs/CONVENTIONS.md](docs/CONVENTIONS.md)
5. [docs/TESTING.md](docs/TESTING.md)

If the task touches Bazel topology or CLI contracts, also read
[docs/BAZEL-TOPOLOGY.md](docs/BAZEL-TOPOLOGY.md) and/or [docs/CLI.md](docs/CLI.md).

### TDD First

Write the failing test before implementation. Then make it pass with the minimum production change.

1. Write the test.
2. Run the targeted test.
3. Prove it fails (red) before editing production code.
4. Implement the minimum fix.
5. Re-run tests to green.

Full policy: [docs/TESTING.md](docs/TESTING.md).

### Worktree Policy

At the start of every new session, if the user hasn't said "use a worktree" explicitly, before
writing any file that is not gitignored, check whether you are already in a worktree or on a
non-main feature branch. If either is true, skip the worktree prompt — you already have
isolation. Otherwise, ask the user:

> "Would you like me to work in a git worktree so the main checkout stays clean? If yes, I'll
> create one now before making any changes."

If yes, use the `using-git-worktree` skill (`.agents/skills/using-git-worktree/SKILL.md`).

Plans always go in the root checkout, in `.plans` (gitignored).

### Regressions and broken features

- **Scoping** — Changes must be related to the session's topic(s). Unrelated changes are
  forbidden unless the user explicitly consents in a follow-up.
- **Regressions** — Any behavioral change not requested by the user must be flagged.
- **Broken features** — Changes must never leave the CLI in a broken state. Phased work must be
  agreed explicitly with a documented completion plan.
- **Refactors can't change behavior** — Refactor-only PRs must not change analysis results.

### Documentation

When a change touches a surface a doc describes, update the doc in the same session.

### Public API

The committed ABI baseline is intentionally empty until the first embedded API is designed. Keep
implementation declarations `internal` and strict explicit API mode enabled. Any public declaration
requires an intentional `api/indexino.api` review and the compatibility analysis described in
[docs/API-STABILITY.md](docs/API-STABILITY.md).

## Actions Requiring Explicit User Approval

Never perform these without explicit user instruction:

- **Opening a PR** (`gh pr create`)
- **Closing or deleting a PR** (`gh pr close`, `gh pr merge`)
- **Merging a PR** (`gh pr merge`)
- **Committing to any branch other than the one the current task is working on**

## Working Style

**Minimize interruptions.** Clarify upfront or not at all. When stuck in a retry loop (three or
more failed attempts at the same approach), pivot structurally and revert failed experiments.

**Be explicit about working directory changes.** Use absolute paths when working outside the
default workspace root.

**Always prefer dedicated tools over shell** for file read/write/search within the project root.

**When you must use bash**, keep commands simple, atomic, and read-only where possible. Never use
bash to write files — use the write/edit tools.

**Copiable blocks use 4-space-indented code blocks**, never top-level fences, when producing
artefacts the user should copy verbatim.

## Build & Run (Quick Reference)

```bash
./gradlew test          # unit tests (fixture-driven)
./gradlew run --args="…" # CLI during development
./gradlew shadowJar     # fat JAR at build/libs/*-all.jar
./gradlew shrunkCliJar  # R8 native-packaging input at build/libs/*-shrunk.jar
./gradlew verifyShrunkCli # full shrunk-JAR acceptance workload
./gradlew verifyConstruoContract # released native-packaging contract and immutable pins
./gradlew verifyAotTrainingContract # AOT task lifecycle and isolation contract
./gradlew trainAotMacArm64 # real matching-host JBR 25 AOT assembly
./gradlew verifyNativeDistributionMacArm64 # package/smoke macOS arm64 on a native host
# LinuxX64 and WindowsX64 variants follow the same verifyNativeDistribution<Target> naming
./gradlew check         # tests (extend with lint/format when added)
```

## Local Skills

Check `.agents/skills/` before improvising workflows. Inventory:
[`.agents/skills/README.md`](.agents/skills/README.md).

**Before any git or GitHub operation**, read
[`.agents/skills/git-github-ops/SKILL.md`](.agents/skills/git-github-ops/SKILL.md).

## PR Rules

**Don't open or push to a PR until TDD is complete and `./gradlew check` passes.**

Before merge:

1. `./gradlew check` passes locally.
2. CI is green on the PR branch.
3. TDD red-green verified for every new test.

Use `gh pr merge <number> --squash --delete-branch` (no `--auto`) when merging.
