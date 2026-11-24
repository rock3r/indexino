package dev.sebastiano.indexino.parse

import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * Kotlin PSI environment shared by a bounded parse pool. Worker threads get their own
 * [KtPsiFactory] and never share a parser instance; the compiler's test environment is
 * process-global, so the pool disposes it once instead of once per worker.
 */
@OptIn(K1Deprecation::class, CompilerConfiguration.Internals::class)
internal class KotlinPsiParserPool(workerCount: Int) : AutoCloseable {
    init {
        require(workerCount > 0) { "A Kotlin parser pool needs at least one worker" }
        IdeaHomeBootstrap.ensure()
    }

    val workerCount: Int = workerCount

    private val disposable = Disposer.newDisposable("KotlinPsiParserPool")
    private val environment =
        KotlinCoreEnvironment.createForTests(
            disposable,
            CompilerConfiguration(),
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
    private val psiFactories =
        List(workerCount) { KtPsiFactory(environment.project, markGenerated = false) }

    fun parseFile(worker: Int, name: String, content: String): KtFile =
        psiFactories[worker].createFile(name, content)

    override fun close() {
        ApplicationManager.getApplication().runWriteAction { Disposer.dispose(disposable) }
    }
}
