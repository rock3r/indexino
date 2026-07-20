# Testing

## Expectations

1. **Unit tests for analysis logic** — walker, callee matching, exclusion rules, nested SC
   counting. Use fixture `.kt` files under `src/test/resources/fixtures/`.
2. **Unit tests for topology** — `.bazelproject` parsing, BUILD snippet parsing (no live Bazel
   required in CI).
3. **Edge cases** — nested SC, DisableSelection between site and SC, no SC, import aliases,
   markdown `selectable = true` when configured.
4. Run `./gradlew test` before considering a change complete.

Distribution and publication contracts use dedicated tagged test tasks and are excluded from the
ordinary unit-test task:

```bash
./gradlew verifyShrunkCli       # manifest launch, full fixture workload, services, size ceiling
./gradlew verifyMavenPublication # thin artifact and distribution-variant non-leakage
```

Kotlin ABI validation is part of `./gradlew check`. Until the first embedded API is defined,
`api/indexino.api` is intentionally empty and `checkKotlinAbi` fails on any accidental public
declaration:

```bash
./gradlew checkKotlinAbi
```

Run `updateKotlinAbi` only when intentionally accepting a reviewed public API change; never use it
as an automatic CI repair.

`verifyShrunkCli` runs only CLI behavior through `shrunkCliJar`; the unshrunk JAR is retained as a
size baseline and diagnostic fallback. The fixture must exercise Kotlin, Java, Android XML, Xodus,
selection-context, status/freshness, deterministic symbol/reference queries, and resource lookup.

## TDD Red-Green Cycle

1. Write the test first.
2. Run targeted test; confirm **assertion failure** (not merely compile error).
3. Minimum implementation.
4. Confirm green.
5. Full `./gradlew test`.

```bash
./gradlew test --tests "com.selectionindex.analysis.SelectionWalkerTest.nested selection containers"
```

Do not skip the red step.

## Fixture Layout

```
src/test/resources/fixtures/
  nested-selection-containers.kt
  disable-selection-in-sc.kt
  no-selection-container.kt
  import-alias-selection-container.kt
```

Fixtures are minimal `@Composable` snippets — valid Kotlin syntax, no external deps required
for parse-only tests.

## Testing Anti-Patterns

**Do not** read production source from disk in tests and assert `source.contains("token")`.

**Do not** test Kotlin/PSI behavior by substring-matching generated walker output strings
without structural assertions on `SelectionContext` fields.

| Claim | Right tool |
|-------|------------|
| "call site is inside SC" | Parse fixture → locate call at line → assert `inSelectionContainer` |
| "excluded by DisableSelection" | Fixture with SC + DS + button → assert `excludedByDisableSelection` |
| "two SCs on path" | Nested SC fixture → assert `selectionContainerCount == 2` |
| "Bazel query returns these files" | Mock process output or golden-file parsed BUILD |

## Live Bazel Tests

Optional `@Tag("bazel")` tests that require `bazel` on PATH and a fixture workspace. Excluded from
default `./gradlew test`; run explicitly when developing topology:

```bash
./gradlew test --tests "*BazelTopology*" -PbazelTests
```

Keep CI green without Bazel installed.

## Test Completeness

Before marking work done, verify every scenario in the plan has a test that was red then green.
