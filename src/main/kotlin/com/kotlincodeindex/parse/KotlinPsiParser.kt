package com.kotlincodeindex.parse

import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

@OptIn(K1Deprecation::class, CompilerConfiguration.Internals::class)
class KotlinPsiParser : AutoCloseable {
    init {
        IdeaHomeBootstrap.ensure()
    }

    private val disposable = Disposer.newDisposable("KotlinPsiParser")
    private val environment =
        KotlinCoreEnvironment.createForTests(
            disposable,
            CompilerConfiguration(),
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
    private val psiFactory = KtPsiFactory(environment.project, markGenerated = false)

    fun parseFile(name: String, content: String): KtFile = psiFactory.createFile(name, content)

    override fun close() {
        ApplicationManager.getApplication().runWriteAction { Disposer.dispose(disposable) }
    }
}
