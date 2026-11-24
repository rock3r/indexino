package dev.sebastiano.indexino.producer

/**
 * A stat-hash cache entry served a hash that no longer matches the file.
 *
 * Windows exposes no inode change time, so a same-size rewrite that restores the modification time
 * is invisible to the stat key and a stale entry can stay trusted. When a re-read discovers the
 * mismatch, the entry is invalidated and the build restarts with a fresh capture instead of failing
 * every later refresh on the same poisoned entry.
 */
internal class StatHashCacheMismatchFailure(val source: IndexedSource) :
    IllegalStateException(
        "Stat-hash cache entry no longer matches the file " +
            "${source.originId}:${source.path}; the entry was invalidated"
    )
