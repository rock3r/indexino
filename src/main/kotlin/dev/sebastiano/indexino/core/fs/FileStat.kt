package dev.sebastiano.indexino.core.fs

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

/** Portable identity and change evidence for one filesystem entry. */
internal class FileStat(
    val size: Long,
    val modifiedNanos: Long,
    val changedNanos: Long,
    val fileKey: String?,
)

internal object FileStatReader {
    private const val UNIX_ATTRIBUTES = "unix:size,lastModifiedTime,ctime,dev,ino"
    private val unixView = FileSystems.getDefault().supportedFileAttributeViews().contains("unix")

    /** One `statx`/`lstat` where the provider supports the Unix view, two calls on Windows. */
    fun read(path: Path, followLinks: Boolean = true): FileStat? =
        runCatching { if (unixView) readUnix(path, followLinks) else readBasic(path, followLinks) }
            .getOrNull()

    private fun readUnix(path: Path, followLinks: Boolean): FileStat {
        val attributes =
            if (followLinks) {
                Files.readAttributes(path, UNIX_ATTRIBUTES)
            } else {
                Files.readAttributes(path, UNIX_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
            }
        val size = attributes["size"] as Long
        val modified = (attributes["lastModifiedTime"] as FileTime).to(TimeUnit.NANOSECONDS)
        val changed = (attributes["ctime"] as FileTime).to(TimeUnit.NANOSECONDS)
        val key = "${attributes["dev"]}:${attributes["ino"]}"
        return FileStat(size, modified, changed, key)
    }

    private fun readBasic(path: Path, followLinks: Boolean): FileStat {
        val attributes =
            if (followLinks) {
                Files.readAttributes(path, BasicFileAttributes::class.java)
            } else {
                Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            }
        return FileStat(
            size = attributes.size(),
            modifiedNanos = attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),
            // Java exposes no inode change time on Windows; creation time plus modification time
            // and file identity are the remaining evidence.
            changedNanos = attributes.creationTime().to(TimeUnit.NANOSECONDS),
            fileKey = attributes.fileKey()?.toString(),
        )
    }
}
