package dev.sebastiano.indexino.core.cache

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean

/** Shared activity leases exclude GC across processes; the monitor also serializes JVM peers. */
internal object CacheActivityLock {
    private class Entry(val channel: FileChannel, val lock: FileLock, var users: Int = 0)

    private val entries = mutableMapOf<Path, Entry>()

    fun acquire(cacheRoot: Path): AutoCloseable =
        synchronized(entries) {
            val path = lockPath(cacheRoot)
            val entry =
                entries.getOrPut(path) {
                    val channel = open(path)
                    try {
                        Entry(channel, channel.lock(0, Long.MAX_VALUE, true))
                    } catch (failure: IOException) {
                        channel.close()
                        throw failure
                    }
                }
            entry.users++
            val closed = AtomicBoolean()
            AutoCloseable {
                if (closed.compareAndSet(false, true)) {
                    synchronized(entries) {
                        entry.users--
                        if (entry.users == 0) {
                            entries.remove(path)
                            entry.channel.use { entry.lock.release() }
                        }
                    }
                }
            }
        }

    fun <T> withExclusiveLock(cacheRoot: Path, action: () -> T): T? =
        synchronized(entries) {
            val path = lockPath(cacheRoot)
            if (path in entries) return@synchronized null
            open(path).use { channel ->
                val lock =
                    try {
                        channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                lock?.use { action() }
            }
        }

    private fun lockPath(cacheRoot: Path): Path {
        Files.createDirectories(cacheRoot)
        return cacheRoot.toRealPath().resolve("activity.lock")
    }

    private fun open(path: Path): FileChannel =
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        )
}
