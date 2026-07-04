package com.kotlincodeindex.core.path

import java.nio.file.Path

class IndexPathResolver(
    private val workspaceRoot: Path,
    private val storeDirName: String = IndexPaths.STORE_DIR_NAME,
) {
    fun storeRoot(): Path = workspaceRoot.resolve(storeDirName)

    fun resolveIndexDirectory(commitHash: String): Path =
        storeRoot().resolve(IndexPaths.INDEX_DIR_NAME).resolve(commitHash)

    fun resolveBaseStore(commitHash: String): Path =
        resolveIndexDirectory(commitHash).resolve(IndexPaths.BASE_STORE_DIR_NAME)

    fun resolveManifest(commitHash: String): Path =
        resolveIndexDirectory(commitHash).resolve(IndexPaths.MANIFEST_FILE_NAME)

    fun resolveSessionDeltaStore(sessionId: String): Path =
        storeRoot().resolve(IndexPaths.SESSIONS_DIR_NAME).resolve(sessionId)
            .resolve(IndexPaths.DELTA_STORE_DIR_NAME)
}