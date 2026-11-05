package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/** Checkpoints the physical writable store, never an overlay's inherited read-only view. */
internal class BuildStoreCheckpoint(
    private val store: CodeIndexStore,
    private val manifestPath: Path,
    private val progress: (String) -> Unit = {},
    private val overlay: Boolean = false,
) : AutoCloseable {
    val directory: Path = directoryFor(manifestPath)
    private var backup: XodusCodeIndexStore? = null
    private var created = false
    private var retain = false

    fun <T> run(action: () -> T): T {
        retain = true
        capture()
        try {
            return action().also { retain = false }
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            try {
                restore()
                retain = false
            } catch (@Suppress("TooGenericExceptionCaught") restoreFailure: Throwable) {
                if (restoreFailure !== failure) failure.addSuppressed(restoreFailure)
            }
            throw failure
        }
    }

    private fun capture() {
        progress("index phase=checkpoint state=started")
        val started = System.nanoTime()
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)
        created = true
        if (manifestPath.exists()) Files.copy(manifestPath, directory.resolve("manifest.json"))
        val target = XodusCodeIndexStore.open(directory.resolve("records"))
        backup = target
        copyRecords(store, target)
        target.flushAndSync()
        val marker = directory.resolve("ready.tmp")
        FileChannel.open(marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
            channel ->
            val content = ByteBuffer.wrap(if (overlay) OVERLAY_MARKER else BASE_MARKER)
            while (content.hasRemaining()) channel.write(content)
            channel.force(true)
        }
        Files.move(marker, directory.resolve("ready"), StandardCopyOption.ATOMIC_MOVE)
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        progress("index phase=checkpoint state=completed durationMillis=$elapsedMillis")
    }

    private fun restore() {
        while (true) {
            val keys = ArrayList<CodeIndexKey>(DELETE_BATCH_SIZE)
            store.forEachPrefix("") { key, _ ->
                keys += key
                keys.size < DELETE_BATCH_SIZE
            }
            if (keys.isEmpty()) break
            store.transaction { keys.forEach(store::delete) }
        }
        val source = backup
        if (source != null) copyRecords(source, store)
        else
            XodusCodeIndexStore.open(directory.resolve("records"), readOnly = true).use {
                copyRecords(it, store)
            }
        val oldManifest = directory.resolve("manifest.json")
        if (oldManifest.exists()) {
            Files.copy(oldManifest, manifestPath, StandardCopyOption.REPLACE_EXISTING)
        } else {
            Files.deleteIfExists(manifestPath)
        }
    }

    private fun copyRecords(source: CodeIndexStore, target: CodeIndexStore) {
        val batch = ArrayList<Pair<CodeIndexKey, CodeIndexRecord>>(COPY_BATCH_SIZE)
        fun flush() {
            if (batch.isEmpty()) return
            target.transaction { batch.forEach { (key, record) -> target.put(key, record) } }
            batch.clear()
        }
        source.forEachPrefix("") { key, record ->
            batch += key to record
            if (batch.size == COPY_BATCH_SIZE) flush()
            true
        }
        flush()
    }

    override fun close() =
        cleanupPhase("checkpoint-release") {
            try {
                backup?.let { pending ->
                    cleanupPhase("checkpoint-backup-close") { pending.close() }
                    backup = null
                }
            } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
                retain = true
                throw failure
            }
            if (created && !retain) {
                // A crash during deletion must not leave a ready marker beside a partial backup.
                Files.deleteIfExists(directory.resolve("ready"))
                cleanupPhase("checkpoint-delete") {
                    Files.walkFileTree(
                        directory,
                        object : SimpleFileVisitor<Path>() {
                            override fun visitFile(
                                file: Path,
                                attrs: BasicFileAttributes,
                            ): FileVisitResult {
                                // Xodus leaves completed logs read-only, even after closing the
                                // environment. Do not follow links or touch another directory.
                                if (attrs.isRegularFile) file.toFile().setWritable(true)
                                Files.delete(file)
                                return FileVisitResult.CONTINUE
                            }

                            override fun postVisitDirectory(
                                dir: Path,
                                exc: IOException?,
                            ): FileVisitResult {
                                if (exc != null) throw exc
                                Files.delete(dir)
                                return FileVisitResult.CONTINUE
                            }
                        },
                    )
                }
            }
        }

    private fun <T> cleanupPhase(phase: String, action: () -> T): T {
        val started = System.nanoTime()
        // A cancelled refresh may reject progress reporting; cleanup must still run.
        runCatching { progress("index phase=$phase state=started") }
        val result = action()
        runCatching {
            progress(
                "index phase=$phase state=completed durationMillis=" +
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            )
        }
        return result
    }

    companion object {
        private const val DELETE_BATCH_SIZE = 256
        private const val COPY_BATCH_SIZE = 16
        private val BASE_MARKER = "base\n".toByteArray()
        private val OVERLAY_MARKER = "overlay\n".toByteArray()

        fun requireRecovered(manifestPath: Path) {
            val directory = directoryFor(manifestPath)
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
            val ready = directory.resolve("ready")
            check(
                Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) &&
                    Files.isRegularFile(ready, LinkOption.NOFOLLOW_LINKS) &&
                    Files.size(ready) <= OVERLAY_MARKER.size &&
                    Files.isDirectory(directory.resolve("records"), LinkOption.NOFOLLOW_LINKS)
            ) {
                "An incomplete index build checkpoint requires manual recovery: $directory"
            }
            val marker = Files.readAllBytes(ready)
            val destination =
                when (marker.toList()) {
                    BASE_MARKER.toList() -> manifestPath.resolveSibling("base.xodus")
                    OVERLAY_MARKER.toList() ->
                        manifestPath.parent.parent.parent
                            .resolve("overlay-build")
                            .resolve(manifestPath.parent.fileName.toString())
                            .resolve("delta")
                    else -> error("Unknown index build checkpoint destination: $ready")
                }
            check(Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                "Missing index build checkpoint destination: $destination"
            }
            val checkpoint =
                XodusCodeIndexStore.open(destination).use { store ->
                    // Another writer may have completed and removed this checkpoint while
                    // opening the destination waited for its Xodus lock.
                    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
                    check(
                        Files.isRegularFile(ready, LinkOption.NOFOLLOW_LINKS) &&
                            Files.size(ready) == marker.size.toLong() &&
                            Files.readAllBytes(ready).contentEquals(marker) &&
                            Files.isDirectory(
                                directory.resolve("records"),
                                LinkOption.NOFOLLOW_LINKS,
                            )
                    ) {
                        "An incomplete index build checkpoint requires manual recovery: $directory"
                    }
                    BuildStoreCheckpoint(store, manifestPath).also { pending ->
                        pending.created = true
                        pending.retain = true
                        // A partial restore can be repeated; do not release the backup until
                        // both the records and the prior manifest are restored and the store
                        // closes.
                        pending.restore()
                    }
                }
            checkpoint.retain = false
            checkpoint.close()
        }

        private fun directoryFor(manifestPath: Path): Path =
            manifestPath.resolveSibling("${manifestPath.fileName}.rollback")
    }
}
