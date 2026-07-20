# Project-local skills

Agent-facing playbooks for developing and using Indexino. Each skill has a
`SKILL.md` with full instructions.

## Skills

### `git-github-ops`

**Purpose:** Diff-grounded commits, non-interactive push, PR text via temp files, `gh` automation.
**Use when:** Commit, push, PR creation, review replies, merge prep.

### `using-git-worktree`

**Purpose:** Isolated git worktrees with directory selection and safety checks.
**Use when:** Starting feature work that should not touch the main checkout.

### `babysit-pr`

**Purpose:** Poll PR CI, review comments, mergeability; retry flaky jobs; stop on human input.
**Use when:** A PR is open and should be watched until merge-ready.

### `compose-ui-audit`

**Purpose:** Evidence-first Compose/Jewel UI audit (boundaries, effects, lazy lists, API shape).
**Use when:** Auditing UI architecture in target repos. Pair with `compose-selection-audit` for
selection-container findings.

### `compose-selection-audit`

**Purpose:** Run indexino `--application selection-context` against a persistent `.indexino/` store.
**Use when:** Auditing SC/DisableSelection in Compose/Jewel UI on Bazel monorepos.

### `skill-creator`

**Purpose:** Author or improve skills in `.agents/skills/`.
**Use when:** Adding or refining agent workflows for this repo.
