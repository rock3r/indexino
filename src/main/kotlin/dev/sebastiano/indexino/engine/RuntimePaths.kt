package dev.sebastiano.indexino.engine

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.io.IOException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat

/** Internal cache locations for one workspace runtime. */
internal object RuntimePaths {
    const val MAX_SOCKET_PATH_LENGTH = 102
    const val WORKSPACE_ID_LENGTH = 16
    private const val CACHE_ROOT_HASH_LENGTH = 16

    fun runtimeDirectory(cacheRoot: Path): Path = cacheRoot.resolve("runtime")

    fun socketPath(cacheRoot: Path, workspaceId: String): Path {
        require(workspaceId.length == WORKSPACE_ID_LENGTH) {
            "Workspace runtime IDs must be $WORKSPACE_ID_LENGTH hexadecimal characters"
        }
        val path = runtimeDirectory(cacheRoot).resolve("$workspaceId.sock")
        if (path.toString().length <= MAX_SOCKET_PATH_LENGTH) return path

        // AF_UNIX names have a kernel-sized path limit. The lease remains under the cache root,
        // while this deterministic per-cache endpoint keeps the actual socket name bindable.
        return Path.of("/tmp")
            .resolve("indexino-runtime-${cacheRootHash(cacheRoot)}")
            .resolve("$workspaceId.sock")
    }

    fun leasePath(cacheRoot: Path, workspaceId: String): Path =
        runtimeDirectory(cacheRoot).resolve("$workspaceId.lease.json")

    fun tombstonePath(cacheRoot: Path, workspaceId: String): Path =
        cacheRoot.resolve("registry").resolve("tombstones").resolve("$workspaceId.json")

    fun workspaceFileKey(workspace: Path, attributes: BasicFileAttributes): String? =
        attributes.fileKey()?.toString()
            ?: if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                WindowsFileMetadata.fileId(workspace)
            } else {
                null
            }

    private fun cacheRootHash(cacheRoot: Path): String =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256").digest(cacheRoot.toString().toByteArray())
            )
            .take(CACHE_ROOT_HASH_LENGTH)
}

/** Windows NIO can omit both file identity and hard-link count on NTFS. */
internal object WindowsFileMetadata {
    fun fileId(path: Path, noFollowLinks: Boolean = false): String =
        withHandle(path, noFollowLinks) { handle ->
            // FILE_ID_INFO: 8-byte volume serial followed by the 16-byte file ID.
            Memory(FILE_ID_INFO_SIZE.toLong()).use { info ->
                if (
                    !kernel32.GetFileInformationByHandleEx(
                        handle,
                        FILE_ID_INFO,
                        info,
                        FILE_ID_INFO_SIZE,
                    )
                ) {
                    throw IOException(
                        "Cannot read file identity: Windows error ${Native.getLastError()}"
                    )
                }
                "win:${HexFormat.of().formatHex(info.getByteArray(0, FILE_ID_INFO_SIZE))}"
            }
        }

    fun linkCount(path: Path): Int =
        withHandle(path, noFollowLinks = true) { handle ->
            // BY_HANDLE_FILE_INFORMATION: nNumberOfLinks at byte offset 40.
            Memory(FILE_INFORMATION_SIZE.toLong()).use { info ->
                if (!kernel32.GetFileInformationByHandle(handle, info)) {
                    throw IOException(
                        "Cannot read link count: Windows error ${Native.getLastError()}"
                    )
                }
                info.getInt(NUMBER_OF_LINKS_OFFSET.toLong())
            }
        }

    private fun <T> withHandle(path: Path, noFollowLinks: Boolean, block: (Pointer) -> T): T {
        val handle =
            kernel32.CreateFileW(
                WString(path.toString()),
                FILE_READ_ATTRIBUTES,
                FILE_SHARE_ALL,
                null,
                OPEN_EXISTING,
                FILE_FLAG_BACKUP_SEMANTICS or
                    (if (noFollowLinks) FILE_FLAG_OPEN_REPARSE_POINT else 0),
                null,
            )
        if (handle == null || Pointer.nativeValue(handle) == -1L) {
            throw IOException("Cannot open file metadata: Windows error ${Native.getLastError()}")
        }
        try {
            return block(handle)
        } finally {
            kernel32.CloseHandle(handle)
        }
    }

    private val kernel32: Kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }

    private interface Kernel32 : StdCallLibrary {
        @Suppress("FunctionName")
        fun CreateFileW(
            path: WString,
            desiredAccess: Int,
            shareMode: Int,
            securityAttributes: Pointer?,
            creationDisposition: Int,
            flags: Int,
            templateFile: Pointer?,
        ): Pointer?

        @Suppress("FunctionName")
        fun GetFileInformationByHandleEx(
            handle: Pointer,
            informationClass: Int,
            information: Pointer,
            bufferSize: Int,
        ): Boolean

        @Suppress("FunctionName")
        fun GetFileInformationByHandle(handle: Pointer, information: Pointer): Boolean

        @Suppress("FunctionName") fun CloseHandle(handle: Pointer): Boolean
    }

    private const val FILE_READ_ATTRIBUTES = 0x80
    private const val FILE_SHARE_ALL = 0x7
    private const val OPEN_EXISTING = 3
    private const val FILE_FLAG_BACKUP_SEMANTICS = 0x02000000
    private const val FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000
    private const val FILE_ID_INFO = 18
    private const val FILE_ID_INFO_SIZE = 24
    private const val FILE_INFORMATION_SIZE = 52
    private const val NUMBER_OF_LINKS_OFFSET = 40
}
