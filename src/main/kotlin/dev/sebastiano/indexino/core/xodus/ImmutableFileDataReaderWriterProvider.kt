package dev.sebastiano.indexino.core.xodus

import java.nio.file.Files
import java.nio.file.Path
import jetbrains.exodus.core.dataStructures.Pair
import jetbrains.exodus.io.Block
import jetbrains.exodus.io.DataReader
import jetbrains.exodus.io.DataReaderWriterProvider
import jetbrains.exodus.io.DataWriter
import jetbrains.exodus.io.FileDataReader
import jetbrains.exodus.io.RemoveBlockType

/**
 * Allows independent processes to read a finalized generation without granting Xodus recovery
 * writes.
 */
internal class ImmutableFileDataReaderWriterProvider : DataReaderWriterProvider() {
    override fun isReadonly(): Boolean = true

    override fun newReaderWriter(location: String): Pair<DataReader, DataWriter> {
        val path = Path.of(location)
        require(Files.isDirectory(path)) { "Immutable Xodus generation is missing: $path" }
        return Pair(FileDataReader(path.toFile()), NoWrites)
    }

    private object NoWrites : DataWriter {
        override fun isOpen(): Boolean = false

        override fun write(b: ByteArray, off: Int, len: Int): Block = reject()

        override fun openOrCreateBlock(address: Long, length: Long): Block = reject()

        override fun removeBlock(blockAddress: Long, rbt: RemoveBlockType): Unit = reject()

        override fun truncateBlock(blockAddress: Long, length: Long): Unit = reject()

        override fun clear(): Unit = reject()

        override fun sync() = Unit

        override fun syncDirectory() = Unit

        override fun close() = Unit

        override fun lock(timeout: Long): Boolean = false

        override fun release(): Boolean = true

        override fun lockInfo(): String = "Immutable Xodus generation cannot be locked for writing"

        private fun <T> reject(): T = error("Immutable Xodus generation cannot be modified")
    }
}
