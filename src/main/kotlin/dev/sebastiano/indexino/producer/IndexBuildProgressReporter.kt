package dev.sebastiano.indexino.producer

import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal interface IndexBuildProgressReporter {
    fun discoveryStarted()

    fun discoveryCompleted(sourceFileCount: Int)

    fun phaseStarted(phase: String, phaseTotal: Int?)

    fun fileProgress(phase: String, phaseCompleted: Int, phaseTotal: Int, currentFile: String)

    fun countersAvailable(changedFiles: Int, unchangedFiles: Int, removedFiles: Int)

    fun phaseCompleted(phase: String, phaseTotal: Int?)

    fun completed(outcome: String)

    fun failed(exitCode: Int, message: String)
}

internal class JsonlIndexBuildProgressReporter(private val emitLine: (String) -> Unit) :
    IndexBuildProgressReporter {
    private var counters: ChangeCounters? = null
    private var terminal = false

    override fun discoveryStarted() {
        emitEvent(
            event = "discovery_started",
            phase = DISCOVERY_PHASE,
            phaseTotal = null,
            includePhaseTotal = true,
        )
    }

    override fun discoveryCompleted(sourceFileCount: Int) {
        emitEvent(
            event = "discovery_completed",
            phase = DISCOVERY_PHASE,
            phaseCompleted = sourceFileCount,
            phaseTotal = sourceFileCount,
            includePhaseTotal = true,
        )
    }

    override fun phaseStarted(phase: String, phaseTotal: Int?) {
        emitEvent(
            event = "phase_started",
            phase = phase,
            phaseCompleted = 0,
            phaseTotal = phaseTotal,
            includePhaseTotal = true,
        )
    }

    override fun fileProgress(
        phase: String,
        phaseCompleted: Int,
        phaseTotal: Int,
        currentFile: String,
    ) {
        if (phaseCompleted !in 1..phaseTotal) {
            return
        }
        if (shouldEmitFileProgress(phaseCompleted, phaseTotal)) {
            emitEvent(
                event = "progress",
                phase = phase,
                phaseCompleted = phaseCompleted,
                phaseTotal = phaseTotal,
                includePhaseTotal = true,
                currentFile = normalizeWorkspaceRelativePath(currentFile),
            )
        }
    }

    override fun countersAvailable(changedFiles: Int, unchangedFiles: Int, removedFiles: Int) {
        val next = ChangeCounters(changedFiles, unchangedFiles, removedFiles)
        counters?.let { previous ->
            if (
                next.changedFiles < previous.changedFiles ||
                    next.unchangedFiles < previous.unchangedFiles ||
                    next.removedFiles < previous.removedFiles
            ) {
                return
            }
        }
        counters = next
        emitEvent(event = "changes_detected", phase = SOURCE_CHANGE_DETECTION_PHASE)
    }

    override fun phaseCompleted(phase: String, phaseTotal: Int?) {
        emitEvent(
            event = "phase_completed",
            phase = phase,
            phaseCompleted = phaseTotal,
            includePhaseCompleted = true,
            phaseTotal = phaseTotal,
            includePhaseTotal = true,
        )
    }

    override fun completed(outcome: String) {
        if (!terminal) {
            terminal = true
            emitEvent(event = "completed", outcome = outcome)
        }
    }

    override fun failed(exitCode: Int, message: String) {
        if (!terminal) {
            terminal = true
            emitEvent(event = "failed", exitCode = exitCode, message = message)
        }
    }

    private fun emitEvent(
        event: String,
        phase: String? = null,
        phaseCompleted: Int? = null,
        includePhaseCompleted: Boolean = false,
        phaseTotal: Int? = null,
        includePhaseTotal: Boolean = false,
        currentFile: String? = null,
        outcome: String? = null,
        exitCode: Int? = null,
        message: String? = null,
    ) {
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        fields["version"] = JsonPrimitive(MACHINE_PROGRESS_PROTOCOL_VERSION)
        fields["event"] = JsonPrimitive(event)
        phase?.let { fields["phase"] = JsonPrimitive(it) }
        if (includePhaseCompleted || phaseCompleted != null) {
            fields["phaseCompleted"] = phaseCompleted?.let(::JsonPrimitive) ?: JsonNull
        }
        if (includePhaseTotal) {
            fields["phaseTotal"] = phaseTotal?.let(::JsonPrimitive) ?: JsonNull
        }
        currentFile?.let { fields["currentFile"] = JsonPrimitive(it) }
        counters?.let {
            fields["changedFiles"] = JsonPrimitive(it.changedFiles)
            fields["unchangedFiles"] = JsonPrimitive(it.unchangedFiles)
            fields["removedFiles"] = JsonPrimitive(it.removedFiles)
        }
        outcome?.let { fields["outcome"] = JsonPrimitive(it) }
        exitCode?.let { fields["exitCode"] = JsonPrimitive(it) }
        message?.let { fields["message"] = JsonPrimitive(it) }
        emitLine(JSON.encodeToString(JsonObject(fields)))
    }

    private data class ChangeCounters(
        val changedFiles: Int,
        val unchangedFiles: Int,
        val removedFiles: Int,
    )

    private companion object {
        const val DISCOVERY_PHASE = "discovery"
        const val FILE_PROGRESS_INTERVAL = 25
        val JSON = Json

        fun shouldEmitFileProgress(completed: Int, total: Int): Boolean =
            completed == 1 || completed == total || completed % FILE_PROGRESS_INTERVAL == 0
    }
}

internal const val MACHINE_PROGRESS_PROTOCOL_VERSION = 1
internal const val SOURCE_CHANGE_DETECTION_PHASE = "source-change-detection"

internal fun normalizeWorkspaceRelativePath(path: String): String {
    require(!WINDOWS_ABSOLUTE_PATH.matches(path)) {
        "progress path must be workspace-relative: $path"
    }
    val normalized = Path.of(path.replace('\\', '/')).normalize()
    require(!normalized.isAbsolute) { "progress path must be workspace-relative: $path" }
    require(!normalized.startsWith("..")) { "progress path escapes workspace: $path" }
    return normalized.toString().replace('\\', '/')
}

private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[/\\\\].*")
