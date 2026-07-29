package dev.sebastiano.indexino.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path

/**
 * Runs an optional `indexino-script-host` script when that artifact is on the distribution
 * classpath.
 */
internal class ScriptCommand : CliktCommand(name = "script") {
    private val project by
        option("--project").file(mustExist = true, mustBeReadable = true).required()
    private val script by argument("script").file(mustExist = true, mustBeReadable = true)

    override fun run() {
        requireScriptSuffix(script.toPath())
        try {
            val report = runScript(requireNotNull(project).toPath(), script.toPath())
            findings(report).forEach(::echo)
        } catch (missing: ClassNotFoundException) {
            fail(
                "The script command requires indexino-script-host on the runtime classpath",
                missing,
            )
        } catch (missing: NoClassDefFoundError) {
            fail("The script command requires all indexino-script-host dependencies", missing)
        } catch (failure: InvocationTargetException) {
            fail("The script command failed", failure.targetException)
        }
    }

    private fun requireScriptSuffix(script: Path) {
        if (!script.fileName.toString().endsWith(SCRIPT_SUFFIX)) {
            echo("Script files must use the $SCRIPT_SUFFIX suffix", err = true)
            throw ProgramResult(CliExitCodes.INVALID_ARGUMENTS)
        }
    }

    private fun fail(prefix: String, failure: Throwable): Nothing {
        echo("$prefix: ${failure.message ?: failure::class.java.name}", err = true)
        throw ProgramResult(CliExitCodes.ANALYSIS_ERROR)
    }

    internal fun runScript(project: Path, script: Path): Any {
        val hostClass = Class.forName(SCRIPT_HOST_CLASS)
        val requestClass = Class.forName(SCRIPT_REQUEST_CLASS)
        val request =
            requestClass
                .getMethod("forFile", Path::class.java, Path::class.java)
                .invoke(null, project, script)
        val host = hostClass.getMethod("create").invoke(null)
        return hostClass.getMethod("run", requestClass).invoke(host, request)
    }

    private fun findings(report: Any): List<String> {
        @Suppress("UNCHECKED_CAST")
        val findings = report.javaClass.getMethod("getFindings").invoke(report) as List<Any>
        return findings.map(Any::toString)
    }

    private companion object {
        private const val SCRIPT_SUFFIX = ".indexino.kts"
        private const val SCRIPT_HOST_CLASS = "dev.sebastiano.indexino.script.IndexinoScriptHost"
        private const val SCRIPT_REQUEST_CLASS = "dev.sebastiano.indexino.script.ScriptRequest"
    }
}
