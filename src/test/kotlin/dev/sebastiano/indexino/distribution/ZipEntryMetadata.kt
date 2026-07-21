package dev.sebastiano.indexino.distribution

import java.nio.file.Files
import java.nio.file.Path

internal data class ZipEntryMetadata(val name: String, val unixMode: Int, val dosSecond: Int)

internal fun readZipCentralDirectory(archive: Path): List<ZipEntryMetadata> {
    val bytes = Files.readAllBytes(archive)
    val endOfCentralDirectory = findEndOfCentralDirectory(bytes)
    val entryCount = littleEndianShort(bytes, endOfCentralDirectory + 10)
    val centralDirectorySize = littleEndianInt(bytes, endOfCentralDirectory + 12)
    var offset = littleEndianInt(bytes, endOfCentralDirectory + 16)
    val centralDirectoryEnd = offset + centralDirectorySize
    val entries = ArrayList<ZipEntryMetadata>(entryCount)
    repeat(entryCount) {
        check(littleEndianInt(bytes, offset) == CENTRAL_DIRECTORY_SIGNATURE) {
            "Invalid ZIP central-directory entry at offset $offset"
        }
        val dosTime = littleEndianShort(bytes, offset + 12)
        val nameLength = littleEndianShort(bytes, offset + 28)
        val extraLength = littleEndianShort(bytes, offset + 30)
        val commentLength = littleEndianShort(bytes, offset + 32)
        val externalAttributes = littleEndianInt(bytes, offset + 38)
        val name = String(bytes, offset + CENTRAL_DIRECTORY_HEADER_SIZE, nameLength, Charsets.UTF_8)
        entries +=
            ZipEntryMetadata(
                name = name,
                unixMode = externalAttributes ushr 16 and POSIX_PERMISSION_MASK,
                dosSecond = (dosTime and DOS_SECOND_MASK) * 2,
            )
        offset += CENTRAL_DIRECTORY_HEADER_SIZE + nameLength + extraLength + commentLength
    }
    check(offset == centralDirectoryEnd) {
        "ZIP central-directory length mismatch: expected $centralDirectoryEnd, got $offset"
    }
    return entries
}

private fun findEndOfCentralDirectory(bytes: ByteArray): Int {
    val firstPossibleOffset =
        (bytes.size - END_OF_CENTRAL_DIRECTORY_MIN_SIZE - ZIP_MAX_COMMENT_SIZE).coerceAtLeast(0)
    for (offset in bytes.size - END_OF_CENTRAL_DIRECTORY_MIN_SIZE downTo firstPossibleOffset) {
        if (littleEndianInt(bytes, offset) == END_OF_CENTRAL_DIRECTORY_SIGNATURE) return offset
    }
    error("ZIP end-of-central-directory record not found")
}

private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
    bytes[offset].toInt() and
        0xff or
        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
        ((bytes[offset + 2].toInt() and 0xff) shl 16) or
        ((bytes[offset + 3].toInt() and 0xff) shl 24)

private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
    bytes[offset].toInt() and 0xff or ((bytes[offset + 1].toInt() and 0xff) shl 8)

private const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50
private const val CENTRAL_DIRECTORY_HEADER_SIZE = 46
private const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50
private const val END_OF_CENTRAL_DIRECTORY_MIN_SIZE = 22
private const val ZIP_MAX_COMMENT_SIZE = 65_535
private const val POSIX_PERMISSION_MASK = 0x1ff
private const val DOS_SECOND_MASK = 0x1f
