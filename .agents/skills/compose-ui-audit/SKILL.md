---
name: compose-ui-audit
description: >
  Use when the user asks to audit, assess, review, or find systemic issues in Compose Desktop
  or Jewel UI architecture: state/effect usage, recomposition behavior, lazy-list keys,
  composable API hygiene, modifier conventions, and desktop interop seams. For SelectionContainer
  / DisableSelection context, run compose-selection-audit first. Produces an evidence-first report
  with severity-ranked findings and top fixes. Desktop/Jewel focus — not Android lifecycle,
  Navigation, Material mobile patterns, or baseline profiles.
---

# Compose UI Audit

Audit Compose/Jewel UI code for architectural correctness and Compose runtime hygiene. This is a
read/evidence workflow, not a visual design review.

For **selection-container context** (interactive composables inside `SelectionContainer` without
`DisableSelection`, nested SC), use **`compose-selection-audit`** first — it invokes
Indexino for conclusive lexical facts.

Keep the audit proportional. A focused request on one file should get a focused file review; a
broad request should map the surface, sample representative files, and report systemic patterns.

## Scope

In scope:

- composition-vs-presenter boundary violations
- app/state/workflow logic inside composables
- `remember`, `derivedStateOf`, and local state misuse
- `LaunchedEffect`, `DisposableEffect`, `snapshotFlow`, and coroutine-scope misuse
- lazy-list keys and `contentType`
- modifier forwarding/order, composable size, file organization, KDoc, and reusable composable API shape
- Detekt/Compose-rules signals that affect UI API contracts, state ownership, effects, or hot-path performance (when Detekt is available in the target repo)
- recomposition/performance smells in Compose Desktop code
- desktop flow collection and coherent screen projections
- state-holder shape and testability
- desktop seams: focus, keyboard shortcuts, dialogs/file pickers, clipboard/open URI, popups,
  window-local state, and protocol/runtime-backed UI boundaries

Out of scope unless the user explicitly asks:

- Jewel component choice, theme wrappers, icon APIs, typography details (defer to target repo docs)
- low-level Swing/AWT interop mechanics
- Android lifecycle, `collectAsStateWithLifecycle`, Navigation, Material mobile patterns, Accompanist, baseline profiles, R8
- numeric Android-style 0-100 scoring
- broad Detekt cleanup when Detekt is not used in the target repo

## Output Shape

```markdown
## Summary
[overall posture: healthy / targeted cleanup needed / risky]

## Top findings
1. [severity] [finding] — `path:line`
2. ...

## Category notes
### Boundary and state ownership
### Desktop lifecycle and flow collection
### Effects and coroutine usage
### Lazy lists and recomposition
### Modifier/API shape
### Desktop interop seams

## Top 3 fixes
1. ...

## Confidence and limits
[what was sampled, what was not checked]
```

Do not assign numeric scores by default. If the user explicitly requests scoring, read
[reporting-guidance.md](references/reporting-guidance.md), label scoring as Compose Pi-specific heuristic, and avoid
importing Android release metrics.

## Audit Process

### 1. Confirm target

If the user gives paths, audit those. Otherwise locate the UI source root from the project's
architecture docs or by searching for `@Composable`. Skip generated/build directories and tests
unless the user asks.

### 2. Map the surface

Find representative files before judging:

- screens and full layouts
- reusable components
- chat timeline / lazy lists
- settings panels
- permission/review workflow UI
- state-holder classes
- presenter projection classes consumed by UI

For broad audits, name the sampled surface in the report.

### 3. Gather evidence

Search hits are leads, not findings. Read surrounding code before reporting. For the full pattern list, read
[audit-search-patterns.md](references/audit-search-patterns.md).

### 4. Judge by Compose Pi categories

Use the category checklist below, but avoid re-reading every reference file. Open only the reference files needed for
actual evidence you find.

| Category | What to check | Read when deeper diagnosis is needed |
|---|---|---|
| Boundary and state ownership | Business/app logic in composition, broad app-state reads, local state shadowing app state, presenter objects passed into leaf components | [boundary-violations.md](references/boundary-violations.md) |
| Desktop lifecycle and flow collection | Multiple raw flows that must be coherent, resource-producing flows started from leaves, composition-owned file watchers/subprocesses/loops | [effects/overview.md](references/effects/overview.md) |
| Flow state/event modeling | Public mutable flows, `stateIn`/`shareIn` in functions or composables, `WhileSubscribed` with required fresh `.value`, lossy `SharedFlow` outcomes, wrong `combine`/`merge`/`zip`/`flatMap*` choice | [`kotlin-flow-state-event-modeling`](../kotlin-flow-state-event-modeling/SKILL.md) |
| Effects and coroutine usage | Work launched from bodies, bad effect keys, stale callbacks, missing cleanup, noisy `snapshotFlow`, business workflows in effects | [effects/overview.md](references/effects/overview.md) |
| Lazy lists and recomposition | Missing stable keys/content types, per-row lookups, row/model churn, hot-path transforms, stale `remember` caches for theme/environment-derived UI values | [lazy-layouts/overview.md](references/lazy-layouts/overview.md), [stability/overview.md](references/stability/overview.md) |
| Modifier/API shape | Swallowed modifiers, missing/misordered `modifier`, `MutableState<T>`/`State<T>` component APIs, unstable or mutable parameters, event/content-slot parameter shape, action/presenter `CompositionLocal`s, unnecessary `Modifier.composed {}`, huge/deeply nested composables, poor file ordering, missing/stale KDoc on reusable composables | [api-hygiene.md](references/api-hygiene.md), [modifiers/overview.md](references/modifiers/overview.md) |
| Desktop interop seams | AWT/Swing details in leaves, shortcut paths bypassing presenter checks, persisted window/session policy in `remember`, protocol/runtime work in UI | [subcomposition/overview.md](references/subcomposition/overview.md) |

### Composable Size, Nesting, And API Hygiene

When the target includes reusable component APIs, public/cross-file composables, Detekt findings, or lint-like Compose
smells, read [api-hygiene.md](references/api-hygiene.md). It distils Detekt's comments/naming/complexity/coroutines/
performance guidance and Nacho López' Compose Rules into Compose Pi Desktop audit heuristics.

Review composables as production code, not as throwaway markup. Flag composables that are so large, deeply nested, or
mixed-purpose that they obscure state ownership, make recomposition behavior hard to reason about, or prevent focused
testing/reuse.

Prefer findings that point to a specific extraction boundary: a toolbar, row, empty state, footer, popup, repeated
section, state holder, or distinct branch. Do not ask for arbitrary fragmentation; extraction should make the UI easier
to read, name, test, or reuse.

Also check file organization and API polish:

1. public screen/entry-point composables should appear near the top of the file;
2. private sub-composables should generally follow in top-down usage order;
3. helpers/constants should stay close to the code that uses them unless project conventions say otherwise;
4. public or cross-file reusable composables should have KDoc explaining purpose, important parameters, slot
   expectations, modifier behavior, and non-obvious desktop effects.

For private helpers, KDoc often means the helper needs a clearer name or smaller scope instead.

For reusable component API design, use the AndroidX Compose API guidelines as high-level reference material, adapted for
Compose Desktop/Jewel and without importing Android-only assumptions:

- [Compose API guidelines](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md)
- [Compose component API guidelines](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-component-api-guidelines.md)

### Theme And Environment-Sensitive `remember` Keys

Check `remember(...)` calls that cache UI values derived from theme, density, locale, font scale, window state, Swing LaF defaults, or similar environment-provided state. Coarse keys such as a theme name, LaF name, dark-mode boolean, or no key can leave stale UI when the underlying colours, typography, metrics, strings, shortcut text, or defaults change without that coarse label changing.

Report this only when stale output is plausible and user-visible. Good fixes are, in order:

1. remove `remember` if the calculation is cheap;
2. key by the concrete inputs used by the calculation;
3. key by a framework-provided theme/environment change token when the dependency is broad or not practical to enumerate, such as `JewelTheme.instanceUuid` in Jewel code.

Do not mechanically demand more keys for every `remember`; the finding needs a concrete stale-cache risk.

### 5. Optional: performance diagnosis

If the task involves jank, slow UI, or recomposition issues, measure before diagnosing and again after any proposed fix.
Use the relevant existing reference instead of duplicating the procedure here:

- Measurement methodology: [measurement/overview.md](references/measurement/overview.md)
- Compiler stability reports: [stability/overview.md](references/stability/overview.md)
- Modifier phase violations: [modifiers/overview.md](references/modifiers/overview.md)
- Lazy list scroll perf: [lazy-layouts/overview.md](references/lazy-layouts/overview.md)
- Subcomposition overhead: [subcomposition/overview.md](references/subcomposition/overview.md)

### 6. Optional: Perfetto tracing

For jank, recomposition, or rendering performance with trace evidence:

- Start with a hypothesis: what operation, frame interval, or user action is suspected?
- Capture a before trace, identify the relevant slice/thread/metadata, then make one targeted change.
- Capture an after trace with the same scenario; do not claim a performance fix from code shape alone.
- Setup: [tracing-setup.md](references/tracing-setup.md)
- Usage: [tracing-usage.md](references/tracing-usage.md)
- For Compose Pi's tracing facade and categories: [`docs/TRACING.md`](../../../docs/TRACING.md)

## Reporting Rules

Read [reporting-guidance.md](references/reporting-guidance.md) before producing the final report. In short:

- Cite concrete file paths and line numbers where possible.
- Prefer 2-4 representative examples for systemic findings.
- Do not infer runtime problems from a grep hit alone.
- Do not double-count one root cause across categories.
- Separate Compose runtime/architecture findings from Jewel component-choice findings.
- When no serious issues are found, say so with confidence/limits and low-priority polish only.
