# Contributing

> **Experimental project** — APIs, index layout, and CLI contracts may change without
> notice. See [LICENSE](LICENSE) (Universal Ethical License v1.0).

## Workflow

After the initial GitHub bootstrap, **all changes land through pull requests**:

1. Branch from `main` (feature branches, worktrees, or topic branches are all fine).
2. Make focused changes; keep `./gradlew check` green locally before pushing.
3. Open a PR against `main`.
4. **Babysit CI** — wait for the [`check`](.github/workflows/check.yml) workflow (and any
   review bots enabled on the repo) to finish. Fix failures locally, batch fixes into one
   push per cycle, and re-run `./gradlew check` before pushing again.
5. Merge with **squash merge** once checks are green and review feedback is addressed.
6. Delete the branch after merge (local and remote).

Do not push directly to `main` after bootstrap except for emergencies; prefer a PR even for
small fixes so CI always runs.

## Local checks

```bash
./gradlew check          # unit tests (required before every push)
./gradlew shadowJar      # fat JAR smoke build
```

CI runs the same `./gradlew check --no-daemon` on Ubuntu with **Java 21**. Bazel is not
required in CI — tests use fixture data under `src/test/resources/fixtures/`.

## Agent / automation notes

Agents working in this repo should read [AGENTS.md](AGENTS.md) and follow TDD
(test-first) for production changes. For open PRs, use the
[babysit-pr](.agents/skills/babysit-pr/SKILL.md) skill to poll CI, address review
comments, and merge only when checks (including Bugbot/Codex when enabled) are green.

## Commit and PR hygiene

- One logical change per PR when possible; link related follow-ups instead of scope creep.
- PR titles: `<area>: <what changed and why it matters>` (see
  [git-github-ops skill](.agents/skills/git-github-ops/SKILL.md)).
- Do not merge with failing `check` workflow or unresolved blocking review threads.
