package dev.sebastiano.indexino.plugin.selection

import dev.sebastiano.indexino.model.PluginFactValue
import dev.sebastiano.indexino.model.SourceLocation
import dev.sebastiano.indexino.model.SourceRange
import dev.sebastiano.indexino.plugin.api.FileAnalysisContextV1
import dev.sebastiano.indexino.plugin.api.FileAnalyzerV1
import dev.sebastiano.indexino.plugin.selection.parse.KotlinPsiParser
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtSimpleNameExpression

@OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)
internal class SelectionContextAnalyzer(private val walker: SelectionWalker = SelectionWalker()) :
    FileAnalyzerV1, AutoCloseable {
    override val id: String = "selection-context"
    private var parser: KotlinPsiParser? = null

    override suspend fun analyze(context: FileAnalysisContextV1) {
        if (!context.file.path.endsWith(".kt")) return
        val file = parser().parseFile(context.file.path, context.sourceText)
        for (call in enumerateComposableCallSites(file)) {
            context.ensureActive()
            val result = walker.analyzeCallSite(call, context.file.path)
            val line = call.lineNumber()
            val column = call.columnNumber()
            context.facts.putAt(
                key = "selection-site:$line:$column",
                range = call.toSourceRange(context, line, column),
                value =
                    PluginFactValue.Struct.of(
                        mapOf(
                            "callee" to PluginFactValue.Text.of(result.callee),
                            "inSelectionContainer" to
                                PluginFactValue.Bool.of(result.inSelectionContainer),
                            "selectionContainerCount" to
                                PluginFactValue.Integer.of(result.selectionContainerCount.toLong()),
                            "excludedByDisableSelection" to
                                PluginFactValue.Bool.of(result.excludedByDisableSelection),
                            "confidence" to PluginFactValue.Text.of(result.confidence),
                        )
                    ),
            )
        }
    }

    override fun close() {
        parser?.close()
        parser = null
    }

    private fun parser(): KotlinPsiParser = parser ?: KotlinPsiParser().also { parser = it }

    private fun enumerateComposableCallSites(file: KtFile): List<KtCallExpression> {
        val scNames = SelectionWalker.DEFAULT_SELECTION_CONTAINER_NAMES
        val dsNames = SelectionWalker.DEFAULT_DISABLE_SELECTION_NAMES
        val scAliases = resolveAliases(file, scNames)
        val dsAliases = resolveAliases(file, dsNames)
        return file
            .collectDescendantsOfType<KtCallExpression>()
            .mapNotNull { call -> findEnclosingComposable(call)?.let { call to it } }
            .filter { (call, enclosing) ->
                val name = extractCalleeName(call) ?: return@filter false
                name !in scAliases &&
                    name !in dsAliases &&
                    !isLocalNonComposableCall(enclosing, name)
            }
            .map { it.first }
    }

    private fun isLocalNonComposableCall(
        enclosingComposable: KtNamedFunction,
        calleeName: String,
    ): Boolean {
        val body = enclosingComposable.bodyExpression ?: return false
        return body.collectDescendantsOfType<KtNamedFunction>().any { fn ->
            fn.name == calleeName &&
                !fn.annotationEntries.any {
                    it.shortName?.asString() == SelectionWalker.COMPOSABLE_ANNOTATION
                }
        }
    }

    private fun findEnclosingComposable(call: KtCallExpression): KtNamedFunction? {
        var current: PsiElement? = call.parent
        while (current != null) {
            if (
                current is KtNamedFunction &&
                    current.annotationEntries.any {
                        it.shortName?.asString() == SelectionWalker.COMPOSABLE_ANNOTATION
                    }
            )
                return current
            current = current.parent
        }
        return null
    }

    private fun resolveAliases(file: KtFile, canonicalNames: Set<String>): Set<String> {
        val names = canonicalNames.toMutableSet()
        file.importDirectives.forEach { imported ->
            val name = imported.importedFqName?.shortName()?.asString() ?: return@forEach
            if (name in canonicalNames) imported.aliasName?.let { names += it }
        }
        return names
    }

    private fun extractCalleeName(call: KtCallExpression): String? =
        when (val callee = call.calleeExpression) {
            is KtSimpleNameExpression -> callee.getReferencedName()
            is KtDotQualifiedExpression ->
                (callee.selectorExpression as? KtSimpleNameExpression)?.getReferencedName()
            else -> null
        }

    private fun KtCallExpression.lineNumber(): Int =
        containingFile.viewProvider.document!!.getLineNumber(textRange.startOffset) + 1

    private fun KtCallExpression.columnNumber(): Int {
        val document = containingFile.viewProvider.document!!
        val line = document.getLineNumber(textRange.startOffset)
        return textRange.startOffset - document.getLineStartOffset(line) + 1
    }

    private fun KtCallExpression.toSourceRange(
        context: FileAnalysisContextV1,
        startLine: Int,
        startColumn: Int,
    ): SourceRange {
        val document = containingFile.viewProvider.document!!
        val endLine = document.getLineNumber(textRange.endOffset) + 1
        val endColumn = textRange.endOffset - document.getLineStartOffset(endLine - 1) + 1
        return SourceRange.of(
            SourceLocation.of(context.file, startLine, startColumn, textRange.startOffset),
            SourceLocation.of(context.file, endLine, endColumn, textRange.endOffset),
        )
    }
}

private inline fun <reified T> org.jetbrains.kotlin.psi.KtElement.collectDescendantsOfType():
    List<T> {
    val results = mutableListOf<T>()
    accept(
        object : org.jetbrains.kotlin.com.intellij.psi.PsiElementVisitor() {
            override fun visitElement(element: org.jetbrains.kotlin.com.intellij.psi.PsiElement) {
                if (element is T) {
                    results += element
                }
                element.acceptChildren(this)
            }
        }
    )
    return results
}
