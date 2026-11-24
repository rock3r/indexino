package dev.sebastiano.indexino.core.key

/**
 * Orders keys exactly as the staging store persists them.
 *
 * Xodus `StringBinding` writes each UTF-16 code unit as its own UTF-8 sequence (CESU-8), so a
 * surrogate pair is six bytes whose lead byte (`0xED`) sorts below the `0xEE`/`0xEF` leads of
 * U+E000..U+FFFF. Its byte order is therefore UTF-16 code-unit order, not code-point order;
 * `String.compareTo` compares exactly those unsigned units. Keys come from parsed identifiers and
 * paths, which never contain U+0000 (the one code unit whose CESU-8 form breaks that equivalence).
 *
 * Comparing persisted order keeps append-only bulk writes monotonic, so `Store.putRight` never sees
 * a key below the store maximum.
 */
internal object CodeIndexKeyOrder : Comparator<CodeIndexKey> {
    override fun compare(left: CodeIndexKey, right: CodeIndexKey): Int =
        comparePersisted(left.value, right.value)

    fun comparePersisted(left: String, right: String): Int = left.compareTo(right)
}
