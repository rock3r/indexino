package dev.sebastiano.indexino.producer

import java.nio.file.Path

/** A source location whose path is relative to its independently owned origin. */
internal data class IndexedSource(val originId: String, val originRoot: Path, val path: String) {
    init {
        require(originId.isNotBlank()) { "Source origin ID must not be blank" }
        require(
            path.isNotBlank() &&
                !path.startsWith('/') &&
                path.split('/').none { it == ".." || it.isBlank() }
        ) {
            "Source path must be normalized and origin-relative: $path"
        }
    }

    companion object {
        fun workspace(root: Path, path: String): IndexedSource =
            IndexedSource("workspace", root, path)
    }
}
