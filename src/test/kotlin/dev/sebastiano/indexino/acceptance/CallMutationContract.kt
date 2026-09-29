// SPDX-License-Identifier: UEL-1.0
package dev.sebastiano.indexino.acceptance

internal fun assertCallMutationRows(
    expected: List<String>,
    references: List<String>,
    calls: List<String>,
) {
    check(references.sorted() == expected.sorted()) {
        "Reference rows: expected=$expected actual=$references"
    }
    check(calls.sorted() == expected.sorted()) { "Caller rows: expected=$expected actual=$calls" }
}
