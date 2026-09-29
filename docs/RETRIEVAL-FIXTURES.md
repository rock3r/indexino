# Versioned, bounded retrieval ground truth

`src/test/resources/fixtures/retrieval-v1/` is a newly authored invented corpus. It contains no
copied repository code. `workspace/` is a parse-only Gradle root, not a build to execute.
`ground-truth.json` and `lifecycle.json` were labelled independently of Indexino output.

## Meaning of a result

Indexino's Kotlin/Java references are syntactic candidates, **not compiler resolution**. The fixture
distinguishes those contracts explicitly:

- `syntactic` cases assert exact supported retrieval: cross-language methods, alias receivers,
  different-arity overloads, receiver shadowing, unrelated same-name methods, comment/string
  negatives, and XML resource qualifiers.
- `semantic` cases retain the type-specific answer for same-arity overloads. A parse-only result
  may contain both overload candidates. Report those extra references as semantic false positives;
  do not rewrite the labels, suppress the case, or claim exact compiler accuracy.

Kotlin receiver lookup respects the nearest lexical variable binding even when its type is unknown.
It uses explicit types and, for local properties, simple constructor/call initializers resolved in
the declaration's scope. A visible function wins over a same-named constructor candidate; a function
without an explicit return type stays unknown. Unknown bindings do not fall back to outer variables,
imports, or objects. This is bounded syntax analysis, not inference through arbitrary expressions,
factory bodies, assignments, delegates, or classpaths. `KotlinReceiverBindingTest` and
`KotlinShadowingQueryTest` exercise both rejected false positives and retained positive references.

Git attributes preserve the fixture's LF bytes on Windows as well as Unix; Kotlin PSI requires
normalized line separators. This does not claim general CRLF source support.

Each `references` case selects a declaration by FQN, file, and declaration line, then queries its
generation-local ID. `scopeFiles` plus `language` defines the **exhaustively labelled query scope**.
Kotlin declaration locations exclude leading comments and KDoc; explicit modifiers and annotations
remain part of the declaration start. Columns do not shift to the identifier alone.
Only rows in that scope enter that case's comparison. The result identity here is file + line
(plus qualifiers for resources); the authored corpus has at most one matching occurrence per line.
Do not generalize this identity to corpora with multiple same-line occurrences.

## JSON and pagination

Both documents carry `version: 1`. Ground-truth cases contain `id`, `kind`, `language`, `category`,
`heldOut`, `contract`, `exhaustive`, `scopeFiles`, a `symbol` or `resource` query, and exact `expected`
rows. Resource `namespace` maps to the public resource package name; qualifier arrays split the
public hyphen-separated qualifier string, with an empty array for the default variant.

Consume every page before comparing sorted **multisets**, including duplicates. Page size 1 exercises
the two-candidate reference and three-resource boundaries. Missing symbol selectors must be errors,
not vacuous empty-reference successes. Compare `hasMore` and cursor termination as well as rows.

The ordinary host-window tests in `IndexSnapshotStorageFailureTest` separately distinguish exactly
10,000 results from 10,001, check the last legal offset/cursor page, and reject offset + limit above
the window before storage access. **10,000 is current implementation policy**, not a new public ABI
promise. The synthetic source corpus need not contain ten thousand source declarations.

## Scores and held-out cases

Report true positives, false positives, and false negatives by query ID, language, category,
contract, and held-out status. Precision is TP / (TP + FP); recall is TP / (TP + FN). A zero
denominator is undefined, not automatically 100%. Negative cases still require zero false positives.
No whole-repository or compiler-accuracy aggregate follows from these small bounded scopes.

`held-out-alias` is reserved from tuning. Run it only for final evaluation and retain its result
separately. If a future fix is developed against a held-out failure, mark that case as development
data in a **new fixture version** and author fresh held-out examples. Keep v1 labels immutable after
publication; corrections require an explained version change, not normalization to actual output.

## Refresh lifecycle

`lifecycle.json` starts at `fixture.lifecycle.Marker` and applies five sequential edits to a disposable
workspace copy: move a declaration line, add a second same-FQN declaration, rename the first file,
delete it, and delete the final definition. Each step specifies exact resulting locations.
The duplicate-FQN stage is intentionally parse-only; the fixture is not a compilable program.

Use `IndexScope.gradle(":").includingDependencies()` for the whole-root fixture: root discovery
reports dependency inclusion even without explicit dependency edges. Use manual in-process refresh
with auto-refresh disabled and a disposable cache outside the workspace.
After each successful refresh, query the new snapshot and check old paths/lines disappear. Previously
pinned snapshots must retain their old results. The public acceptance driver owns executing these
operations; fixture data is not permission to mutate any real checkout.
