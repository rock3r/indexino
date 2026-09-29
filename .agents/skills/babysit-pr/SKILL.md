---
name: babysit-pr
description: >
  Babysits a GitHub pull request by polling CI, Codex and conditional CodeRabbit review activity,
  trusted-human feedback, and mergeability until action or merge readiness. Use when asked to
  monitor a PR, watch CI, handle review comments, or respond to failures.
allowed-tools: Bash(python3 */skills/babysit-pr/scripts/*), Bash(gh pr *), Bash(gh run *), Bash(gh api *), Bash(git fetch *), Bash(git rebase *), Bash(git merge *), Bash(git checkout *), Bash(git switch *), Bash(git push *), Bash(git commit *), Bash(git diff *), Bash(git log *), Bash(git status), Bash(git branch *), Bash(git worktree *), Bash(./gradlew check *), Read, Edit
---

# PR Babysitter

Monitor a PR until it is merged/closed, merge-ready, or needs user intervention.

## Workflow

1. Resolve the PR and tell the user which PR is being tracked.
2. Run `python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr auto --once`.
3. Act on the returned `actions`; use `--snapshot` for immediate inspection and `--watch` only
   when the harness streams output.
4. Diagnose deterministic failures and batch fixes. Use `--retry-failed-now` only for eligible
   flaky workflows (currently E2E), up to the per-SHA retry budget.
5. Process actionable comments from Codex, active CodeRabbit, and trusted humans (`OWNER`,
   `MEMBER`, or `COLLABORATOR`). Resolve addressed review threads.
6. Run `./gradlew check` before one batched push, then resume watching.

## Gates and actions

Codex review is mandatory. A 👀 reaction from `chatgpt-codex-connector[bot]` produces
`wait_codex`; do not push or merge until it disappears and all Codex comments are addressed.

CodeRabbit is presence-conditional. It gates only when a CodeRabbit check, reaction, or authored
comment establishes activity. While its check is pending or its 👀 reaction is present, the
watcher emits `wait_coderabbit`. Dormant CodeRabbit does not block readiness.

All CI checks must be terminal and successful. Skipped/neutral checks require diagnosis. The
watcher preserves a short terminal-check grace period so late review comments are collected.

| Action | Meaning |
|---|---|
| `stop_pr_closed` | PR merged or closed |
| `stop_ready_to_merge` | CI and review gates clear; no conflicts or blocking comments |
| `stop_exhausted_retries` | Flaky retry budget exhausted |
| `stop_non_retryable_failure` | Diagnose and fix a deterministic failure |
| `stop_session_timeout` | Session timeout reached |
| `diagnose_hung_check` | A check exceeded the 30-minute threshold |
| `diagnose_merge_conflict` | Resolve a `CONFLICTING`/`DIRTY` PR |
| `diagnose_skipping_checks` | Investigate skipped/neutral checks |
| `wait_codex` | Mandatory Codex review is active |
| `wait_coderabbit` | Active CodeRabbit review is still running |

## Push discipline

Start fixing known branch failures immediately, but delay pushing until checks and active review
bots finish so all feedback can be included. Batch CI fixes, review feedback, and conflict
resolution into one tested push. Never push speculatively. See `references/heuristics.md`.

## Output

All modes emit JSONL. Snapshots include `pr`, `checks`, `failed_runs`, `codex_gate`,
`coderabbit_gate`, `hung_checks`, review item lists, `actions`, retry state, and terminal-check
elapsed time. `--watch` wraps snapshots in event envelopes.

`blocking_review_items` contains actionable unresolved inline comments. If thread lookup is
unavailable, blocking falls back to a 30-minute freshness heuristic.

## Cleanup

After a merged `stop_pr_closed`, switch away from the PR branch, remove its local branch and
worktree if present, and do not alter the remote branch.

When using worktrees, run `./gradlew check` before pushing; see
`docs/WORKTREE-GRADLE-PITFALLS.md`.
