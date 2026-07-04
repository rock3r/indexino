package com.kotlincodeindex.producer.selectioncontext

import com.kotlincodeindex.application.selectioncontext.SelectionWalker
import com.kotlincodeindex.core.key.CodeIndexKey
import com.kotlincodeindex.core.record.ComposeSelectionSiteRecord
import com.kotlincodeindex.core.record.DisableSelectionRef
import com.kotlincodeindex.core.record.SelectionContainerRef
import com.kotlincodeindex.core.store.CodeIndexStore
import com.kotlincodeindex.parse.KotlinPsiParser
import com.kotlincodeindex.producer.IndexBuildContext
import com.kotlincodeindex.producer.IndexProducer
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtSimpleNameExpression

class SelectionContextProducer(
    private val walker: SelectionWalker = SelectionWalker(),
) : IndexProducer {
    override val id: String = "selection-context"
    override val displayName: String = "SelectionContextProducer"

    override fun produce(context: IndexBuildContext, store: CodeIndexStore) {
        deleteAllSelectionSiteKeys(store)
        KotlinPsiParser().use { parser ->
            val ktFiles = context.sourceFiles.filter { it.endsWith(".kt") }
            ktFiles.forEachIndexed { index, relativePath ->
                context.reportFileProgress(index + 1, ktFiles.size, relativePath)
                deleteStaleKeys(store, relativePath)
                val content = context.readSource(relativePath)
                val file = parser.parseFile(relativePath, content)
                for (call in enumerateComposableCallSites(file)) {
                    val contextResult = walker.analyzeCallSite(call, relativePath)
                    val line = call.lineNumber()
                    val column = call.columnNumber()
                    store.put(
                        CodeIndexKey.composeSelectionSite(relativePath, line, column),
                        ComposeSelectionSiteRecord(
                            callee = contextResult.callee,
                            inSelectionContainer = contextResult.inSelectionContainer,
                            selectionContainerCount = contextResult.selectionContainerCount,
                            excludedByDisableSelection = contextResult.excludedByDisableSelection,
                            selectionContainers = contextResult.selectionContainers.map {
                                SelectionContainerRef(it.file, it.line, it.function)
                            },
                            disableSelection = contextResult.disableSelection?.let {
                                DisableSelectionRef(it.file, it.line, it.function)
                            },
                            confidence = contextResult.confidence,
                            indexedFromCommit = context.commitHash,
                        ),
                    )
                }
            }
        }
    }

    private fun deleteAllSelectionSiteKeys(store: CodeIndexStore) {
        store.prefixScan("compose:selection-site:")
            .forEach { (key, _) -> store.delete(key) }
    }

    private fun deleteStaleKeys(store: CodeIndexStore, relativeFile: String) {
        val prefix = CodeIndexKey.composeSelectionSiteFilePrefix(relativeFile)
        store.prefixScan(prefix).forEach { (key, _) -> store.delete(key) }
    }

    private fun enumerateComposableCallSites(file: KtFile): List<KtCallExpression> {
        val scNames = SelectionWalker.DEFAULT_SELECTION_CONTAINER_NAMES
        val dsNames = SelectionWalker.DEFAULT_DISABLE_SELECTION_NAMES
        val scAliases = resolveAliases(file, scNames)
        val dsAliases = resolveAliases(file, dsNames)

        return file.collectDescendantsOfType<KtCallExpression>()
            .mapNotNull { call -> findEnclosingComposable(call)?.let { call to it } }
            .filter { (call, enclosing) ->
                val name = extractCalleeName(call) ?: return@filter false
                name !in scAliases && name !in dsAliases && !isLocalNonComposableCall(call, enclosing, name)
            }
            .map { it.first }
    }

    private fun isLocalNonComposableCall(
        call: KtCallExpression,
        enclosingComposable: KtNamedFunction,
        calleeName: String,
    ): Boolean {
        val body = enclosingComposable.bodyExpression ?: return false
        return body.collectDescendantsOfType<KtNamedFunction>()
            .any { fn ->
                fn.name == calleeName &&
                    !fn.annotationEntries.any {
                        it.shortName?.asString() == SelectionWalker.COMPOSABLE_ANNOTATION
                    }
            }
    }

    private fun findEnclosingComposable(call: KtCallExpression): KtNamedFunction? {
        var current: PsiElement? = call.parent
        while (current != null) {
            if (current is KtNamedFunction &&
                current.annotationEntries.any { it.shortName?.asString() == SelectionWalker.COMPOSABLE_ANNOTATION }
            ) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun resolveAliases(file: KtFile, canonicalNames: Set<String>): Set<String> {
        val names = canonicalNames.toMutableSet()
        for (import in file.importDirectives) {
            val importedName = import.importedFqName?.shortName()?.asString() ?: continue
            if (importedName !in canonicalNames) {
                continue
            }
            import.aliasName?.let { names += it }
        }
        return names
    }

    private fun extractCalleeName(call: KtCallExpression): String? {
        val callee = call.calleeExpression ?: return null
        return when (callee) {
            is KtSimpleNameExpression -> callee.getReferencedName()
            is KtDotQualifiedExpression ->
                (callee.selectorExpression as? KtSimpleNameExpression)?.getReferencedName()
            else -> null
        }
    }

    private fun KtCallExpression.lineNumber(): Int {
        val doc = containingFile.viewProvider.document
            ?: error("No document for ${containingFile.name}")
        return doc.getLineNumber(textRange.startOffset) + 1
    }

    private fun KtCallExpression.columnNumber(): Int {
        val doc = containingFile.viewProvider.document
            ?: error("No document for ${containingFile.name}")
        val line = doc.getLineNumber(textRange.startOffset)
        val lineStart = doc.getLineStartOffset(line)
        return textRange.startOffset - lineStart + 1
    }
}

private inline fun <reified T> org.jetbrains.kotlin.psi.KtElement.collectDescendantsOfType(): List<T> {
    val results = mutableListOf<T>()
    accept(object : org.jetbrains.kotlin.com.intellij.psi.PsiElementVisitor() {
        override fun visitElement(element: org.jetbrains.kotlin.com.intellij.psi.PsiElement) {
            if (element is T) {
                results += element
            }
            element.acceptChildren(this)
        }
    })
    return results
}
