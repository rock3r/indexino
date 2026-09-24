package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/** Checkpoints the physical writable store, never an overlay's inherited read-only view. */
internal class BuildStoreCheckpoint(
    private val store: CodeIndexStore,
    private val manifestPath: Path,
    private val progress: (String) -> Unit = {},
) : AutoCloseable {
    val directory: Path = directoryFor(manifestPath)
    private var backup: XodusCodeIndexStore? = null
    private var created = false
    private var retain = false

    fun <T> run(action: () -> T): T {
        capture()
        retain = true
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
        copyRecords(checkNotNull(backup), store)
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

    override fun close() {
        try {
            backup?.close()
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            retain = true
            throw failure
        }
        if (created && !retain) {
            Files.walkFileTree(
                directory,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        // Xodus leaves completed logs read-only, even after closing the
                        // environment.
                        // Do not follow links or change anything outside this disposable
                        // checkpoint.
                        if (attrs.isRegularFile) file.toFile().setWritable(true)
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        if (exc != null) throw exc
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
    }

    companion object {
        private const val DELETE_BATCH_SIZE = 256
        private const val COPY_BATCH_SIZE = 16

        fun requireRecovered(manifestPath: Path) {
            check(!directoryFor(manifestPath).exists()) {
                "An active or unfinished index build checkpoint requires recovery"
            }
        }

        private fun directoryFor(manifestPath: Path): Path =
            manifestPath.resolveSibling("${manifestPath.fileName}.rollback")
    }
}
