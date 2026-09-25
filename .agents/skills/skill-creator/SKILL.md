---
name: skill-creator
description: Create, revise, evaluate, package, and optimize agent skills. Use when users want to turn a workflow into reusable skill guidance, improve an existing skill, add evals or bundled resources, test skill behavior with subagents, benchmark skill quality, or tune a skill trigger description.
license: See LICENSE.txt
---

# Skill Creator

## Core idea

A skill is compact, reusable operational knowledge for agents. Good skills capture what agents would otherwise miss: local conventions, fragile workflows, non-obvious gotchas, exact commands, validation loops, and output formats.

Write skills from evidence, not vibes. Extract from completed tasks, runbooks, review comments, incidents, code, observed agent failures, or corrections the user made. Then evaluate and iterate.

Keep this skill agent-neutral by default. Use `agent`, `model`, `subagent`, `harness`, or the current product name. Do not mention vendor-specific UIs, hidden configuration directories, or single-provider CLI commands unless the skill being edited is explicitly for that environment or you are explaining optional compatibility tooling.

## Operating loop

1. **Capture intent**
   - What task should this skill improve?
   - When should it trigger? Include concrete user phrases, symptoms, tools, file types, and failure modes.
   - What output or behavior counts as success?
   - What should the skill explicitly not do?
   - In projects that bundle skills, should the result be bundled/exported (`include`) or non-bundled repo-local maintainer guidance (`exclude`)?

2. **Gather source material**
   - Extract everything already available from the prompt and conversation before asking questions.
   - If the user references a workflow but the transcript or artifacts are not available, state that limitation, draft from the available prompt, and mark placeholders instead of blocking on broad questions.
   - Prefer real conversations, working commands, bug fixes, review feedback, specs, runbooks, and existing skills.
   - Record corrections the user made; these are usually the best gotchas.
   - Avoid generic advice the model already knows.

3. **Draft or refactor the skill**
   - If the user asks to create or turn a workflow into a skill, produce a concrete draft `SKILL.md` with frontmatter unless a required decision is genuinely missing.
   - Keep `SKILL.md` focused on the common path and high-value gotchas.
   - Move optional or bulky material to `references/`, deterministic helpers to `scripts/`, and templates/assets to `assets/`.
   - In `SKILL.md`, state exactly when to read each support file.
   - Use exact commands for fragile operations, principles for judgment-heavy work, and validation loops when quality matters.
   - For bundled/exported skills, audit portability with `references/portable-skill-audit.md` before finalizing.

4. **Create eval prompts**
   - Add realistic prompts to `evals/evals.json` when the skill has testable behavior.
   - Include happy-path cases, near misses, and pressure cases where an agent might rationalize skipping the skill.
   - Make expectations objectively checkable where possible.

5. **Run evals and inspect traces**
   - Compare baseline behavior with skill-assisted behavior whenever practical.
   - For new skills, baseline is no skill. For existing skills, baseline is the previous version or an explicit snapshot.
   - Inspect transcripts, not just final answers: wasted exploration, skipped validation, wrong tool choices, and rationalizations indicate unclear guidance.

6. **Refactor and repeat**
   - Cut instructions that did not affect behavior.
   - Add concrete gotchas for mistakes agents actually made.
   - Bundle scripts when agents repeatedly reinvent brittle logic.
   - Re-run evals after changing behavior or trigger descriptions.

## Recommended layout

```text
skill-name/
  SKILL.md                 # required: triggerable core instructions
  evals/evals.json         # recommended for testable behavior
  references/              # optional docs loaded only when needed
  scripts/                 # optional deterministic helpers
  assets/                  # optional templates or files used in outputs
```

For project-local skills, follow the repository's existing skills directory convention. For portable skills, keep paths, commands, and terminology harness-neutral unless the skill intentionally targets one harness.

## Frontmatter

Use YAML frontmatter at the top of `SKILL.md`.

```yaml
---
name: concise-hyphenated-name
description: Use when [specific triggering situations, symptoms, or user intents]
compatibility: Optional. Mention required tools or environments only when useful.
---
```

Guidance:
- `name`: lowercase letters, numbers, and hyphens.
- `description`: the primary trigger. Include both what the skill does and when to use it.
- Put trigger conditions in the description, not buried only in the body.
- Keep the description agent-neutral unless the skill is harness-specific.
- Do not pack the full workflow into the description; that can make agents shortcut without reading `SKILL.md`.

## What belongs in `SKILL.md`

Include:
- Core workflow, defaults, and validation gates.
- Gotchas the agent is likely to miss.
- Exact commands for fragile operations.
- Short output templates.
- Pointers to support files with clear load conditions.

Avoid:
- Generic best practices with no local or domain specificity.
- Long background explanations.
- Menus of equal options when one default is best.
- One-off narrative history.
- Bulky API docs or examples that only apply sometimes.
- Harness-specific instructions that do not apply to the target environment.

Ask for each paragraph: “Would an agent likely get this wrong without the skill?” If not, cut it.

## Progressive disclosure patterns

Use support files deliberately:

- `references/`: store topic-specific Markdown docs that should be read only when that subtopic appears.
- `scripts/`: store deterministic, repetitive, or easily validated helpers.
- `assets/`: store templates or static files used in outputs.

Example pointers:

```markdown
If adding evals or pressure tests, read `references/testing-skills-with-subagents.md`.
If auditing bundled/exported portability, read `references/portable-skill-audit.md`.
If the skill includes a large API surface, move that detail into a named reference file and tell the agent when to read it.
Run the bundled validation script before finalizing generated files.
```

Keep references one level deep from `SKILL.md` where possible. For long reference files, include a table of contents.

## Instruction style

Calibrate control to task fragility:
- Use strict sequences for mutating, fragile, security-sensitive, or compliance-heavy workflows.
- Use principles and checklists for judgment-heavy tasks.
- Explain why when the agent needs flexibility.
- Prefer a clear default plus an escape hatch over a menu of equivalent options.

Useful sections:
- `Overview`: one or two sentences.
- `When to use`: triggers and non-triggers if not obvious from the description.
- `Workflow`: stepwise procedure or checklist.
- `Gotchas`: concrete mistakes agents make.
- `Validation`: commands or self-checks before completion.
- `Output format`: template when exact shape matters.

## Evals

Use evals to test whether the skill changes behavior, not just whether the text sounds good.

For installable or reusable skills, store prompts in `evals/evals.json`:

```json
{
  "skill_name": "example-skill",
  "evals": [
    {
      "id": 0,
      "name": "descriptive-case-name",
      "prompt": "Realistic user task prompt",
      "expected_output": "Observable expected behavior",
      "files": [],
      "expectations": [
        "Uses the intended tool or workflow.",
        "Avoids the known bad shortcut."
      ]
    }
  ]
}
```

When running evals:
- Use realistic prompts, not abstract quizzes.
- For mutating workflows, make evals plan-only unless the user explicitly authorizes real mutation.
- Record failures as skill improvement opportunities.
- For discipline or compliance skills, read `references/testing-skills-with-subagents.md` before designing pressure tests.
- For schema details used by the bundled viewer and benchmark tools, read `references/schemas.md`.

## Running and reviewing evals

Use this sequence when the user wants more than a quick edit.

1. Create an iteration workspace next to the skill directory, for example `<skill-name>-workspace/iteration-1/`.
2. For each eval, create a descriptive directory such as `requires-sandbox-validation/`.
3. Run comparison cases:
   - New skill: `with_skill/` versus `without_skill/`.
   - Existing skill: `new_skill/` versus `old_skill/` from a snapshot taken before editing.
4. Save each case's prompt and expectations in `eval_metadata.json`.
5. If subagents are available, run all comparison cases in the same turn so timing and model drift are comparable.
6. If the subagent notification includes `total_tokens` and `duration_ms`, save them immediately to that run's `timing.json`.
7. Grade objective expectations into `grading.json`. Use the fields `text`, `passed`, and `evidence`; the viewer depends on those names.
8. Aggregate results with:

```bash
python -m scripts.aggregate_benchmark <workspace>/iteration-N --skill-name <name>
```

9. Launch the review UI:

```bash
python <skill-creator-path>/eval-viewer/generate_review.py \
  <workspace>/iteration-N \
  --skill-name "my-skill" \
  --benchmark <workspace>/iteration-N/benchmark.json
```

For headless environments, add `--static <output_path>` and send the generated HTML to the user. For iteration 2+, also pass `--previous-workspace <workspace>/iteration-<N-1>`.

10. Read `feedback.json` when the user is done, then revise the skill. Empty feedback means that output was acceptable.

Do not write a custom review UI unless the bundled viewer cannot handle the task. The point is to get comparable outputs in front of the human quickly.

## Improving an existing skill

When revising a skill:

1. Snapshot the original skill before editing if you plan to benchmark against it.
2. Generalize from feedback instead of overfitting to one eval.
3. Read traces to see where agents hesitated, skipped steps, or invented unnecessary tools.
4. Keep the prompt lean. Remove content that did not change behavior.
5. Add scripts only when repeated traces show agents reinventing the same brittle logic.
6. Update `skill-source.json` whenever you fold in new source material, examples, scripts, references, or policy from another repo or workflow.
7. Re-run the same evals after each behavior-changing edit.

If a skill is mainly reference material, don't force an elaborate benchmark. A concise review plus targeted validation may be enough.

## Trigger description optimization

The `description` field controls skill discovery. After creating or improving a skill, offer to tune the description.

Default approach:
1. Generate 20 realistic trigger eval queries: 8-10 should trigger, 8-10 should not trigger.
2. Use near misses for negative cases, not obviously unrelated prompts.
3. Ask the user to review the eval set.
4. Evaluate the current description against the set using the current harness if it supports trigger testing.
5. Revise the description based on failures, then re-test on held-out queries where possible.
6. When reporting the plan, explicitly say whether the bundled trigger scripts are usable in the current harness or are being skipped.

The bundled `scripts/run_loop.py` and `scripts/run_eval.py` are compatibility helpers for environments that expose the expected trigger-testing command-line interface. Treat them as optional harness-specific tooling, not the default workflow for every agent environment. Do not present them as required for trigger tuning; if they are unavailable, use the current harness's eval mechanism and say what confidence is lost.

## Agent-neutrality checklist

Before finalizing a skill, verify:

- The description names the task and trigger conditions, not one vendor's UI.
- Paths and commands are portable or clearly scoped to the target repository.
- The body uses `agent`, `model`, `subagent`, `harness`, or the current product name instead of unrelated agent-platform names.
- Domain-specific terms from the user's requested skill topic are preserved when they are part of the actual task; portability means avoiding accidental harness/vendor assumptions, not erasing the domain.
- Any vendor-specific command is either required by the skill's domain or explicitly labelled optional compatibility tooling.
- User-facing viewer text, templates, and examples do not tell the user to return to the wrong product.
- The skill does not assume unavailable tools such as a browser, a particular CLI, or a specific subagent implementation without saying so.

## Reference files

- `references/agent-skill-best-practices.md` — concise authoring guidance adapted for agent-neutral skills.
- `references/testing-skills-with-subagents.md` — TDD-style skill testing and pressure scenarios.
- `references/persuasion-principles.md` — why some compliance guidance needs bright-line language.
- `references/examples/AGENT_GUIDANCE_TESTING.md` — worked example of testing skill-discovery guidance.
- `references/schemas.md` — exact JSON schemas expected by the bundled eval viewer and benchmark tools.

## Final handoff

When done, summarize:
- Skill files changed.
- New or updated evals.
- Validation performed.
- Any remaining harness-specific assumptions.
