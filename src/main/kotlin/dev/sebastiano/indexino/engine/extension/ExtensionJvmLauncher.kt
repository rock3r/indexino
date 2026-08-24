package dev.sebastiano.indexino.engine.extension

import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal object ExtensionJvmLauncher {
    internal var workerMainClass: String =
        "dev.sebastiano.indexino.engine.extension.ExtensionWorkerMain"

    internal var workerClasspath: String? = null

    internal var javaExecutable: String? = null

    fun launchWorker(
        socketPath: Path,
        sessionToken: String,
        pluginJar: Path,
        pluginId: String,
        checkId: String,
    ): Process {
        val java = resolveJavaExecutable()
        val classpath = resolveWorkerClasspath()
        val command = buildList {
            add(java)
            add("-Xmx${ExtensionProtocolConstants.WORKER_MAX_HEAP}")
            add("-cp")
            add(classpath)
            add(workerMainClass)
            add("--socket")
            add(socketPath.toString())
            add("--session-token")
            add(sessionToken)
            add("--plugin-jar")
            add(pluginJar.toString())
            add("--plugin-id")
            add(pluginId)
            add("--check-id")
            add(checkId)
        }
        return ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .start()
            .also { ExtensionProcessSupport.installKillOnClose(it) }
    }

    private fun resolveJavaExecutable(): String =
        javaExecutable
            ?: System.getProperty("indexino.extension.java")
            ?: System.getenv("INDEXINO_EXTENSION_JAVA")
            ?: packagedRuntimeJava()
            ?: fallbackJavaExecutable()

    private fun fallbackJavaExecutable(): String {
        if (DistributionCapabilities.requiresOutOfProcessExtensions()) {
            throw dev.sebastiano.indexino.api.indexinoFailure(
                category = dev.sebastiano.indexino.model.IndexFailureCategory.INVALID_REQUEST,
                code = "extension_java_missing",
                message =
                    "Extension worker JVM is unavailable; set INDEXINO_EXTENSION_JAVA to a Java " +
                        "launcher or use a native install that packages runtime/bin/java",
                retryable = false,
            )
        }
        return Path.of(System.getProperty("java.home"), "bin", "java").toString()
    }

    private fun resolveWorkerClasspath(): String =
        workerClasspath
            ?: System.getProperty("indexino.extension.worker.classpath")
            ?: System.getenv("INDEXINO_EXTENSION_WORKER_CLASSPATH")
            ?: packagedExtensionWorkerJar()
            ?: run {
                val codeSource =
                    ExtensionWorkerMain::class.java.protectionDomain.codeSource?.location?.toURI()
                requireNotNull(codeSource) { "Extension worker classpath is unavailable" }.path
            }

    private fun packagedInstallRoot(): Path? {
        if (!DistributionCapabilities.requiresOutOfProcessExtensions()) return null
        val hostJar =
            ExtensionHost::class.java.protectionDomain.codeSource?.location?.toURI()?.path
                ?: return null
        return Path.of(hostJar).parent
    }

    private fun packagedExtensionWorkerJar(): String? {
        val installRoot = packagedInstallRoot() ?: return null
        val workerJar = installRoot.resolve("indexino-extension-worker.jar")
        return workerJar.toFile().takeIf { it.isFile }?.absolutePath
    }

    private fun packagedRuntimeJava(): String? {
        val installRoot = packagedInstallRoot() ?: return null
        val unixJava = installRoot.resolve("runtime/bin/java")
        if (unixJava.toFile().isFile) return unixJava.toString()
        val windowsJava = installRoot.resolve("runtime/bin/java.exe")
        return windowsJava.toFile().takeIf { it.isFile }?.absolutePath
    }
}

internal object ExtensionProcessSupport {
    fun destroyProcessTree(process: Process) {
        val root = process.toHandle()
        root.descendants().forEach { descendant -> descendant.destroyForcibly() }
        root.destroyForcibly()
        if (
            !process.waitFor(
                ExtensionProtocolConstants.PROCESS_DESTROY_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
        ) {
            process.destroy()
        }
    }

    fun installKillOnClose(process: Process) {
        Runtime.getRuntime().addShutdownHook(Thread { destroyProcessTree(process) })
    }
}
