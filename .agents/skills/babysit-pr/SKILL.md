---
name: babysit-pr
description: >
  Babysit a GitHub pull request after creation by continuously polling CI checks/workflow
  runs, new review comments, and mergeability state until the PR is ready to merge (or
  merged/closed). Diagnose failures, retry likely flaky failures up to 3 times, auto-fix
  and push branch-related issues when appropriate, and stop only when user help is required
  (e.g. CI infrastructure issues, exhausted flaky retries, or ambiguous/blocking review
  feedback). Use when the user asks to monitor a PR, watch CI, handle review comments, or
  keep an eye on failures and feedback on an open PR.
allowed-tools: Bash(python3 */skills/babysit-pr/scripts/*), Bash(gh pr *), Bash(gh run *), Bash(gh api *), Bash(git fetch *), Bash(git rebase *), Bash(git merge *), Bash(git checkout *), Bash(git switch *), Bash(git push *), Bash(git commit *), Bash(git diff *), Bash(git log *), Bash(git status), Bash(git branch *), Bash(git rev-parse *), Bash(git worktree *), Bash(./gradlew check *), Read, Edit
---

# PR Babysitter

Monitor a PR persistently until one of the terminal states is reached:
- PR merged or closed
- CI fully green, no unaddressed review comments, no merge conflicts
- A situation requiring user intervention

## Inputs

- No PR argument — infer from current branch (`--pr auto`)
- PR number — e.g. `123`
- PR URL — e.g. `https://github.com/<owner>/<repo>/pull/123`

## Core workflow

0. **Before running any script**, output a single line so the user knows which PR this conversation is tracking — e.g. `Babysitting PR [#123](https://github.com/<owner>/<repo>/pull/123)`. Resolve the PR number/URL from the user's input or the current branch first if needed.
1. Start with `--once` (default) — it blocks until something needs your attention, then returns.
2. Run the watcher to snapshot PR/CI/review state.
3. Inspect the `actions` list in the JSON output.
4. Diagnose CI failures — classify as branch-related (fix and push) vs. flaky (retry).
5. Process actionable review comments from trusted humans, Bugbot, and Codex.
6. Verify mergeability on each loop.
7. After any push, relaunch `--watch` in the same turn.
8. Continue until a terminal stop condition is reached.

## Key commands

```bash
# Wait until something needs attention, then return one snapshot (default)
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr auto --once

# Instant snapshot of current state (no waiting)
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr auto --snapshot

# Continuously poll, emitting JSONL snapshots (for streaming-capable consumers)
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr auto --watch

# Trigger a rerun of failed jobs for the current SHA
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr auto --retry-failed-now

# Explicit PR number or URL
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr 42 --once
python3 .agents/skills/babysit-pr/scripts/gh_pr_watch.py --pr https://github.com/<owner>/<repo>/pull/42 --snapshot
```

## Stop conditions

| `actions` value | Meaning |
|---|---|
| `stop_pr_closed` | PR was merged or closed — done |
| `stop_ready_to_merge` | CI green, no blocking reviews, no conflicts |
| `stop_exhausted_retries` | Flaky reruns hit the retry limit — user must investigate |
| `stop_non_retryable_failure` | Terminal failure is not in retry-eligible workflows — diagnose/fix before continuing |
| `stop_bugbot_not_green` | Cursor Bugbot is not SUCCESS (`neutral`, `skipped`, `failure`, or missing) — do not merge |
| `stop_session_timeout` | `--max-session-minutes` elapsed (default 90 min) — stop and report |
| `diagnose_hung_check` | A pending check has exceeded its hung threshold (Bugbot: 20 min, CI/E2E: 30 min) — stop and report |
| `diagnose_merge_conflict` | PR is merge-conflicted (`CONFLICTING` / `DIRTY`) — resolve conflicts before waiting on checks |
| `diagnose_skipping_checks` | One or more checks completed with `neutral`/`skipping` — investigate why |
| `wait_codex` | Codex is still reviewing (👀 reaction present on the PR) — do not push or merge |
| `wait_coderabbit` | CodeRabbit is active and still reviewing (its check is pending, or a 👀 reaction is present) — do not push or merge |

Keep polling when CI is running (`idle`), when new review items arrive (`process_review_comment`),
when Bugbot is still running (`wait_bugbot`), when Codex is still reviewing (`wait_codex`),
when CodeRabbit is still reviewing (`wait_coderabbit`), or when CI is green, but the PR is awaiting approval.

## Post-merge cleanup (when `stop_pr_closed` and PR is merged)

`stop_pr_closed` also fires for a PR that was closed without merging. Clean up only when the PR was merged: check
`pr.merged` in the snapshot, or run `gh pr view <n> --json mergedAt` and confirm that `mergedAt` is set. For a PR
that was closed without merging, keep everything and tell the user.

Run every step from the main checkout, never from inside the PR's worktree. If the current working directory is the
worktree, `cd` to the main checkout first. Skip any step whose worktree or branch does not exist locally.

1. **Remove the git worktree**, if the branch is checked out in one. Run `git worktree list` and look for an entry
   whose branch matches the PR's `head_branch`:
   ```bash
   git worktree remove /path/to/worktree
   ```
   Git refuses when the worktree has uncommitted changes. Do not force it; ask the user instead. Remove the
   worktree before deleting the branch, because Git will not delete a branch that a worktree still uses.

2. **Delete the local branch only when nothing would be lost.** Squash merges leave the branch looking unmerged,
   so `git branch -d` refuses and `git branch -D` is needed. Force-deleting a branch can lose commits, so first
   prove that the local tip is exactly the head that was merged:
   ```bash
   test "$(git rev-parse <head_branch>)" = "$(gh pr view <n> --json headRefOid --jq .headRefOid)" && git branch -D <head_branch>
   ```
   If the local tip differs, it has commits that were never merged. Keep the branch and tell the user.

**Only delete the local branch and worktree** — never touch remote branches (the remote is already deleted by GitHub's "delete branch on merge" setting or the `--delete-branch` flag used at merge time).

## Push discipline — batch all fixes before pushing (cost control)

Each push triggers new Bugbot + Codex runs. **Never push until all of the following are true:**

1. `./gradlew check` passes locally (no CI failures to fix after the push).
2. Neither Bugbot nor Codex is still `IN_PROGRESS` — wait for both to finish so their comments, if any, can be collected and fixed in the same push.
3. You have incorporated all currently visible actionable Bugbot and Codex comments into the pending local fix batch.

After pushing the fix batch, resolve all bot threads on GitHub (or reply + resolve when no code change is needed). No open bot threads should remain when the PR is merged.

**Workflow when fixes are needed:**

1. Collect all outstanding issues: failed CI logs + any Bugbot/Codex comments already posted.
2. Fix everything locally in one pass.
3. Run `./gradlew check` to confirm green.
4. Only then push — one push per fix cycle.

If either bot finishes while you are mid-fix and posts new comments, incorporate those fixes into the same commit before pushing.

Pushing also follows the approval rules in `AGENTS.md`: push only to the PR branch this task is working on, and only
after TDD is complete and `./gradlew check` passes.

## Conflict + Bugbot batching strategy (use this when PR shows `CONFLICTING`/`DIRTY`)

When GitHub reports merge conflicts while Bugbot/Codex/CI is still running:

1. **Do not push immediately.** Wait until neither Bugbot nor Codex is `IN_PROGRESS`.
2. Snapshot latest status/comments.
3. If conflict remains, merge `origin/main` into the PR branch. That keeps history intact. Rebasing rewrites the branch's history and needs a force push, so only rebase when the user asks for it.
4. Resolve conflicts and **in the same fix cycle** apply all actionable Bugbot/Codex comments.
5. Run `./gradlew check`.
6. Push once.

Rationale: this avoids paying for multiple Bugbot/Codex reruns and prevents a ping-pong where a conflict-fix push is immediately followed by a second bot-fix push.

## Bugbot + Codex merge gate (mandatory)

**Never merge until both Cursor Bugbot and Codex report clean.**

### Bugbot (CI check)

For Bugbot, only `SUCCESS` is a green gate.

- If Bugbot is still `IN_PROGRESS` → keep polling, do not push.
- If Bugbot conclusion is `NEUTRAL` → it found potential issues. Read the inline PR review comments left by `cursor[bot]`, fix every reported issue locally, run `./gradlew check`, then push once (see push discipline above).
- If Bugbot conclusion is `SKIPPING` → treat as **not green**. Bugbot may have posted review comments before skipping. Always check `gh api repos/{owner}/{repo}/pulls/{pr}/comments` for `cursor[bot]` comments. If comments exist, fix them before merging. If no comments exist, re-request review or ask the user.
- If Bugbot conclusion is `SUCCESS` → proceed (Bugbot gate is clear).
- Do **not** merge on NEUTRAL or SKIPPING, even if all other checks pass.

### Codex (emoji reaction)

Codex does **not** use a CI check. Instead it uses emoji reactions on the PR:

- **👀 reaction present** from `chatgpt-codex-connector[bot]` → Codex is actively reviewing. The `codex_gate.reviewing` field will be `true` and a `wait_codex` action will be emitted. Do not push or merge.
- **👀 reaction removed, no new review comments** → Codex is satisfied. Proceed.
- **👀 reaction removed, review comments posted** → Codex found issues. Fix them the same way as Bugbot comments (see push discipline).

The watcher automatically detects the 👀 reaction via the PR reactions API and surfaces `codex_gate` in the snapshot.
If the reactions lookup fails, `codex_gate.status` is `unknown`. The watcher cannot tell whether Codex is still
reviewing, so it does not emit `stop_ready_to_merge` until the lookup works again.

Codex also keeps a "Codex Review Summary" status table as a PR comment and edits it on every review. It is not a
finding, so the watcher ignores it.

### CodeRabbit (conditional)

CodeRabbit is a **presence-conditional** gate — it only applies when CodeRabbit is active on the PR.
When active, do not merge while `coderabbit_gate.reviewing` is `true` (a `wait_coderabbit` action is
emitted), and address its inline comments like Bugbot's before merging. When CodeRabbit shows no signs
of life, it is not a gate at all. See [CodeRabbit (presence-conditional gate)](#coderabbit-presence-conditional-gate)
for the full rules.

## Decision rules

See `references/heuristics.md` for the full classification checklist:
- **Branch-related failure**: edit the code, collect all other pending issues (Bugbot, Codex, human reviews), fix everything, run `./gradlew check`, then push once.
- **Likely flaky/unrelated**: rerun via `--retry-failed-now`; retry budget defaults to 3 per SHA.
  - The watcher only auto-reruns retry-eligible workflows (currently E2E-style workflows).
  - CI/check workflow failures are treated as diagnose/fix-first by default.
- **Ambiguous or requires product decision**: stop and ask the user.

## Review bots

The watcher surfaces feedback from:
- **cursor[bot]** — Cursor Bugbot (CI check-based code review; always a merge gate)
- **chatgpt-codex-connector[bot]** — OpenAI Codex (emoji reaction-based code review; always a merge gate)
- **coderabbitai[bot]** — CodeRabbit (CI check + inline review comments; a **presence-conditional** gate, see below)
- Trusted humans: authors with `OWNER`, `MEMBER`, or `COLLABORATOR` association

Comments by the account that `gh` is authenticated as are never surfaced as new review items. The agent usually
authenticates as the owner, so the owner's own unresolved inline threads still go into `blocking_review_items` and
block merge readiness.

### CodeRabbit (presence-conditional gate)

CodeRabbit is **not assumed to be present**. It only gates a PR when it shows signs of life on
that PR — a CodeRabbit CI check (`CodeRabbit`), a reaction from `coderabbitai[bot]`, or an authored
comment. When CodeRabbit is dormant the gate is inert and the watcher behaves as a bugbot+codex-only
gate, so nothing breaks if CodeRabbit is later removed from the repo.

- The watcher emits `wait_coderabbit` (and withholds `stop_ready_to_merge`) only while CodeRabbit is
  **active and still reviewing**: its check is pending, or it has a live 👀 reaction on the PR. The
  `coderabbit_gate` snapshot field exposes `active`, `present_check`, `reviewing`, and `status`.
- CodeRabbit's inline review comments are surfaced like any other review bot's: as `new_review_items`
  / `blocking_review_items` (`process_review_comment`). Address and resolve them before merge.
- CodeRabbit reports a zero-time `startedAt` (`0001-01-01T00:00:00Z`) on its check while queued; the
  hung-check detector ignores non-positive start timestamps and falls back to first-seen tracking, so
  a freshly queued CodeRabbit check never trips a false `diagnose_hung_check`.

> **Note**: if additional review bots are enabled on the repo (e.g. GitHub Actions summary
> bots), add their login keyword to `REVIEW_BOT_LOGIN_KEYWORDS` in `scripts/gh_pr_watch.py`. To
> make a bot a gate (not just surface its comments), wire a dedicated gate like `coderabbit_gate` —
> and prefer a presence-conditional one unless the bot is guaranteed to run on every PR.

## Worktree gotchas

When working from a git worktree, watch out for ktfmt CLI vs Gradle plugin version
mismatches and rebases silently reverting fixes. Always run `./gradlew check`
before pushing. See [docs/WORKTREE-GRADLE-PITFALLS.md](../../../docs/WORKTREE-GRADLE-PITFALLS.md)
for details and a pre-push checklist.

## Choosing a mode based on harness capabilities

The right mode depends on whether the harness can stream bash tool stdout back to the
model while the command is still running, or only delivers the final output after exit.

### Harness streams tool output to the model (e.g. Claude Code subagents)

Use `--watch`. The script runs continuously, emitting JSONL snapshots as events. The
model sees each snapshot as it arrives and can act on it (retry, fix, merge) without
waiting for the script to exit. The script exits on terminal stop conditions.

### Harness only returns output after tool exit (e.g. Pi, most tool-use loops)

Use `--once` (the default). The script blocks internally, polling every 30 seconds,
and only returns to the model when something needs agent attention — a CI failure to
diagnose, a review comment to process, a merge readiness signal, etc. The model never
sleeps blindly; the script handles all waiting. After acting on the result, the model
calls `--once` again to wait for the next event.

Typical agent loop:
1. Run `--once` → script blocks until CI finishes, review arrives, etc.
2. Model reads the snapshot, acts on `actions` (fix code, retry, merge).
3. If not terminal, run `--once` again → repeat.

### Quick debugging / one-off inspection

Use `--snapshot` for an instant point-in-time view with no waiting.

## Output format

All modes emit newline-delimited JSON.

Where the `actions` list is depends on the mode:

| Mode | Read the actions from |
|---|---|
| `--once`, `--snapshot` | top-level `actions` |
| `--retry-failed-now` | `snapshot.actions`. The top level reports the rerun: `rerun_attempted`, `rerun_count`, `reason`. |
| `--watch` | `payload.snapshot.actions` on `snapshot` events, `payload.actions` on `stop` events |

`--watch` emits event envelopes:
- `{"event":"snapshot","payload":{"snapshot":{...},"state_file":"...","next_poll_seconds":30}}`
- `{"event":"stop","payload":{...}}`

`blocking_review_items` contains actionable unresolved inline review comments. When the thread-resolution lookup is unavailable, the watcher fails closed: every actionable inline review comment blocks, whatever its age. While the list is not empty, `stop_ready_to_merge` is not emitted.

`checks.failed_count` includes cancelled checks. `gh pr checks` exits 1 when a check failed and 8 while checks are pending; the watcher still reads the JSON it prints in both cases.

Example snapshot payload shape (`--once` / `--snapshot`, or `--watch` under `payload.snapshot`):

```json
{
  "pr": { "number": 42, "head_sha": "abc123", "mergeable": "MERGEABLE", ... },
  "checks": { "pending_count": 0, "failed_count": 1, "passed_count": 8, "skipping_count": 0, "all_terminal": true },
  "failed_runs": [{ "run_id": 123, "workflow_name": "CI", "conclusion": "failure", "retry_eligible": false, ... }],
  "bugbot_gate": { "status": "completed", "conclusion": "success", "is_success": true, ... },
  "codex_gate": { "reviewing": false, "status": "idle" },
  "coderabbit_gate": { "active": true, "present_check": true, "reviewing": false, "status": "active" },
  "hung_checks": [{ "name": "CI", "elapsed_seconds": 1920, "threshold_seconds": 1800 }],
  "new_review_items": [],
  "blocking_review_items": [],
  "actions": ["diagnose_ci_failure", "stop_non_retryable_failure"],
  "retry_state": { "current_sha_retries_used": 0, "max_flaky_retries": 3 }
}
```
